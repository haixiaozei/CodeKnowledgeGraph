package com.example.codeknowledgegraph.service;

import com.example.codeknowledgegraph.parser.model.AnalysisResult;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 将分析结果批量写入 Neo4j。
 */
@Service
public class GraphStoreService {

    private final Neo4jClient neo4j;

    public GraphStoreService(Neo4jClient neo4j) {
        this.neo4j = neo4j;
    }

    @Transactional
    public Map<String, Object> replaceProject(AnalysisResult r) {
        String p = r.projectName();

        // 清除该项目的旧数据
        neo4j.query("MATCH (n) WHERE n.project = $p DETACH DELETE n").bind(p).to("p").run();
        neo4j.query("MERGE (x:Project {name: $p}) SET x.path = $path")
                .bind(p).to("p").bind(r.rootPath()).to("path").run();

        // 包
        if (!r.packages().isEmpty()) {
            neo4j.query("UNWIND $rows AS name CREATE (x:Package {name: name, project: $p})")
                    .bind(r.packages()).to("rows").bind(p).to("p").run();
        }

        // 类型 + 包含关系
        List<Map<String, Object>> typeRows = r.types().stream()
                .map(t -> Map.<String, Object>of(
                        "qn", t.qualifiedName(), "name", t.simpleName(), "pkg", t.packageName(),
                        "kind", t.kind(), "vis", t.visibility(),
                        "abstract", t.isAbstract(), "lines", t.lineCount()))
                .toList();
        neo4j.query("""
                UNWIND $rows AS row
                CREATE (t:Type {qualifiedName: row.qn, project: $p})
                SET t.simpleName = row.name, t.kind = row.kind, t.visibility = row.vis,
                    t.isAbstract = row.abstract, t.lineCount = row.lines, t.packageName = row.pkg
                WITH t, row
                MATCH (pkg:Package {name: row.pkg, project: $p})
                CREATE (pkg)-[:CONTAINS]->(t)
                """).bind(typeRows).to("rows").bind(p).to("p").run();

        // 方法
        List<Map<String, Object>> methodRows = r.methods().stream()
                .map(m -> Map.<String, Object>of(
                        "id", m.id(), "owner", m.ownerType(), "name", m.name(), "sig", m.signature(),
                        "ret", m.returnType(), "vis", m.visibility(), "static", m.isStatic(),
                        "ctor", m.isConstructor()))
                .toList();
        neo4j.query("""
                UNWIND $rows AS row
                CREATE (m:Method {id: row.id, project: $p})
                SET m.name = row.name, m.signature = row.sig, m.returnType = row.ret,
                    m.visibility = row.vis, m.isStatic = row.static, m.isConstructor = row.ctor
                WITH m, row
                MATCH (t:Type {qualifiedName: row.owner, project: $p})
                CREATE (t)-[:HAS_METHOD]->(m)
                """).bind(methodRows).to("rows").bind(p).to("p").run();

        // 字段
        List<Map<String, Object>> fieldRows = r.fields().stream()
                .map(f -> Map.<String, Object>of(
                        "id", f.id(), "owner", f.ownerType(), "name", f.name(),
                        "type", f.type(), "vis", f.visibility(), "static", f.isStatic()))
                .toList();
        neo4j.query("""
                UNWIND $rows AS row
                CREATE (f:Field {id: row.id, project: $p})
                SET f.name = row.name, f.type = row.type, f.visibility = row.vis, f.isStatic = row.static
                WITH f, row
                MATCH (t:Type {qualifiedName: row.owner, project: $p})
                CREATE (t)-[:HAS_FIELD]->(f)
                """).bind(fieldRows).to("rows").bind(p).to("p").run();

        // 类型之间的关系(按类型分批执行)
        writeTypeEdges(r.typeEdges(), AnalysisResult.EXTENDS, p);
        writeTypeEdges(r.typeEdges(), AnalysisResult.IMPLEMENTS, p);
        writeTypeEdges(r.typeEdges(), AnalysisResult.DEPENDS_ON, p);

        // 方法调用
        if (!r.callEdges().isEmpty()) {
            neo4j.query("""
                    UNWIND $rows AS row
                    MATCH (a:Method {id: row.from, project: $p}), (b:Method {id: row.to, project: $p})
                    CREATE (a)-[:CALLS]->(b)
                    """).bind(r.callEdges().stream().map(e -> Map.<String, Object>of(
                            "from", e.from(), "to", e.to())).toList())
                    .to("rows").bind(p).to("p").run();
        }

        return Map.of(
                "project", p,
                "files", r.fileCount(),
                "types", r.types().size(),
                "methods", r.methods().size(),
                "fields", r.fields().size(),
                "typeRelations", r.typeEdges().size(),
                "callRelations", r.callEdges().size());
    }

    private void writeTypeEdges(List<AnalysisResult.Edge> edges, String kind, String project) {
        List<Map<String, Object>> rows = edges.stream()
                .filter(e -> kind.equals(e.kind()))
                .map(e -> Map.<String, Object>of("from", e.from(), "to", e.to()))
                .toList();
        if (rows.isEmpty()) return;
        neo4j.query("UNWIND $rows AS row " +
                        "MATCH (a:Type {qualifiedName: row.from, project: $p}), (b:Type {qualifiedName: row.to, project: $p}) " +
                        "CREATE (a)-[:" + kind + "]->(b)")
                .bind(rows).to("rows").bind(project).to("p").run();
    }

    public Collection<Map<String, Object>> listProjects() {
        return neo4j.query(
                        "MATCH (x:Project) OPTIONAL MATCH (t:Type {project: x.name}) " +
                        "RETURN x.name AS name, x.path AS path, count(t) AS typeCount")
                .fetch().all();
    }
}
