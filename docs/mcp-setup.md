# MCP 接入指南

CodeKnowledgeGraph 内置 MCP Server(Streamable-HTTP,端点 `http://localhost:8080/mcp`),任何支持 MCP 的 AI agent 都可以直连查询代码知识图谱。

## 前置条件

1. Neo4j 运行中(bolt://localhost:7687)
2. 启动应用:`./mvnw spring-boot:run`(或 IDEA 运行)
3. 至少分析过一个项目(可通过网页、HTTP 接口或 MCP 的 `analyze_project` 工具)

## OpenCode 接入

### 方式一:命令行(推荐)

```sh
opencode mcp add code-graph --url http://localhost:8080/mcp
opencode mcp list   # 应显示 ✓ code-graph connected
```

本服务无鉴权,若 `mcp list` 显示 `needs authentication`,在配置里显式关闭 OAuth(见方式二)。

### 方式二:手写配置

项目根目录 `opencode.jsonc`:

```jsonc
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "servers": {
      "code-graph": {
        "type": "remote",
        "url": "http://localhost:8080/mcp",
        "oauth": false
      }
    }
  }
}
```

配置保存后自动生效,无需重启 OpenCode。工具会以 `code-graph_<工具名>` 的形式暴露(如 `code-graph_get_context_pack`)。

## Claude Desktop 接入

编辑 `~/Library/Application Support/Claude/claude_desktop_config.json`(macOS):

```json
{
  "mcpServers": {
    "code-graph": {
      "type": "http",
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

保存后重启 Claude Desktop。

## 可用工具一览

| 工具 | 用途 |
|------|------|
| `list_projects` | 列出已入库项目(名称/路径/commit/类型数) |
| `search_types` | 按关键词搜索类型与方法 |
| `get_type_detail` | 类型详情:方法、字段、依赖、被依赖 |
| `get_type_source` | 读取类型或方法的源码片段(仅本地路径项目) |
| `get_context_pack` | **上下文包**——理解一个类型所需的最小完整信息,梳理项目优先用它 |
| `impact_analysis` | 影响分析:改动某类型会波及谁 |
| `find_cycles` | 循环依赖检测 |
| `get_method_callers` / `get_method_callees` | 方法级调用链 |
| `analyze_project` | 提交本地目录分析(异步,返回 jobId) |
| `get_analysis_status` | 查询分析任务状态 |

## 典型用法(AI 会话中)

```
"帮我梳理 spring-petclinic 项目的结构"            → list_projects → 图谱概览/包过滤
"ContextPackService 是干嘛的?"                   → search_types → get_context_pack
"如果我改了 X 类,会影响哪些地方?"                → impact_analysis
"这个项目有循环依赖吗?"                          → find_cycles
"把 /path/to/repo 分析入库"                      → analyze_project → get_analysis_status
```

## 注意事项

- **安全**:/mcp 端点无鉴权,仅限本机使用;不要把 8080 端口暴露到公网
- **异步分析**:大仓库分析需要几分钟,`analyze_project` 立即返回 jobId,用 `get_analysis_status` 轮询(单线程串行,同时只跑一个任务)
- **源码读取**:只有以"本地路径"模式分析的项目能返回源码;Git URL 模式分析的项目因临时目录已清理,会返回明确提示
- **数据升级**:旧版本分析的项目缺少源码位置信息,重新分析即可升级
