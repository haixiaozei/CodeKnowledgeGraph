package com.example.codeknowledgegraph.mcp;

import com.example.codeknowledgegraph.service.AnalysisJobService;
import com.example.codeknowledgegraph.service.GraphQueryService;
import com.example.codeknowledgegraph.service.GraphStoreService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * F2.2: MCP 工具集——把图谱查询能力暴露给 AI agent。
 * 通过注解自动扫描注册,@McpTool 的 description 是 agent 选择工具的依据,要写清楚。
 */
@Component
public class GraphMcpTools {

    private final GraphQueryService query;
    private final GraphStoreService store;
    private final AnalysisJobService jobs;

    public GraphMcpTools(GraphQueryService query, GraphStoreService store, AnalysisJobService jobs) {
        this.query = query;
        this.store = store;
        this.jobs = jobs;
    }

    @McpTool(name = "list_projects", description = "列出所有已分析入库的项目(名称、来源路径、commit、类型数量)")
    public Collection<Map<String, Object>> listProjects() {
        return store.listProjects();
    }

    @McpTool(name = "search_types", description = "在项目内按关键词搜索类型与方法(匹配全限定名/方法名,返回全限定名用于后续查询)")
    public List<Map<String, Object>> searchTypes(
            @McpToolParam(description = "项目名称,可先用 list_projects 查询") String project,
            @McpToolParam(description = "搜索关键词,如类名或方法名片段") String q) {
        return query.search(project, q);
    }

    @McpTool(name = "get_type_detail", description = "获取类型详情:全部方法(含调用者计数)、字段、依赖与被依赖关系")
    public Map<String, Object> getTypeDetail(
            @McpToolParam(description = "项目名称") String project,
            @McpToolParam(description = "类型全限定名,如 com.example.Foo") String qualifiedName) {
        return query.typeDetail(project, qualifiedName);
    }

    @McpTool(name = "get_type_source", description = "读取类型或方法的源码片段。仅本地路径模式分析的项目可用,Git 模式会返回提示")
    public Map<String, Object> getTypeSource(
            @McpToolParam(description = "项目名称") String project,
            @McpToolParam(description = "类型全限定名") String qualifiedName,
            @McpToolParam(description = "方法 id(格式: 全限定名.方法名(参数个数)),不传则返回整个类型", required = false) String methodId) {
        return query.sourceSnippet(project, qualifiedName, methodId);
    }

    @McpTool(name = "get_context_pack", description = "获取类型的上下文包——理解该类型所需的最小完整信息:元信息+Javadoc+源码+方法字段+直接依赖的签名摘要+外部调用关系。梳理大型项目时优先使用本工具")
    public Map<String, Object> getContextPack(
            @McpToolParam(description = "项目名称") String project,
            @McpToolParam(description = "类型全限定名") String qualifiedName) {
        return query.contextPack(project, qualifiedName);
    }

    @McpTool(name = "impact_analysis", description = "影响分析:反向传递依赖查询,判断改动某类型后会波及哪些类型")
    public List<Map<String, Object>> impactAnalysis(
            @McpToolParam(description = "项目名称") String project,
            @McpToolParam(description = "类型全限定名") String qualifiedName,
            @McpToolParam(description = "最大追溯深度,1-10,默认 5", required = false) Integer maxDepth) {
        return query.impactAnalysis(project, qualifiedName, maxDepth == null ? 5 : maxDepth);
    }

    @McpTool(name = "find_cycles", description = "检测项目内类型间的循环依赖,返回每条环上的类型序列")
    public List<Map<String, Object>> findCycles(
            @McpToolParam(description = "项目名称") String project) {
        return query.findCycles(project);
    }

    @McpTool(name = "get_method_callers", description = "查询指定方法的调用者(哪些方法调用了它),方法 id 从搜索或类型详情获得")
    public List<Map<String, Object>> getMethodCallers(
            @McpToolParam(description = "项目名称") String project,
            @McpToolParam(description = "方法 id,格式: 全限定名.方法名(参数个数)") String methodId) {
        return query.methodCallers(project, methodId);
    }

    @McpTool(name = "get_method_callees", description = "查询指定方法调用了哪些其他方法")
    public List<Map<String, Object>> getMethodCallees(
            @McpToolParam(description = "项目名称") String project,
            @McpToolParam(description = "方法 id,格式: 全限定名.方法名(参数个数)") String methodId) {
        return query.methodCallees(project, methodId);
    }

    // ==================== F2.3: 分析触发(异步) ====================

    @McpTool(name = "analyze_project", description = "分析本地代码目录并建立知识图谱(异步任务,立即返回 jobId)。大仓库需要几分钟,用 get_analysis_status 轮询结果。仅支持本地路径,Git 仓库请先 clone 到本地。重复分析时自动增量(只处理变更文件),full=true 强制全量重建")
    public Map<String, Object> analyzeProject(
            @McpToolParam(description = "项目名称,不传则用目录名推断", required = false) String name,
            @McpToolParam(description = "本地目录绝对路径,如 /Users/xxx/projects/demo") String path,
            @McpToolParam(description = "强制全量重建,默认 false 走增量", required = false) Boolean full) {
        return jobs.submit(name, path, Boolean.TRUE.equals(full));
    }

    @McpTool(name = "get_analysis_status", description = "查询分析任务状态:PENDING/RUNNING 排队或执行中,DONE 返回统计信息,FAILED 返回错误原因")
    public Map<String, Object> getAnalysisStatus(
            @McpToolParam(description = "analyze_project 返回的任务 id") String jobId) {
        return jobs.status(jobId);
    }

    // ==================== F4: 分析维度增强 ====================

    @McpTool(name = "find_rest_endpoint", description = "按路径片段查找 REST 接口的实现位置(HTTP 方法/路径/所属类/方法)。适合回答'这个接口在哪实现'")
    public List<Map<String, Object>> findRestEndpoint(
            @McpToolParam(description = "项目名称") String project,
            @McpToolParam(description = "路径片段,如 /api/projects;传空返回全部端点", required = false) String pathSubstring) {
        return query.restEndpoints(project, pathSubstring);
    }

    @McpTool(name = "find_dead_code", description = "死代码检测:无调用者且非入口的方法(已排除构造器/main/REST端点/接口声明/Override/Test等)。每项附带 suggestion 处置建议与置信度:高置信可建议用户删除,低置信(public)可能是外部 API 或'忘记接线'的功能,应提示用户人工确认而非直接删除。反射调用无法识别")
    public List<Map<String, Object>> findDeadCode(
            @McpToolParam(description = "项目名称") String project,
            @McpToolParam(description = "逗号分隔的排除注解名(覆盖默认: Override,Test 系列等)", required = false) String excludeAnnotations) {
        return query.deadCode(project, excludeAnnotations == null || excludeAnnotations.isBlank()
                ? null : List.of(excludeAnnotations.split(",")));
    }

    @McpTool(name = "get_coupling", description = "耦合度指标:被依赖最多(fanIn)/依赖最多(fanOut)的类型 Top15,以及上帝类候选(方法多或超长)。评估改动风险时使用")
    public Map<String, Object> getCoupling(
            @McpToolParam(description = "项目名称") String project) {
        return query.coupling(project);
    }

    @McpTool(name = "find_hotspots", description = "Git 变更热点:最近 200 个 commit 中变更最频繁的 Java 文件(高频变更=高风险区域)。仅本地路径模式项目可用")
    public List<Map<String, Object>> findHotspots(
            @McpToolParam(description = "项目名称") String project,
            @McpToolParam(description = "返回条数,默认 20,最大 100", required = false) Integer limit) {
        Object result = query.hotspots(project, limit == null ? 20 : limit);
        if (result instanceof Map<?, ?> m && m.containsKey("error")) return List.of(Map.of("error", m.get("error")));
        return (List<Map<String, Object>>) ((Map<?, ?>) result).get("hotspots");
    }
}
