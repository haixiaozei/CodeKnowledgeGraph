package com.example.codeknowledgegraph.service;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 图谱查询:概览、类型详情、影响分析、循环依赖、搜索、调用链、质量指标。
 */
@Service
public class GraphQueryService {

    private final Neo4jClient neo4j;
    private final GitInfoService git;

    public GraphQueryService(Neo4jClient neo4j, GitInfoService git) {
        this.neo4j = neo4j;
        this.git = git;
    }

    /** 可视化概览:类型节点 + 类型间关系,可按包过滤 */
    public Map<String, Object> overview(String project) {
        return overview(project, List.of());
    }

    public Map<String, Object> overview(String project, List<String> packages) {
        boolean hasFilter = packages != null && !packages.isEmpty();
        String nodeQuery = """
                MATCH (t:Type {project: $p})
                """ + (hasFilter ? "WHERE t.packageName IN $pkgs " : "") + """
                RETURN t.qualifiedName AS id, t.simpleName AS name, t.kind AS kind,
                       t.packageName AS pkg, t.lineCount AS lines
                """;
        String edgeQuery = """
                MATCH (a:Type {project: $p})-[r:EXTENDS|IMPLEMENTS|DEPENDS_ON]->(b:Type {project: $p})
                """ + (hasFilter ? "WHERE a.packageName IN $pkgs AND b.packageName IN $pkgs " : "") + """
                RETURN a.qualifiedName AS from, b.qualifiedName AS to, type(r) AS kind
                """;

        var nodeBind = neo4j.query(nodeQuery).bind(project).to("p");
        if (hasFilter) nodeBind = nodeBind.bind(packages).to("pkgs");
        Collection<Map<String, Object>> nodes = nodeBind.fetch().all();

        var edgeBind = neo4j.query(edgeQuery).bind(project).to("p");
        if (hasFilter) edgeBind = edgeBind.bind(packages).to("pkgs");
        Collection<Map<String, Object>> edges = edgeBind.fetch().all();

        return Map.of("nodes", List.copyOf(nodes), "edges", List.copyOf(edges));
    }

    /** 局部视图:以某类型为中心,depth 层关系内的邻居子图 */
    public Map<String, Object> focus(String project, String qn, int depth) {
        int d = Math.max(1, Math.min(depth, 3));
        Collection<Map<String, Object>> nodes = neo4j.query("""
                MATCH path = (c:Type {project: $p, qualifiedName: $qn})-[:DEPENDS_ON|EXTENDS|IMPLEMENTS*0..%d]-(x:Type)
                WHERE x.project = $p
                RETURN DISTINCT x.qualifiedName AS id, x.simpleName AS name, x.kind AS kind,
                       x.packageName AS pkg, x.lineCount AS lines
                """.formatted(d)).bindAll(Map.of("p", project, "qn", qn)).fetch().all();

        List<String> ids = nodes.stream().map(n -> (String) n.get("id")).toList();
        if (ids.isEmpty()) return Map.of("nodes", List.of(), "edges", List.of());

        Collection<Map<String, Object>> edges = neo4j.query("""
                MATCH (a:Type {project: $p})-[r:EXTENDS|IMPLEMENTS|DEPENDS_ON]->(b:Type {project: $p})
                WHERE a.qualifiedName IN $ids AND b.qualifiedName IN $ids
                RETURN a.qualifiedName AS from, b.qualifiedName AS to, type(r) AS kind
                """).bindAll(Map.of("p", project, "ids", ids)).fetch().all();

        return Map.of("nodes", List.copyOf(nodes), "edges", List.copyOf(edges));
    }

    /** 项目下所有包及类型数量 */
    public List<Map<String, Object>> packages(String project) {
        return List.copyOf(neo4j.query("""
                MATCH (p:Package {project: $p})-[:CONTAINS]->(t:Type)
                RETURN p.name AS name, count(t) AS typeCount
                ORDER BY name
                """).bind(project).to("p").fetch().all());
    }

    /** 类型详情:方法、字段、依赖、被依赖、直接调用者方法数 */
    public Map<String, Object> typeDetail(String project, String qn) {
        var type = neo4j.query("""
                MATCH (t:Type {project: $p, qualifiedName: $qn})
                RETURN t.qualifiedName AS qualifiedName, t.simpleName AS name, t.kind AS kind,
                       t.visibility AS visibility, t.isAbstract AS isAbstract, t.lineCount AS lineCount,
                       t.packageName AS pkg
                """).bindAll(Map.of("p", project, "qn", qn)).fetch().first();

        if (type.isEmpty()) return Map.of("error", "Type not found: " + qn);

        List<Map<String, Object>> methods = new ArrayList<>(neo4j.query("""
                MATCH (:Type {project: $p, qualifiedName: $qn})-[:HAS_METHOD]->(m:Method)
                OPTIONAL MATCH (caller:Method)-[:CALLS]->(m)
                RETURN m.id AS id, m.name AS name, m.signature AS signature, m.returnType AS returnType,
                       m.visibility AS visibility, m.isStatic AS isStatic, m.startLine AS startLine,
                       m.httpMethod AS httpMethod, m.httpPath AS httpPath,
                       m.annotations AS annotations, count(caller) AS callers
                ORDER BY m.name
                """).bindAll(Map.of("p", project, "qn", qn)).fetch().all());

        List<Map<String, Object>> fields = new ArrayList<>(neo4j.query("""
                MATCH (:Type {project: $p, qualifiedName: $qn})-[:HAS_FIELD]->(f:Field)
                RETURN f.id AS id, f.name AS name, f.type AS type, f.visibility AS visibility, f.isStatic AS isStatic
                ORDER BY f.name
                """).bindAll(Map.of("p", project, "qn", qn)).fetch().all());

        List<Map<String, Object>> dependsOn = new ArrayList<>(neo4j.query("""
                MATCH (a:Type {project: $p, qualifiedName: $qn})-[r]->(b:Type)
                RETURN b.qualifiedName AS name, type(r) AS kind
                """).bindAll(Map.of("p", project, "qn", qn)).fetch().all());

        List<Map<String, Object>> dependedBy = new ArrayList<>(neo4j.query("""
                MATCH (a:Type)-[r]->(b:Type {project: $p, qualifiedName: $qn})
                RETURN a.qualifiedName AS name, type(r) AS kind
                """).bindAll(Map.of("p", project, "qn", qn)).fetch().all());

        return Map.of("type", type.get(), "methods", methods, "fields", fields,
                "dependsOn", dependsOn, "dependedBy", dependedBy);
    }

    /** 影响分析:反向传递依赖(谁会被这个类型的改动波及) */
    public List<Map<String, Object>> impactAnalysis(String project, String qn, int maxDepth) {
        int depth = Math.max(1, Math.min(maxDepth <= 0 ? 5 : maxDepth, 10));
        String cypher = """
                MATCH path = (d:Type)-[:DEPENDS_ON|EXTENDS|IMPLEMENTS*1..%d]->(t:Type {project: $p, qualifiedName: $qn})
                WHERE d.project = $p
                RETURN d.qualifiedName AS name, min(length(path)) AS depth
                ORDER BY depth, name
                """.formatted(depth);
        return List.copyOf(neo4j.query(cypher)
                .bindAll(Map.of("p", project, "qn", qn)).fetch().all());
    }

    /** 循环依赖检测 */
    public List<Map<String, Object>> findCycles(String project) {
        var raw = neo4j.query("""
                MATCH path = (a:Type {project: $p})-[:DEPENDS_ON*1..6]->(a)
                RETURN [n IN nodes(path) | n.qualifiedName] AS cycle
                LIMIT 200
                """).bind(project).to("p").fetch().all();

        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : raw) {
            @SuppressWarnings("unchecked")
            List<String> cycle = (List<String>) row.get("cycle");
            // 旋转去重:环的起点不影响其唯一性
            TreeSet<String> key = new TreeSet<>(cycle);
            if (seen.add(String.join("|", key))) {
                result.add(Map.of("cycle", cycle.subList(0, cycle.size() - 1), "length", cycle.size() - 1));
            }
        }
        return result;
    }

    /** 搜索类型与方法 */
    public List<Map<String, Object>> search(String project, String q) {
        String needle = q == null ? "" : q.trim().toLowerCase();
        if (needle.isEmpty()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>(neo4j.query("""
                MATCH (t:Type {project: $p})
                WHERE toLower(t.qualifiedName) CONTAINS $q
                RETURN 'type' AS kind, t.qualifiedName AS name, t.kind AS detail
                LIMIT 15
                """).bindAll(Map.of("p", project, "q", needle)).fetch().all());

        if (result.size() < 15) {
            result.addAll(neo4j.query("""
                    MATCH (t:Type {project: $p})-[:HAS_METHOD]->(m:Method)
                    WHERE toLower(m.name) CONTAINS $q
                    RETURN 'method' AS kind, t.qualifiedName + '.' + m.signature AS name,
                           m.id AS methodId
                    LIMIT 15
                    """).bindAll(Map.of("p", project, "q", needle)).fetch().all());
        }
        return result;
    }

    /** 方法的调用者(谁调用了它) */
    public List<Map<String, Object>> methodCallers(String project, String methodId) {
        return List.copyOf(neo4j.query("""
                MATCH (caller:Method {project: $p})-[:CALLS]->(m:Method {project: $p, id: $id})
                MATCH (owner:Type {project: $p})-[:HAS_METHOD]->(caller)
                RETURN caller.id AS id, caller.signature AS signature, owner.qualifiedName AS ownerType,
                       owner.simpleName AS ownerSimpleName
                """).bindAll(Map.of("p", project, "id", methodId)).fetch().all());
    }

    /** 方法的调用链(它调用了谁) */
    public List<Map<String, Object>> methodCallees(String project, String methodId) {
        return List.copyOf(neo4j.query("""
                MATCH (m:Method {project: $p, id: $id})-[:CALLS]->(callee:Method)
                MATCH (owner:Type {project: $p})-[:HAS_METHOD]->(callee)
                RETURN callee.id AS id, callee.signature AS signature, owner.qualifiedName AS ownerType,
                       owner.simpleName AS ownerSimpleName
                """).bindAll(Map.of("p", project, "id", methodId)).fetch().all());
    }

    /** 项目统计概览 */
    public Map<String, Object> projectStats(String project) {
        var counts = neo4j.query("""
                MATCH (x:Project {name: $p})
                OPTIONAL MATCH (t:Type {project: $p})
                WITH x, count(t) AS types
                OPTIONAL MATCH (m:Method {project: $p})
                WITH x, types, count(m) AS methods
                OPTIONAL MATCH (:Method {project: $p})-[c:CALLS]->(:Method {project: $p})
                RETURN x.name AS project, types, methods, count(c) AS calls
                """).bind(project).to("p").fetch().first();
        return counts.orElse(Map.of("project", project));
    }

    /** 源码片段读取的行数上限,防止巨型类型撑爆响应 */
    private static final int MAX_SOURCE_LINES = 2000;
    /**
     * F1.3: 按类型或方法定位并读取源码文本。
     * 仅支持本地路径模式分析的项目;Git URL 模式分析的临时目录已被清理。
     */
    public Map<String, Object> sourceSnippet(String project, String qn, String methodId) {
        boolean isMethod = methodId != null && !methodId.isBlank();
        // 1. 定位节点(方法优先)
        Map<String, Object> node;
        if (isMethod) {
            node = neo4j.query("""
                    MATCH (t:Type {project: $p, qualifiedName: $qn})-[:HAS_METHOD]->(m:Method {project: $p, id: $id})
                    RETURN m.id AS id, m.signature AS name, m.filePath AS filePath,
                           m.startLine AS startLine, m.endLine AS endLine
                    """).bindAll(Map.of("p", project, "qn", qn, "id", methodId))
                    .fetch().first().orElse(null);
        } else {
            node = neo4j.query("""
                    MATCH (t:Type {project: $p, qualifiedName: $qn})
                    RETURN t.qualifiedName AS id, t.simpleName AS name, t.filePath AS filePath,
                           t.startLine AS startLine, t.endLine AS endLine
                    """).bindAll(Map.of("p", project, "qn", qn))
                    .fetch().first().orElse(null);
        }
        if (node == null) return Map.of("error", "Not found: " + (isMethod ? methodId : qn));

        // 2. 项目根路径(仅本地路径模式可读取源码)
        String rootPath = neo4j.query("MATCH (x:Project {name: $p}) RETURN x.path AS path")
                .bind(project).to("p").fetch().first()
                .map(m -> String.valueOf(m.get("path"))).orElse("");
        if (rootPath.startsWith("file://")) rootPath = rootPath.substring("file://".length());
        if (rootPath.isBlank() || "null".equals(rootPath) || !Files.isDirectory(Path.of(rootPath))) {
            return Map.of("error", "源码不可用: 该项目按 Git URL 分析,临时目录已清理,请使用本地路径模式重新分析");
        }

        // 3. 校验文件路径(防目录穿越)
        Object filePathRaw = node.get("filePath");
        if (filePathRaw == null || String.valueOf(filePathRaw).isBlank()) {
            return Map.of("error", "该节点没有源码位置信息,请重新分析项目以升级数据");
        }
        Path file = Path.of(rootPath).resolve(String.valueOf(filePathRaw)).normalize();
        if (!file.startsWith(Path.of(rootPath).normalize()) || !Files.isRegularFile(file)) {
            return Map.of("error", "源码文件不存在: " + filePathRaw);
        }

        // 4. 读取行范围(1-based 含端点;老数据无行号时读全文件)
        try {
            List<String> allLines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int from = node.get("startLine") instanceof Number n ? n.intValue() : 1;
            int to = node.get("endLine") instanceof Number n ? n.intValue() : allLines.size();
            from = Math.max(1, Math.min(from, allLines.size()));
            to = Math.max(from, Math.min(to, allLines.size()));
            boolean truncated = false;
            if (to - from + 1 > MAX_SOURCE_LINES) { to = from + MAX_SOURCE_LINES - 1; truncated = true; }

            Map<String, Object> result = new HashMap<>();
            result.put("id", node.get("id"));
            result.put("name", node.get("name"));
            result.put("filePath", String.valueOf(filePathRaw));
            result.put("startLine", from);
            result.put("endLine", to);
            result.put("totalLines", allLines.size());
            result.put("truncated", truncated);
            result.put("source", String.join("\n", allLines.subList(from - 1, to)));
            return result;
        } catch (IOException e) {
            return Map.of("error", "读取源码失败: " + e.getMessage());
        }
    }

    /**
     * F1.4: 上下文包——理解一个类型所需的最小完整信息,供 AI 直接消费。
     * 组成: 类型元信息(含 Javadoc) + 源码(如可用) + 方法/字段 + 直接依赖(含签名摘要) + 被依赖 + 外部调用关系。
     */
    public Map<String, Object> contextPack(String project, String qn) {
        var type = neo4j.query("""
                MATCH (t:Type {project: $p, qualifiedName: $qn})
                RETURN t.qualifiedName AS qualifiedName, t.simpleName AS name, t.kind AS kind,
                       t.visibility AS visibility, t.isAbstract AS isAbstract, t.lineCount AS lineCount,
                       t.packageName AS pkg, t.javadoc AS javadoc, t.filePath AS filePath
                """).bindAll(Map.of("p", project, "qn", qn)).fetch().first();
        if (type.isEmpty()) return Map.of("error", "Type not found: " + qn);

        Map<String, Object> result = new HashMap<>(type.get());

        // 源码(Git URL 模式不可用时置 null,不报错——上下文包其余部分仍有价值)
        Map<String, Object> src = sourceSnippet(project, qn, null);
        result.put("source", src.containsKey("error") ? null : src);

        // 方法(含调用者计数与行号,便于跳转)
        result.put("methods", new ArrayList<>(neo4j.query("""
                MATCH (:Type {project: $p, qualifiedName: $qn})-[:HAS_METHOD]->(m:Method)
                OPTIONAL MATCH (caller:Method)-[:CALLS]->(m)
                RETURN m.id AS id, m.signature AS signature, m.visibility AS visibility,
                       m.isStatic AS isStatic, m.startLine AS startLine, m.javadoc AS javadoc,
                       count(caller) AS callers
                ORDER BY m.startLine
                """).bindAll(Map.of("p", project, "qn", qn)).fetch().all()));

        // 字段
        result.put("fields", new ArrayList<>(neo4j.query("""
                MATCH (:Type {project: $p, qualifiedName: $qn})-[:HAS_FIELD]->(f:Field)
                RETURN f.name AS name, f.type AS type, f.visibility AS visibility
                ORDER BY f.name
                """).bindAll(Map.of("p", project, "qn", qn)).fetch().all()));

        // 直接依赖:关系类型 + 对方 Javadoc + 对方方法签名摘要(最多 30 个)
        result.put("dependsOn", new ArrayList<>(neo4j.query("""
                MATCH (a:Type {project: $p, qualifiedName: $qn})-[r]->(b:Type {project: $p})
                OPTIONAL MATCH (b)-[:HAS_METHOD]->(m:Method)
                WITH b, type(r) AS kind, collect(DISTINCT m.signature)[..30] AS methodSignatures
                RETURN b.qualifiedName AS name, kind, b.javadoc AS javadoc, methodSignatures
                """).bindAll(Map.of("p", project, "qn", qn)).fetch().all()));

        // 被依赖(反向)
        result.put("dependedBy", new ArrayList<>(neo4j.query("""
                MATCH (a:Type {project: $p})-[r]->(b:Type {project: $p, qualifiedName: $qn})
                RETURN a.qualifiedName AS name, type(r) AS kind
                """).bindAll(Map.of("p", project, "qn", qn)).fetch().all()));

        // 外部调用者:谁调用了本类型的方法(最多 20 条)
        result.put("methodCallers", new ArrayList<>(neo4j.query("""
                MATCH (owner:Type {project: $p})-[:HAS_METHOD]->(caller:Method {project: $p})
                      -[:CALLS]->(m:Method {project: $p})<-[:HAS_METHOD]-(t:Type {project: $p, qualifiedName: $qn})
                WHERE owner.qualifiedName <> $qn
                RETURN owner.qualifiedName AS callerType, caller.signature AS callerSignature,
                       m.signature AS calleeSignature
                LIMIT 20
                """).bindAll(Map.of("p", project, "qn", qn)).fetch().all()));

        // 本类型方法的对外调用(最多 20 条)
        result.put("methodCallees", new ArrayList<>(neo4j.query("""
                MATCH (t:Type {project: $p, qualifiedName: $qn})-[:HAS_METHOD]->(m:Method {project: $p})
                      -[:CALLS]->(callee:Method {project: $p})<-[:HAS_METHOD]-(owner:Type {project: $p})
                WHERE owner.qualifiedName <> $qn
                RETURN m.signature AS callerSignature, owner.qualifiedName AS calleeType,
                       callee.signature AS calleeSignature
                LIMIT 20
                """).bindAll(Map.of("p", project, "qn", qn)).fetch().all()));

        return result;
    }

    /**
     * F4.1: 死代码检测——无调用者且非入口的方法。
     * 入口规则: 构造器 / main / REST 端点 / 接口声明 / 携带排除注解(默认 Override、Test 系列)。
     */
    public List<Map<String, Object>> deadCode(String project, List<String> excludeAnnotations) {
        List<String> excluded = (excludeAnnotations == null || excludeAnnotations.isEmpty())
                ? List.of("Override", "Test", "BeforeEach", "BeforeAll", "AfterEach", "AfterAll", "ParameterizedTest")
                : excludeAnnotations;
        return List.copyOf(neo4j.query("""
                MATCH (t:Type {project: $p})-[:HAS_METHOD]->(m:Method)
                WHERE NOT ( (:Method {project: $p})-[:CALLS]->(m) )
                  AND NOT m.isConstructor
                  AND m.name <> 'main'
                  AND (m.httpPath IS NULL OR m.httpPath = '')
                  AND t.kind <> 'interface'
                  AND (m.annotations IS NULL
                       OR NOT any(a IN m.annotations WHERE a IN $excluded))
                RETURN m.id AS id, m.signature AS signature, m.visibility AS visibility,
                       m.startLine AS startLine, t.qualifiedName AS ownerType,
                       t.simpleName AS ownerSimpleName, t.kind AS ownerKind
                ORDER BY ownerType, m.name
                """).bindAll(Map.of("p", project, "excluded", excluded)).fetch().all());
    }

    /** F4.2: REST 端点索引——按路径片段查找接口实现,q 为空返回全部端点 */
    public List<Map<String, Object>> restEndpoints(String project, String pathSubstring) {
        String needle = pathSubstring == null ? "" : pathSubstring.trim();
        return List.copyOf(neo4j.query("""
                MATCH (t:Type {project: $p})-[:HAS_METHOD]->(m:Method)
                WHERE m.httpPath IS NOT NULL AND m.httpPath <> ''
                  AND ($q = '' OR toLower(m.httpPath) CONTAINS toLower($q))
                RETURN m.httpMethod AS httpMethod, m.httpPath AS httpPath,
                       t.qualifiedName AS ownerType, t.simpleName AS ownerSimpleName,
                       m.signature AS signature, m.id AS methodId, m.filePath AS filePath,
                       m.startLine AS startLine
                ORDER BY m.httpPath, m.httpMethod
                """).bindAll(Map.of("p", project, "q", needle)).fetch().all());
    }

    /** F4.3: 耦合度指标——扇入/扇出 TopN 与上帝类候选 */
    public Map<String, Object> coupling(String project) {
        Collection<Map<String, Object>> fanIn = neo4j.query("""
                MATCH (a:Type {project: $p})-[r]->(b:Type {project: $p})
                RETURN b.qualifiedName AS name, b.simpleName AS simpleName,
                       count(r) AS fanIn, b.lineCount AS lineCount
                ORDER BY fanIn DESC LIMIT 15
                """).bind(project).to("p").fetch().all();
        Collection<Map<String, Object>> fanOut = neo4j.query("""
                MATCH (a:Type {project: $p})-[r]->(b:Type {project: $p})
                RETURN a.qualifiedName AS name, a.simpleName AS simpleName,
                       count(r) AS fanOut, a.lineCount AS lineCount
                ORDER BY fanOut DESC LIMIT 15
                """).bind(project).to("p").fetch().all();
        // 上帝类候选: 方法多或行数大(改动风险与理解成本高)
        Collection<Map<String, Object>> godClasses = neo4j.query("""
                MATCH (t:Type {project: $p})-[:HAS_METHOD]->(m:Method)
                WITH t, count(m) AS methodCount
                WHERE methodCount >= 30 OR t.lineCount >= 500
                RETURN t.qualifiedName AS name, t.simpleName AS simpleName,
                       t.lineCount AS lineCount, methodCount
                ORDER BY methodCount DESC LIMIT 15
                """).bind(project).to("p").fetch().all();
        return Map.of(
                "mostDepended", List.copyOf(fanIn),
                "mostDependent", List.copyOf(fanOut),
                "godClasses", List.copyOf(godClasses));
    }

    /** F4.4: Git 热点——近期变更最频繁的 Java 文件(仅本地路径模式) */
    public Map<String, Object> hotspots(String project, int limit) {
        String rootPath = neo4j.query("MATCH (x:Project {name: $p}) RETURN x.path AS path")
                .bind(project).to("p").fetch().first()
                .map(m -> String.valueOf(m.get("path"))).orElse("");
        if (rootPath.startsWith("file://")) rootPath = rootPath.substring("file://".length());
        if (rootPath.isBlank() || "null".equals(rootPath) || !Files.isDirectory(Path.of(rootPath))) {
            return Map.of("error", "热点分析仅支持本地路径模式的项目(Git 模式临时目录已清理)");
        }
        return git.gitHotspots(Path.of(rootPath), limit <= 0 ? 20 : Math.min(limit, 100));
    }
}
