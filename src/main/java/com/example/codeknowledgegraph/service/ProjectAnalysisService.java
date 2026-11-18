package com.example.codeknowledgegraph.service;

import com.example.codeknowledgegraph.parser.CodeAnalyzer;
import com.example.codeknowledgegraph.parser.model.AnalysisResult;
import com.example.codeknowledgegraph.parser.model.AnalysisResult.MethodInfo;
import com.example.codeknowledgegraph.parser.model.AnalysisResult.TypeInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * F3.3-F3.5: 分析编排器——决定全量还是增量,并落地。
 *
 * 增量条件: 项目已有分析记录、rootPath 一致、本次为 git 仓库且已知上次 commit(F3.4 不满足则降级全量)。
 * 变更检测覆盖未提交改动(AI 改完文件不 commit 就重新分析是最高频场景)。
 * 变更文件数超过阈值时回退全量,避免病态 diff 比全量更慢。
 */
@Service
public class ProjectAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(ProjectAnalysisService.class);

    /** 超过该文件数直接回退全量 */
    private static final int FULL_FALLBACK_FILES = 300;

    private final CodeAnalyzer analyzer;
    private final GraphStoreService store;
    private final GitInfoService git;

    public ProjectAnalysisService(CodeAnalyzer analyzer, GraphStoreService store, GitInfoService git) {
        this.analyzer = analyzer;
        this.store = store;
        this.git = git;
    }

    /**
     * 分析项目:自动选择全量/增量(F3.5: forceFull=true 强制全量)。
     * 返回统计信息,含 mode: full / incremental / skipped。
     */
    public Map<String, Object> analyze(String projectName, Path root, boolean forceFull)
            throws IOException, InterruptedException {
        long start = System.currentTimeMillis();
        Path abs = root.toAbsolutePath();

        Map<String, Object> state = store.loadProjectState(projectName);
        String prevCommit = str(state.get("commit"));
        String prevPath = str(state.get("path"));
        Integer prevFileCount = state.get("fileCount") instanceof Number n ? n.intValue() : null;
        String head = git.headCommit(abs);

        boolean sameRoot = !prevPath.isBlank()
                && Path.of(prevPath).normalize().equals(abs.normalize());

        if (!forceFull && head != null && !prevCommit.isBlank() && sameRoot) {
            GitInfoService.ChangeSet cs = git.changeSince(abs, prevCommit);
            List<String> reanalyze = cs.reanalyze().stream()
                    .filter(f -> f.endsWith(".java") && !f.contains("/target/")).toList();
            List<String> deleted = cs.deleted().stream()
                    .filter(f -> f.endsWith(".java") && !f.contains("/target/")).toList();

            if (cs.noChanges()) {
                log.info("[{}] 无变化,跳过分析", projectName);
                return Map.of("project", projectName, "mode", "skipped",
                        "message", "代码无变化(工作区干净且 commit 未变)");
            }
            if (reanalyze.size() + deleted.size() > FULL_FALLBACK_FILES) {
                log.info("[{}] 变更文件 {} 个超过阈值 {},回退全量分析",
                        projectName, reanalyze.size() + deleted.size(), FULL_FALLBACK_FILES);
            } else {
                return incremental(projectName, abs, cs, reanalyze, deleted,
                        prevCommit, prevFileCount, start);
            }
        }
        return full(projectName, abs, head, start);
    }

    /** F3.3: 增量路径——快照跨文件边 -> 删旧 -> 增量解析 -> 写入 -> 恢复边 -> 更新状态 */
    private Map<String, Object> incremental(String projectName, Path abs, GitInfoService.ChangeSet cs,
                                             List<String> reanalyze, List<String> deleted,
                                             String prevCommit, Integer prevFileCount, long start) throws IOException {
        List<String> all = new ArrayList<>(reanalyze);
        all.addAll(deleted);

        // 1. 种子索引(未变更部分)与跨文件入边快照,均在删除前完成
        List<TypeInfo> typeSeed = store.loadTypeSeed(projectName, all);
        List<MethodInfo> methodSeed = store.loadMethodSeed(projectName, all);
        Map<String, TypeInfo> typeMap = new LinkedHashMap<>();
        Map<String, String> simpleNames = new HashMap<>();
        for (TypeInfo t : typeSeed) {
            typeMap.put(t.qualifiedName(), t);
            simpleNames.putIfAbsent(t.simpleName(), t.qualifiedName());
        }
        Map<String, List<MethodInfo>> methodsByType = methodSeed.stream()
                .collect(Collectors.groupingBy(MethodInfo::ownerType, HashMap::new, Collectors.toCollection(ArrayList::new)));
        var seed = new CodeAnalyzer.GraphSeed(typeMap, methodsByType, simpleNames);

        GraphStoreService.CrossEdges captured = store.captureCrossEdges(projectName, all);

        // 2. 删除变更文件的旧数据
        store.deleteByFilePaths(projectName, all);

        // 3. 增量解析并落地
        AnalysisResult delta = analyzer.analyzeDelta(projectName, abs, reanalyze, seed);
        store.applyDelta(delta);
        store.restoreCrossEdges(projectName, captured);

        // 4. 更新分析状态:有未提交改动时不推进 commit 指针(否则下次 diff 基线错乱)
        String commitToStore = cs.dirty() ? prevCommit : cs.head();
        int fileCount = Math.max(0, (prevFileCount == null ? 0 : prevFileCount) + cs.addedCount() - cs.deletedCount());
        store.updateAnalysisState(projectName, commitToStore, fileCount);

        long took = System.currentTimeMillis() - start;
        log.info("[{}] 增量分析完成: {} 个文件重析 / {} 个删除, 耗时 {}ms",
                projectName, reanalyze.size(), deleted.size(), took);
        Map<String, Object> stats = new HashMap<>();
        stats.put("project", projectName);
        stats.put("mode", "incremental");
        stats.put("changedFiles", reanalyze.size());
        stats.put("deletedFiles", deleted.size());
        stats.put("types", delta.types().size());
        stats.put("methods", delta.methods().size());
        stats.put("typeRelations", delta.typeEdges().size());
        stats.put("callRelations", delta.callEdges().size());
        stats.put("tookMs", took);
        return stats;
    }

    private Map<String, Object> full(String projectName, Path abs, String head, long start)
            throws IOException {
        AnalysisResult result = analyzer.analyze(projectName, abs);
        Map<String, Object> stats = store.replaceProject(result, abs.toString(), head);
        stats.put("tookMs", System.currentTimeMillis() - start);
        return stats;
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
