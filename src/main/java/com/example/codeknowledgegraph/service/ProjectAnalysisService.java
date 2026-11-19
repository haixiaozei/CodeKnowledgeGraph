package com.example.codeknowledgegraph.service;

import com.example.codeknowledgegraph.parser.CodeAnalyzer;
import com.example.codeknowledgegraph.parser.model.AnalysisResult;
import com.example.codeknowledgegraph.parser.model.AnalysisResult.Edge;
import com.example.codeknowledgegraph.parser.model.AnalysisResult.FieldInfo;
import com.example.codeknowledgegraph.parser.model.AnalysisResult.MethodInfo;
import com.example.codeknowledgegraph.parser.model.AnalysisResult.TypeInfo;
import com.example.codeknowledgegraph.parser.support.TreeSitterProjectAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final TreeSitterProjectAnalyzer treeSitter;

    public ProjectAnalysisService(CodeAnalyzer analyzer, GraphStoreService store, GitInfoService git,
                                   TreeSitterProjectAnalyzer treeSitter) {
        this.analyzer = analyzer;
        this.store = store;
        this.git = git;
        this.treeSitter = treeSitter;
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
                    .filter(f -> (f.endsWith(".java") || f.endsWith(".py") || f.endsWith(".ts")
                            || f.endsWith(".tsx") || f.endsWith(".go")) && !f.contains("/target/")).toList();
            List<String> deleted = cs.deleted().stream()
                    .filter(f -> (f.endsWith(".java") || f.endsWith(".py") || f.endsWith(".ts")
                            || f.endsWith(".tsx") || f.endsWith(".go")) && !f.contains("/target/")).toList();

            // 图谱只关心 .java:原始变更集非空但过滤后为空(如仅新增了配置文件)同样视为无变化
            if (cs.noChanges() || (reanalyze.isEmpty() && deleted.isEmpty())) {
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
        long t0 = System.currentTimeMillis();

        // 1. 种子索引(未变更部分)与跨文件入边快照,均在删除前完成
        List<TypeInfo> typeSeed = store.loadTypeSeed(projectName, all);
        List<MethodInfo> methodSeed = store.loadMethodSeed(projectName, all);
        long tSeed = System.currentTimeMillis();
        log.info("[增量计时] 种子加载: {}ms ({} 类型 / {} 方法)", tSeed - t0, typeSeed.size(), methodSeed.size());
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
        long tCapture = System.currentTimeMillis();
        log.info("[增量计时] 跨文件边快照: {}ms (类型边 {} / 调用边 {})",
                tCapture - tSeed, captured.typeEdges().size(), captured.callEdges().size());

        // 2. 删除变更文件的旧数据
        store.deleteByFilePaths(projectName, all);
        long tDelete = System.currentTimeMillis();

        // 3. 增量解析并落地: Java(tree-sitter 之外)与 tree-sitter 语言分别解析,合并为一个增量
        AnalysisResult delta = reanalyze.stream().anyMatch(f -> f.endsWith(".java"))
                ? analyzer.analyzeDelta(projectName, abs,
                        reanalyze.stream().filter(f -> f.endsWith(".java")).toList(), seed)
                : new AnalysisResult(projectName, abs.toString(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of(), 0);
        long tDelta = System.currentTimeMillis();
        log.info("[增量计时] Java 增量解析总耗时: {}ms", tDelta - tDelete);
        List<Path> treeSitterFiles = reanalyze.stream()
                .filter(f -> !f.endsWith(".java"))
                .map(abs::resolve).toList();
        TreeSitterProjectAnalyzer.Result tsDelta = treeSitterFiles.isEmpty() ? null
                : treeSitter.analyzeIncremental(abs, treeSitterFiles, typeSeed, methodSeed);
        AnalysisResult combined = tsDelta == null ? delta
                : merge(delta, tsDelta, projectName, abs);
        store.applyDelta(combined);
        store.restoreCrossEdges(projectName, captured);
        log.info("[增量计时] 写入+边恢复: {}ms", System.currentTimeMillis() - tDelta);

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
        AnalysisResult java = analyzer.analyze(projectName, abs);
        TreeSitterProjectAnalyzer.Result py = treeSitterResult(abs, List.of(), List.of(), List.of());
        AnalysisResult result = merge(java, py, projectName, abs);
        Map<String, Object> stats = store.replaceProject(result, abs.toString(), head);
        stats.put("tookMs", System.currentTimeMillis() - start);
        return stats;
    }

    /** F5.1: 解析项目内全部 tree-sitter 支持的文件(无则返回空) */
    private TreeSitterProjectAnalyzer.Result treeSitterResult(Path abs,
                                                              List<String> changedRelPaths,
                                                              List<TypeInfo> seedTypes,
                                                              List<MethodInfo> seedMethods)
            throws IOException {
        List<Path> pyFiles = treeSitter.discoverFiles(abs).stream()
                .filter(p -> changedRelPaths.isEmpty()
                        || changedRelPaths.contains(abs.relativize(p).toString().replace('\\', '/')))
                .toList();
        if (pyFiles.isEmpty()) return new TreeSitterProjectAnalyzer.Result(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0);
        return changedRelPaths.isEmpty()
                ? treeSitter.analyzeProject(abs, pyFiles)
                : treeSitter.analyzeIncremental(abs, pyFiles, seedTypes, seedMethods);
    }

    /** F5.1: 合并 Java 与 Python 的分析结果 */
    private AnalysisResult merge(AnalysisResult java, TreeSitterProjectAnalyzer.Result py,
                                  String projectName, Path root) {
        List<TypeInfo> types = new ArrayList<>(java.types());
        List<MethodInfo> methods = new ArrayList<>(java.methods());
        List<FieldInfo> fields = new ArrayList<>(java.fields());
        List<Edge> typeEdges = new ArrayList<>(java.typeEdges());
        List<Edge> callEdges = new ArrayList<>(java.callEdges());
        Set<String> packages = new LinkedHashSet<>(java.packages());
        types.addAll(py.types());
        methods.addAll(py.methods());
        fields.addAll(py.fields());
        typeEdges.addAll(py.typeEdges());
        callEdges.addAll(py.callEdges());
        packages.addAll(py.packages());
        return new AnalysisResult(projectName, root.toString(), new ArrayList<>(packages),
                types, methods, fields, typeEdges, callEdges,
                java.fileCount() + py.fileCount());
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
