package com.example.codeknowledgegraph.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * F2.3: 后台分析任务管理。
 * 大仓库分析是分钟级操作,超出 MCP 工具调用的合理等待时间,改为:
 * analyze 提交任务立即返回 jobId -> 后台线程执行 -> 通过 get_analysis_status 轮询结果。
 * 单线程串行执行,避免多个大仓库分析互相拖垮 Neo4j 写入。
 * F3.5: 支持强制全量(forceFull)开关,由分析编排器决定全量/增量。
 */
@Service
public class AnalysisJobService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisJobService.class);

    private final ProjectAnalysisService projectAnalysis;
    /** 单线程串行:同一时间只跑一个分析任务 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "analysis-job");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, JobState> jobs = new ConcurrentHashMap<>();

    /** 任务状态快照:PENDING -> RUNNING -> DONE / FAILED */
    public record JobState(String jobId, String name, String path, boolean forceFull,
                           String status, String submittedAt, Map<String, Object> result, String error) {}

    public AnalysisJobService(ProjectAnalysisService projectAnalysis) {
        this.projectAnalysis = projectAnalysis;
    }

    /** 提交本地路径分析任务,校验通过后立即返回 jobId */
    public Map<String, Object> submit(String name, String path, boolean forceFull) {
        if (path == null || path.isBlank()) {
            return Map.of("error", "请提供本地目录绝对路径");
        }
        Path root = Path.of(path.trim());
        if (!Files.isDirectory(root)) {
            return Map.of("error", "目录不存在: " + path);
        }
        String projectName = (name == null || name.isBlank()) ? root.getFileName().toString() : name.trim();
        String jobId = UUID.randomUUID().toString().substring(0, 8);
        JobState job = new JobState(jobId, projectName, root.toAbsolutePath().toString(), forceFull,
                "PENDING", Instant.now().toString(), null, null);
        jobs.put(jobId, job);
        executor.submit(() -> runJob(jobId, job));
        log.info("提交分析任务 {}: {} ({})", jobId, projectName, job.path());
        return Map.of("jobId", jobId, "project", projectName, "status", "PENDING");
    }

    /** 查询任务状态;DONE 时附带分析统计,FAILED 时附带错误信息 */
    public Map<String, Object> status(String jobId) {
        JobState j = jobs.get(jobId);
        if (j == null) return Map.of("error", "任务不存在: " + jobId);
        return Map.of(
                "jobId", j.jobId(),
                "project", j.name(),
                "status", j.status(),
                "submittedAt", j.submittedAt(),
                "result", j.result() == null ? Map.of() : j.result(),
                "error", j.error() == null ? "" : j.error());
    }

    private void runJob(String jobId, JobState job) {
        jobs.put(jobId, new JobState(job.jobId(), job.name(), job.path(), job.forceFull(), "RUNNING",
                job.submittedAt(), null, null));
        try {
            Map<String, Object> stats = projectAnalysis.analyze(job.name(), Path.of(job.path()), job.forceFull());
            jobs.put(jobId, new JobState(job.jobId(), job.name(), job.path(), job.forceFull(), "DONE",
                    job.submittedAt(), stats, null));
            log.info("分析任务 {} 完成: {}", jobId, stats);
        } catch (Exception e) {
            jobs.put(jobId, new JobState(job.jobId(), job.name(), job.path(), job.forceFull(), "FAILED",
                    job.submittedAt(), null, e.getMessage()));
            log.error("分析任务 {} 失败", jobId, e);
        }
    }
}
