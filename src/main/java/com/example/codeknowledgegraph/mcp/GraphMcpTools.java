package com.example.codeknowledgegraph.mcp;

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

    public GraphMcpTools(GraphQueryService query, GraphStoreService store) {
        this.query = query;
        this.store = store;
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
}
