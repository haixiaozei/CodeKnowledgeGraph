package com.example.codeknowledgegraph.service;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 图谱查询:概览、类型详情、影响分析、循环依赖、搜索、调用链。
 */
@Service
public class GraphQueryService {

    private final Neo4jClient neo4j;

    public GraphQueryService(Neo4jClient neo4j) {
        this.neo4j = neo4j;
    }

    /** 可视化概览:类型节点 + 类型间关系 */
    public Map<String, Object> overview(String project) {
        Collection<Map<String, Object>> nodes = neo4j.query("""
                MATCH (t:Type {project: $p})
                RETURN t.qualifiedName AS id, t.simpleName AS name, t.kind AS kind,
                       t.packageName AS pkg, t.lineCount AS lines
                """).bind(project).to("p").fetch().all();

        Collection<Map<String, Object>> edges = neo4j.query("""
                MATCH (a:Type {project: $p})-[r:EXTENDS|IMPLEMENTS|DEPENDS_ON]->(b:Type {project: $p})
                RETURN a.qualifiedName AS from, b.qualifiedName AS to, type(r) AS kind
                """).bind(project).to("p").fetch().all();

        return Map.of("nodes", List.copyOf(nodes), "edges", List.copyOf(edges));
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
                       m.visibility AS visibility, m.isStatic AS isStatic, count(caller) AS callers
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
}
