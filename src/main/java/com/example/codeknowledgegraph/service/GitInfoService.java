package com.example.codeknowledgegraph.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * F3.2: 本地 git 仓库信息查询,用于增量分析的变更检测。
 * 与 GitCloneService(克隆)职责分离;依赖本机 git 命令。
 */
@Service
public class GitInfoService {

    private static final Logger log = LoggerFactory.getLogger(GitInfoService.class);

    /** 变更集:reanalyze=需重新解析的文件(A/M/R新/未跟踪),deleted=已消失的文件(D/R旧) */
    public record ChangeSet(List<String> reanalyze, List<String> deleted,
                            int addedCount, int deletedCount, boolean dirty, String head) {
        public boolean noChanges() {
            return reanalyze.isEmpty() && deleted.isEmpty();
        }
    }

    /** 本地仓库 HEAD 短 commit;非 git 目录或命令失败返回 null(F3.4 降级依据) */
    public String headCommit(Path root) {
        if (!Files.exists(root.resolve(".git"))) return null;
        try {
            String out = runGit(root, "rev-parse", "--short", "HEAD");
            return out.isBlank() ? null : out;
        } catch (Exception e) {
            log.warn("获取 HEAD 失败: {}", root, e);
            return null;
        }
    }

    /**
     * 计算自 fromCommit 以来的变更文件(相对仓库根路径)。
     * 覆盖两种场景:
     * - 工作区有未提交改动: 与 HEAD 比对 + 未跟踪文件(此时不应推进 commit 指针)
     * - 工作区干净: 与 fromCommit..HEAD 比对
     */
    public ChangeSet changeSince(Path root, String fromCommit) throws IOException, InterruptedException {
        String head = headCommit(root);
        String dirtyOut = runGit(root, "status", "--porcelain");
        boolean dirty = !dirtyOut.isBlank();

        Set<String> reanalyze = new LinkedHashSet<>();
        Set<String> deleted = new LinkedHashSet<>();
        int added = 0, removed = 0;

        // 已跟踪文件的变更(含暂存区)
        String diff = runGit(root, "diff", "--name-status", dirty ? "HEAD" : fromCommit);
        for (String line : diff.split("\n")) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\t");
            switch (parts[0].substring(0, 1)) {
                case "A", "M", "T", "C" -> {
                    reanalyze.add(parts[1]);
                    if (!parts[0].startsWith("M")) added++;
                }
                case "D" -> {
                    deleted.add(parts[1]);
                    removed++;
                }
                case "R" -> { // rename: 旧路径删除 + 新路径重析(净文件数不变)
                    deleted.add(parts[1]);
                    reanalyze.add(parts[2]);
                }
                default -> { /* 未知状态,保守重析 */ reanalyze.add(parts[1]); }
            }
        }
        // 未跟踪的新文件
        if (dirty) {
            String untracked = runGit(root, "ls-files", "--others", "--exclude-standard");
            for (String line : untracked.split("\n")) {
                if (!line.isBlank()) {
                    reanalyze.add(line);
                    added++;
                }
            }
        }
        return new ChangeSet(List.copyOf(reanalyze), List.copyOf(deleted), added, removed, dirty, head);
    }

    /** 在仓库目录内执行 git 命令,返回完整 stdout(失败返回空串,由调用方决定降级) */
    private String runGit(Path repoDir, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(repoDir.toFile())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor(1, TimeUnit.MINUTES);
        return p.exitValue() == 0 ? out.strip() : "";
    }

    /**
     * F4.4: Git 热点——最近 200 个 commit 中变更最频繁的 Java 文件。
     * 高频变更 = 高风险区域;浅克隆仓库只有 1 个 commit,信号弱但不报错。
     */
    public Map<String, Object> gitHotspots(Path root, int limit) {
        if (!Files.exists(root.resolve(".git"))) {
            return Map.of("error", "该目录不是 git 仓库,无法统计变更热点");
        }
        try {
            String log = runGit(root, "log", "-n", "200", "--name-only", "--pretty=format:");
            Map<String, Integer> counts = new java.util.HashMap<>();
            int commitCount = 0;
            for (String line : log.split("\n")) {
                String t = line.strip();
                if (t.isEmpty()) { commitCount++; continue; }
                if (t.endsWith(".java")) counts.merge(t, 1, Integer::sum);
            }
            List<Map<String, Object>> top = counts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(limit)
                    .map(e -> Map.<String, Object>of("file", e.getKey(), "changes", e.getValue()))
                    .toList();
            return Map.of("commits", commitCount, "changedFiles", counts.size(), "hotspots", top);
        } catch (Exception e) {
            return Map.of("error", "git 统计失败: " + e.getMessage());
        }
    }
}
