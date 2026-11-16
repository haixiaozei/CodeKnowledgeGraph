package com.example.codeknowledgegraph.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Git 仓库克隆服务:浅克隆到临时目录,供分析后清理。
 * 依赖本机 git 命令,因此自动复用用户已有的凭证配置(ssh key / credential helper)。
 */
@Service
public class GitCloneService {

    private static final Logger log = LoggerFactory.getLogger(GitCloneService.class);
    private static final Pattern URL_PATTERN =
            Pattern.compile("^(https?://|git://|ssh://|git@[-\\w.]+[.:]\\S+|file://).+");
    private static final long CLONE_TIMEOUT_MINUTES = 10;

    public record ClonedRepo(Path directory, String source, String commit, String defaultName) {}

    /** 判断输入是 Git 地址还是本地路径 */
    public boolean isGitUrl(String input) {
        return input != null && !input.isBlank() && URL_PATTERN.matcher(input.trim()).matches();
    }

    public ClonedRepo clone(String url, String branch) throws IOException, InterruptedException {
        url = url.trim();
        if (!isGitUrl(url)) {
            throw new IllegalArgumentException("不支持的 Git 地址格式: " + url);
        }
        Path base = Files.createTempDirectory("ckg-git-");
        Path repo = base.resolve("repo");

        List<String> cmd = new ArrayList<>(List.of("git", "clone", "--depth", "1", "--quiet"));
        if (branch != null && !branch.isBlank()) {
            cmd.add("--branch");
            cmd.add(branch.trim());
        }
        cmd.add(url);
        cmd.add(repo.toString());

        log.info("开始克隆: {} (分支: {})", url, branch == null || branch.isBlank() ? "默认" : branch);
        long start = System.currentTimeMillis();
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(CLONE_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            deleteRecursively(base);
            throw new IllegalStateException("git clone 超时(" + CLONE_TIMEOUT_MINUTES + "分钟): " + url);
        }
        if (p.exitValue() != 0) {
            deleteRecursively(base);
            throw new IllegalStateException("git clone 失败: " + output.strip());
        }
        String commit = runGit(repo, "rev-parse", "--short", "HEAD");
        log.info("克隆完成: {} @{}, 耗时 {}ms", url, commit, System.currentTimeMillis() - start);

        String source = url + ((branch == null || branch.isBlank()) ? "" : "@" + branch.trim());
        return new ClonedRepo(repo, source, commit, repoNameOf(url));
    }

    /** 在仓库目录内执行 git 命令并返回第一行输出 */
    private String runGit(Path repoDir, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(repoDir.toFile())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor(1, TimeUnit.MINUTES);
        return out.strip().lines().findFirst().orElse("");
    }

    /** 从 URL 提取仓库名: .../foo.git -> foo */
    private String repoNameOf(String url) {
        String s = url.replaceAll("[/\\\\]$", "");
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf(':'));
        String name = slash >= 0 ? s.substring(slash + 1) : s;
        return name.endsWith(".git") ? name.substring(0, name.length() - 4) : name;
    }

    /** 递归删除目录(用于清理临时克隆) */
    public void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            log.warn("清理临时目录失败: {}", dir, e);
        }
    }
}
