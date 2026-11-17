package com.example.codeknowledgegraph.controller;

import com.example.codeknowledgegraph.parser.CodeAnalyzer;
import com.example.codeknowledgegraph.parser.model.AnalysisResult;
import com.example.codeknowledgegraph.service.GitCloneService;
import com.example.codeknowledgegraph.service.GraphQueryService;
import com.example.codeknowledgegraph.service.GraphStoreService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final CodeAnalyzer analyzer;
    private final GraphStoreService store;
    private final GraphQueryService query;
    private final GitCloneService gitClone;

    public ApiController(CodeAnalyzer analyzer, GraphStoreService store,
                         GraphQueryService query, GitCloneService gitClone) {
        this.analyzer = analyzer;
        this.store = store;
        this.query = query;
        this.gitClone = gitClone;
    }

    // ==================== 项目管理 ====================

    public record AnalyzeRequest(String name, String path, String gitUrl, String branch) {}

    @PostMapping("/projects")
    public ResponseEntity<?> analyzeProject(@RequestBody AnalyzeRequest request) throws Exception {
        // ---- Git URL 模式:克隆到临时目录,分析完清理 ----
        if (request.gitUrl() != null && !request.gitUrl().isBlank()) {
            GitCloneService.ClonedRepo cloned;
            try {
                cloned = gitClone.clone(request.gitUrl(), request.branch());
            } catch (IllegalArgumentException | IllegalStateException e) {
                return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
            }
            try {
                String name = (request.name() == null || request.name().isBlank())
                        ? cloned.defaultName() : request.name().trim();
                AnalysisResult result = analyzer.analyze(name, cloned.directory());
                Map<String, Object> stats = store.replaceProject(result, cloned.source(), cloned.commit());
                return ResponseEntity.ok(stats);
            } finally {
                // 删除整个临时目录(包含 .git)
                gitClone.deleteRecursively(cloned.directory().getParent());
            }
        }

        // ---- 本地路径模式 ----
        if (request.path() == null || request.path().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请提供 gitUrl 或 path"));
        }
        Path root = Path.of(request.path().trim());
        if (!Files.isDirectory(root)) {
            return ResponseEntity.badRequest().body(Map.of("error", "目录不存在: " + request.path()));
        }
        String name = (request.name() == null || request.name().isBlank())
                ? root.getFileName().toString() : request.name().trim();

        AnalysisResult result = analyzer.analyze(name, root);
        Map<String, Object> stats = store.replaceProject(result, root.toAbsolutePath().toString(), null);
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/projects")
    public List<Map<String, Object>> projects() {
        return List.copyOf(store.listProjects());
    }

    @GetMapping("/projects/{name}/stats")
    public Map<String, Object> stats(@PathVariable String name) {
        return query.projectStats(name);
    }

    // ==================== 图谱查询 ====================

    @GetMapping("/graph/overview")
    public Map<String, Object> overview(@RequestParam String project,
                                         @RequestParam(required = false) List<String> packages) {
        return query.overview(project, packages);
    }

    @GetMapping("/graph/focus")
    public Map<String, Object> focus(@RequestParam String project,
                                      @RequestParam String qn,
                                      @RequestParam(defaultValue = "2") int depth) {
        return query.focus(project, qn, depth);
    }

    @GetMapping("/packages")
    public List<Map<String, Object>> packages(@RequestParam String project) {
        return query.packages(project);
    }

    @GetMapping("/types/{qualifiedName}")
    public Map<String, Object> typeDetail(@PathVariable String qualifiedName,
                                           @RequestParam String project) {
        return query.typeDetail(project, qualifiedName);
    }

    @GetMapping("/impact/{qualifiedName}")
    public List<Map<String, Object>> impact(@PathVariable String qualifiedName,
                                             @RequestParam String project,
                                             @RequestParam(defaultValue = "5") int depth) {
        return query.impactAnalysis(project, qualifiedName, depth);
    }

    @GetMapping("/quality/cycles")
    public List<Map<String, Object>> cycles(@RequestParam String project) {
        return query.findCycles(project);
    }

    @GetMapping("/search")
    public List<Map<String, Object>> search(@RequestParam String project, @RequestParam String q) {
        return query.search(project, q);
    }

    @GetMapping("/methods/{id}/callers")
    public List<Map<String, Object>> callers(@PathVariable String id, @RequestParam String project) {
        return query.methodCallers(project, id);
    }

    @GetMapping("/methods/{id}/callees")
    public List<Map<String, Object>> callees(@PathVariable String id, @RequestParam String project) {
        return query.methodCallees(project, id);
    }
}
