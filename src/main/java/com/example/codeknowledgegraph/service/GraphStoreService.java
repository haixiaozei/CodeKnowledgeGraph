package com.example.codeknowledgegraph.service;

import com.example.codeknowledgegraph.parser.model.AnalysisResult;
import com.example.codeknowledgegraph.parser.model.AnalysisResult.Edge;
import com.example.codeknowledgegraph.parser.model.AnalysisResult.FieldInfo;
import com.example.codeknowledgegraph.parser.model.AnalysisResult.MethodInfo;
import com.example.codeknowledgegraph.parser.model.AnalysisResult.TypeInfo;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 将分析结果写入 Neo4j。
 * 支持两种模式:全量替换(replaceProject)与增量落地(applyDelta + 跨文件边恢复)。
 */
@Service
public class GraphStoreService {

    private final Neo4jClient neo4j;

    public GraphStoreService(Neo4jClient neo4j) {
        this.neo4j = neo4j;
    }

    @Transactional
    public Map<String, Object> replaceProject(AnalysisResult r) {
        return replaceProject(r, r.rootPath(), null);
    }

    /** 全量替换:清空项目数据后重建(F3.4 降级路径 / 首次分析 / Git 模式) */
    @Transactional
    public Map<String, Object> replaceProject(AnalysisResult r, String source, String commit) {
        String p = r.projectName();

        // 清除该项目的旧数据
        neo4j.query("MATCH (n) WHERE n.project = $p DETACH DELETE n").bind(p).to("p").run();
        upsertProject(p, source, commit, r.fileCount());

        writePackages(p, r.packages());
        writeTypes(p, r.types());
        writeMethods(p, r.methods());
        writeFields(p, r.fields());
        writeTypeEdges(r.typeEdges(), p);
        writeCallEdges(p, r.callEdges());

        return statsOf(r, "full");
    }

    /** F3.3: 增量落地——变更文件的实体与边(包节点用 MERGE,避免与未变更数据重复) */
    @Transactional
    public void applyDelta(AnalysisResult delta) {
        String p = delta.projectName();
        writePackagesMerge(p, delta.packages());
        writeTypes(p, delta.types());
        writeMethods(p, delta.methods());
        writeFields(p, delta.fields());
        writeTypeEdges(delta.typeEdges(), p);
        writeCallEdges(p, delta.callEdges());
    }

    /** F3.3: 删除一组文件(相对路径)产生的全部节点及其关系 */
    @Transactional
    public void deleteByFilePaths(String project, Collection<String> files) {
        if (files.isEmpty()) return;
        neo4j.query("MATCH (n) WHERE n.project = $p AND n.filePath IN $files DETACH DELETE n")
                .bind(project).to("p").bind(files).to("files").run();
    }

    /** F3.1: 更新项目的分析状态(commit 指针与文件数;未提交改动时不推进 commit) */
    @Transactional
    public void updateAnalysisState(String project, String commit, Integer fileCount) {
        neo4j.query("MERGE (x:Project {name: $p}) SET x.commit = $c, x.fileCount = $f")
                .bind(project).to("p")
                .bind(commit == null ? "" : commit).to("c")
                .bind(fileCount == null ? 0 : fileCount).to("f").run();
    }

    /** F3.1: 读取项目分析状态(path/commit/fileCount),无记录返回空 Map */
    public Map<String, Object> loadProjectState(String project) {
        return neo4j.query("MATCH (x:Project {name: $p}) " +
                        "RETURN x.path AS path, x.commit AS commit, x.fileCount AS fileCount")
                .bind(project).to("p").fetch().first().orElse(Map.of());
    }

    /** F3.3: 跨文件入边快照——删除变更文件节点时,来自未变更文件的边会随之消失,需先捕获后按 qn 恢复 */
    public record CrossEdges(List<Map<String, Object>> typeEdges, List<Map<String, Object>> callEdges) {}

    public CrossEdges captureCrossEdges(String project, Collection<String> files) {
        List<Map<String, Object>> typeEdges = new ArrayList<>(neo4j.query("""
                MATCH (a:Type {project: $p})-[r]->(b:Type {project: $p})
                WHERE b.filePath IN $files AND NOT coalesce(a.filePath, '') IN $files
                RETURN a.qualifiedName AS from, type(r) AS kind, b.qualifiedName AS to
                """).bind(project).to("p").bind(files).to("files").fetch().all());

        List<Map<String, Object>> callEdges = new ArrayList<>(neo4j.query("""
                MATCH (caller:Method {project: $p})-[:CALLS]->(m:Method {project: $p})
                WHERE m.filePath IN $files AND NOT coalesce(caller.filePath, '') IN $files
                RETURN caller.id AS caller, m.id AS callee
                """).bind(project).to("p").bind(files).to("files").fetch().all());

        return new CrossEdges(typeEdges, callEdges);
    }

    /** F3.3: 恢复跨文件边(目标仍在图中才恢复;类型被删/改名的边自然丢弃,全量重析可修复) */
    @Transactional
    public void restoreCrossEdges(String project, CrossEdges captured) {
        for (String kind : new String[]{AnalysisResult.EXTENDS, AnalysisResult.IMPLEMENTS, AnalysisResult.DEPENDS_ON}) {
            List<Map<String, Object>> rows = captured.typeEdges().stream()
                    .filter(e -> kind.equals(e.get("kind"))).toList();
            if (rows.isEmpty()) continue;
            neo4j.query("UNWIND $rows AS row " +
                            "MATCH (a:Type {qualifiedName: row.from, project: $p}), (b:Type {qualifiedName: row.to, project: $p}) " +
                            "MERGE (a)-[:" + kind + "]->(b)")
                    .bind(rows).to("rows").bind(project).to("p").run();
        }
        if (!captured.callEdges().isEmpty()) {
            neo4j.query("""
                    UNWIND $rows AS row
                    MATCH (c:Method {id: row.caller, project: $p}), (m:Method {id: row.callee, project: $p})
                    MERGE (c)-[:CALLS]->(m)
                    """).bind(captured.callEdges()).to("rows").bind(project).to("p").run();
        }
    }

    /** F3.3: 类型种子——未变更文件的既有类型(排除即将删除重建的部分) */
    public List<TypeInfo> loadTypeSeed(String project, Collection<String> excludeFiles) {
        Collection<Map<String, Object>> rows = neo4j.query("""
                MATCH (t:Type {project: $p})
                WHERE NOT coalesce(t.filePath, '') IN $ex
                RETURN t.qualifiedName AS qn, t.simpleName AS name, t.packageName AS pkg,
                       t.kind AS kind, t.visibility AS vis, t.isAbstract AS abstract,
                       t.lineCount AS lines, t.filePath AS file,
                       t.startLine AS sl, t.endLine AS el, t.javadoc AS javadoc
                """).bind(project).to("p").bind(excludeFiles).to("ex").fetch().all();
        return rows.stream().map(r -> new TypeInfo(
                (String) r.get("qn"), (String) r.get("name"), (String) r.get("pkg"),
                (String) r.get("kind"), (String) r.get("vis"),
                Boolean.TRUE.equals(r.get("abstract")),
                r.get("lines") instanceof Number n ? n.intValue() : 0,
                (String) r.get("file"),
                r.get("sl") instanceof Number n ? n.intValue() : 0,
                r.get("el") instanceof Number n ? n.intValue() : 0,
                (String) r.get("javadoc"))).toList();
    }

    /** F3.3: 方法种子——未变更类型的方法(跨文件 CALLS 解析的目标索引) */
    public List<MethodInfo> loadMethodSeed(String project, Collection<String> excludeFiles) {
        Collection<Map<String, Object>> rows = neo4j.query("""
                MATCH (t:Type {project: $p})-[:HAS_METHOD]->(m:Method)
                WHERE NOT coalesce(t.filePath, '') IN $ex
                RETURN m.id AS id, t.qualifiedName AS owner, m.name AS name,
                       m.isConstructor AS ctor, m.filePath AS file
                """).bind(project).to("p").bind(excludeFiles).to("ex").fetch().all();
        return rows.stream().map(r -> new MethodInfo(
                (String) r.get("id"), (String) r.get("owner"), (String) r.get("name"),
                r.get("name") + "(seed)", "", "", false,
                Boolean.TRUE.equals(r.get("ctor")),
                (String) r.get("file"), 0, 0, null)).toList();
    }

    // ==================== 写入器(全量/增量共用) ====================

    private void upsertProject(String p, String source, String commit, int fileCount) {
        neo4j.query("MERGE (x:Project {name: $p}) SET x.path = $path, x.commit = $commit, x.fileCount = $f")
                .bind(p).to("p").bind(source).to("path")
                .bind(commit == null ? "" : commit).to("commit")
                .bind(fileCount).to("f").run();
    }

    /** 全量模式专用:数据已清空,直接 CREATE */
    private void writePackages(String p, List<String> packages) {
        if (packages.isEmpty()) return;
        neo4j.query("UNWIND $rows AS name CREATE (x:Package {name: name, project: $p})")
                .bind(packages).to("rows").bind(p).to("p").run();
    }

    private void writePackagesMerge(String p, List<String> packages) {
        if (packages.isEmpty()) return;
        neo4j.query("UNWIND $rows AS name MERGE (x:Package {name: name, project: $p})")
                .bind(packages).to("rows").bind(p).to("p").run();
    }

    private void writeTypes(String p, List<TypeInfo> types) {
        if (types.isEmpty()) return;
        List<Map<String, Object>> typeRows = types.stream()
                .map(t -> Map.<String, Object>ofEntries(
                        Map.entry("qn", t.qualifiedName()), Map.entry("name", t.simpleName()), Map.entry("pkg", t.packageName()),
                        Map.entry("kind", t.kind()), Map.entry("vis", t.visibility()),
                        Map.entry("abstract", t.isAbstract()), Map.entry("lines", t.lineCount()),
                        Map.entry("file", t.filePath() == null ? "" : t.filePath()),
                        Map.entry("sl", t.startLine()), Map.entry("el", t.endLine()),
                        Map.entry("javadoc", t.javadoc() == null ? "" : t.javadoc())))
                .toList();
        neo4j.query("""
                UNWIND $rows AS row
                CREATE (t:Type {qualifiedName: row.qn, project: $p})
                SET t.simpleName = row.name, t.kind = row.kind, t.visibility = row.vis,
                    t.isAbstract = row.abstract, t.lineCount = row.lines, t.packageName = row.pkg,
                    t.filePath = row.file, t.startLine = row.sl, t.endLine = row.el, t.javadoc = row.javadoc
                WITH t, row
                MATCH (pkg:Package {name: row.pkg, project: $p})
                CREATE (pkg)-[:CONTAINS]->(t)
                """).bind(typeRows).to("rows").bind(p).to("p").run();
    }

    private void writeMethods(String p, List<MethodInfo> methods) {
        if (methods.isEmpty()) return;
        List<Map<String, Object>> methodRows = methods.stream()
                .map(m -> Map.<String, Object>ofEntries(
                        Map.entry("id", m.id()), Map.entry("owner", m.ownerType()), Map.entry("name", m.name()), Map.entry("sig", m.signature()),
                        Map.entry("ret", m.returnType()), Map.entry("vis", m.visibility()), Map.entry("static", m.isStatic()),
                        Map.entry("ctor", m.isConstructor()),
                        Map.entry("file", m.filePath() == null ? "" : m.filePath()),
                        Map.entry("sl", m.startLine()), Map.entry("el", m.endLine()),
                        Map.entry("javadoc", m.javadoc() == null ? "" : m.javadoc())))
                .toList();
        neo4j.query("""
                UNWIND $rows AS row
                CREATE (m:Method {id: row.id, project: $p})
                SET m.name = row.name, m.signature = row.sig, m.returnType = row.ret,
                    m.visibility = row.vis, m.isStatic = row.static, m.isConstructor = row.ctor,
                    m.filePath = row.file, m.startLine = row.sl, m.endLine = row.el, m.javadoc = row.javadoc
                WITH m, row
                MATCH (t:Type {qualifiedName: row.owner, project: $p})
                CREATE (t)-[:HAS_METHOD]->(m)
                """).bind(methodRows).to("rows").bind(p).to("p").run();
    }

    private void writeFields(String p, List<FieldInfo> fields) {
        if (fields.isEmpty()) return;
        List<Map<String, Object>> fieldRows = fields.stream()
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
    }

    private void writeTypeEdges(List<Edge> edges, String project) {
        for (String kind : new String[]{AnalysisResult.EXTENDS, AnalysisResult.IMPLEMENTS, AnalysisResult.DEPENDS_ON}) {
            List<Map<String, Object>> rows = edges.stream()
                    .filter(e -> kind.equals(e.kind()))
                    .map(e -> Map.<String, Object>of("from", e.from(), "to", e.to()))
                    .toList();
            if (rows.isEmpty()) continue;
            neo4j.query("UNWIND $rows AS row " +
                            "MATCH (a:Type {qualifiedName: row.from, project: $p}), (b:Type {qualifiedName: row.to, project: $p}) " +
                            "CREATE (a)-[:" + kind + "]->(b)")
                    .bind(rows).to("rows").bind(project).to("p").run();
        }
    }

    private void writeCallEdges(String p, List<Edge> callEdges) {
        if (callEdges.isEmpty()) return;
        neo4j.query("""
                UNWIND $rows AS row
                MATCH (a:Method {id: row.from, project: $p}), (b:Method {id: row.to, project: $p})
                CREATE (a)-[:CALLS]->(b)
                """).bind(callEdges.stream().map(e -> Map.<String, Object>of(
                        "from", e.from(), "to", e.to())).toList())
                .to("rows").bind(p).to("p").run();
    }

    private Map<String, Object> statsOf(AnalysisResult r, String mode) {
        return new java.util.HashMap<>(Map.of(
                "project", r.projectName(),
                "mode", mode,
                "files", r.fileCount(),
                "types", r.types().size(),
                "methods", r.methods().size(),
                "fields", r.fields().size(),
                "typeRelations", r.typeEdges().size(),
                "callRelations", r.callEdges().size()));
    }

    public Collection<Map<String, Object>> listProjects() {
        return neo4j.query(
                        "MATCH (x:Project) OPTIONAL MATCH (t:Type {project: x.name}) " +
                        "RETURN x.name AS name, x.path AS path, x.commit AS commit, count(t) AS typeCount")
                .fetch().all();
    }

    /**
     * 删除项目及其全部图谱数据(类型/方法/字段/包/关系)。
     * @return true=删除成功;false=项目不存在
     */
    @Transactional
    public boolean deleteProject(String name) {
        // 先确认项目存在,避免无效删除
        Long count = neo4j.query("MATCH (x:Project {name: $name}) RETURN count(x) AS c")
                .bind(name).to("name").fetchAs(Long.class).one().orElse(0L);
        if (count == 0) {
            return false;
        }
        // 删除 Project 节点 + 所有携带该 project 属性的节点(含关系,DETACH DELETE)
        neo4j.query("MATCH (x:Project {name: $name}) DETACH DELETE x")
                .bind(name).to("name").run();
        neo4j.query("MATCH (n) WHERE n.project = $name DETACH DELETE n")
                .bind(name).to("name").run();
        return true;
    }
}
