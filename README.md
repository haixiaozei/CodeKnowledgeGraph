# CodeKnowledgeGraph 代码知识图谱

> **让 AI 真正读懂大型代码库**：把代码仓库解析为 Neo4j 知识图谱，任何支持 MCP 的 AI agent（OpenCode、Claude Desktop 等）都能直接查询项目的结构、调用链、依赖关系与源码上下文——不再需要全库扫描和人肉转述。

## 背景

用 AI 梳理大型项目时存在三个痛点：

1. **上下文窗口装不下**——AI 只能靠关键词搜索碎片化地读文件，拼不出全局结构
2. **"这个改动会影响什么"答不准**——缺少调用图与依赖关系数据
3. **每次会话都要重新摸索**——项目的理解没有沉淀

本项目把仓库解析成图谱（类型/方法/字段为节点，继承/依赖/调用为边），并暴露为 MCP 服务，让 AI 带着结构化上下文工作。数据支持秒级增量更新，始终与代码同步。

## 核心能力

| 能力 | 说明 |
|------|------|
| **多语言解析** | Java（JavaParser + 符号求解器，精确调用解析）、Python / TypeScript / TSX / Go（tree-sitter，按名启发式） |
| **图谱查询** | 类型详情、方法调用链、影响分析、循环依赖、包结构过滤 |
| **上下文包** | 一个类型所需的最小完整信息（元信息 + Javadoc/Docstring + 源码 + 依赖签名 + 调用关系），供 AI 直接消费 |
| **质量指标** | 死代码检测（带置信度与处置建议）、REST 端点索引、耦合度/上帝类、Git 变更热点 |
| **增量更新** | 基于 git diff + 未提交改动检测，万级文件项目改动数个文件后秒级完成 |
| **MCP 服务** | 15 个工具，AI 可完成"查结构 → 定位类型 → 拿上下文 → 评估影响"全流程 |
| **可视化** | 内置 Web 页面：力导布局图谱、包过滤、聚焦视图、死代码/循环依赖弹窗 |

## 架构一览

```
代码仓库 (Java/Python/TS/Go)
    │  解析器: JavaParser(Java) / tree-sitter(Python/TS/Go)
    ▼
AnalysisResult ──► Neo4j 图谱 ◄── 增量更新(git diff + 工作区改动)
                        │
        ┌───────────────┼────────────────┐
        ▼               ▼                ▼
   HTTP API          MCP Server      可视化前端
 (localhost:8080/api) (/mcp, 15 工具)  (static/index.html)
        │               │
        └───── AI agent (OpenCode / Claude 等)
```

## 本地部署

### 前置条件

- **JDK 21**
- **Neo4j 5.x**（本地安装或 Docker）
- 本机 `git`（克隆仓库与增量变更检测依赖 git 命令）
- 想让 AI 使用图谱：任一支持 MCP 的 AI 客户端（可选）

### 1. 启动 Neo4j

Docker 方式（推荐）：

```bash
docker run -d --name neo4j -p 7474:7474 -p 7687:7687 \
  -e NEO4J_AUTH=neo4j/codekg123 neo4j:5
```

或本地安装后确保 bolt 端口(7687)可访问。账号密码需与 `src/main/resources/application.yaml` 中配置一致（默认 `neo4j/codekg123`，生产环境请修改）。

### 2. 启动本服务

```bash
git clone <本仓库地址> && cd CodeKnowledgeGraph
./mvnw spring-boot:run
```

或用 IDEA 直接运行 `CodeKnowledgeGraphApplication`。启动后：

- 可视化页面：<http://localhost:8080>
- MCP 端点：<http://localhost:8080/mcp>
- 首次启动会自动创建 Neo4j 索引（约 1 秒）

### 3. 分析第一个项目

页面顶栏点「分析项目」→ 输入**本地目录绝对路径**（或 Git 仓库地址）→ 等待完成。

也可以用 API：

```bash
curl -X POST http://localhost:8080/api/projects \
  -H "Content-Type: application/json" \
  -d '{"name": "my-project", "path": "/Users/you/projects/my-project"}'
```

- 重复分析自动**增量**（只处理变更文件）；`"full": true` 强制全量重建
- Git 仓库地址模式使用浅克隆，分析完自动清理临时目录（该模式下源码片段/上下文包中的源码不可用，建议用本地路径模式）

### 4. 接入 AI（可选但推荐）

```bash
opencode mcp add code-graph --url http://localhost:8080/mcp
```

随后在任意会话里直接说："帮我梳理 xxx 项目的结构"、"这个接口在哪实现"、"改了 X 类会影响什么"。完整接入指南（含 Claude Desktop / Cursor 等）见 [docs/mcp-setup.md](docs/mcp-setup.md)。

## 常用 API 速查

| 接口 | 用途 |
|------|------|
| `POST /api/projects` | 分析项目（body: name/path/gitUrl/branch/full） |
| `GET /api/projects` | 项目列表 |
| `DELETE /api/projects/{name}` | 删除项目图谱 |
| `GET /api/graph/overview?project=` | 图谱概览（可按包过滤） |
| `GET /api/context/{qualifiedName}?project=` | 上下文包（AI 首选） |
| `GET /api/impact/{qualifiedName}?project=` | 影响分析 |
| `GET /api/source?project=&qn=` | 源码片段（本地路径模式） |
| `GET /api/endpoints?project=` | REST 端点索引 |
| `GET /api/quality/dead-code` `coupling` `hotspots` `cycles` | 质量指标 |

全部接口列表见 `ApiController`；错误统一返回 `{"error": "..."}`。

## 文档索引

| 文档 | 内容 |
|------|------|
| [docs/requirements.md](docs/requirements.md) | 需求路线图与各阶段交付状态（**进度真相源**） |
| [docs/mcp-setup.md](docs/mcp-setup.md) | MCP 接入指南（各 AI 客户端配置矩阵） |
| [docs/neo4j-tutorial.md](docs/neo4j-tutorial.md) | Neo4j 学习笔记（基于本项目真实数据） |
| [AGENTS.md](AGENTS.md) | 开发约定（分支策略/提交规范/技术约定） |

## 支持的语言与已知限制

| 语言 | 解析方式 | 调用边精度 | 已知限制 |
|------|---------|-----------|---------|
| Java | JavaParser + 符号求解器 | 高（精确解析） | — |
| Python | tree-sitter 按名启发式 | 中 | 相对导入依赖边较粗；鸭子类型动态分发无法精确 |
| TypeScript/TSX | tree-sitter 按名启发式 | 中 | 接口中函数类型属性暂不提取为方法；运行时动态调用不可见 |
| Go | tree-sitter 按名启发式 | 低-中 | 接收者变量类型不推断（`s.Method()` 不建边）；import 依赖边未解析 |

## 项目状态

五个阶段的规划与交付状态以 [docs/requirements.md](docs/requirements.md) 为准（阶段一~四已交付，阶段五已交付多语言解析，语义搜索/团队化待做）。测试数据与临时验证项目请勿留在生产 Neo4j 中。
