# CodeKnowledgeGraph 项目需求文档（Roadmap）

> 定位：将代码仓库解析为 Neo4j 知识图谱，作为 **AI 梳理大型项目的外接代码大脑**。
> AI agent 通过 MCP 接口直接查询图谱，获取结构、关系、源码上下文，避免全库扫描。
>
> 现状基线（已完成）：Java 项目解析入库、图谱可视化前端、图查询 / 影响分析 / 环检测 / 搜索接口、请求日志、全局异常处理。

---

## 阶段一：源码定位与上下文包（图谱的"血肉"）

**目标：图谱不再只有骨架（签名/关系），AI 可以按图索骥拿到真实代码。**

当前 `Type` / `Method` 节点缺少位置信息，AI 即使查到了类型也无法定位源码。本阶段补齐这块地基，
并产出核心能力——**上下文包（Context Pack）**。

### 功能需求

| 编号 | 需求 | 说明 |
|------|------|------|
| F1.1 | 源码位置入库 | `Type` 节点增加 `filePath`、`startLine`、`endLine`；`Method` 节点增加 `filePath`、`startLine`、`endLine`（JavaParser 已提供位置信息，解析侧改动小） |
| F1.2 | Javadoc 提取 | `Type` / `Method` 节点增加 `javadoc` 属性（首个句子或前 N 字符），AI 不读源码也能拿到语义 |
| F1.3 | 源码片段接口 | `GET /api/source?project=&qn=&method=` —— 按类型/方法定位返回源码文本（从本地路径模式分析的项目可直接读文件；Git 模式需留存源码或仅支持本地路径） |
| F1.4 | 上下文包接口 | `GET /api/context/{qualifiedName}?project=` —— 返回紧凑文本：类型源码 + 直接依赖类型的签名摘要 + 调用者/被调用者列表。**这是给 AI 上下文的最小完整单元** |

### 验收标准

- 对任意已入库类型，能通过 `filePath + startLine/endLine` 直接定位到源码
- `context` 接口返回的文本可以直接粘贴进 LLM 对话并足以理解该类型的职责
- 重新分析已有项目后，历史数据自动升级（全量替换逻辑不变，天然兼容）

### 不做的事

- 不做方法体级别的 AST 细节查询（调用关系已覆盖）
- 不做 Git 模式下的源码留存（依赖本地路径，见阶段三）

---

## 阶段二：MCP Server 接口（AI 的入口）

**目标：任何 AI agent（OpenCode / Claude Desktop 等）可以直接查询图谱，无需人工转述。**

阶段一的 HTTP 接口在此阶段包装为 MCP tools，查询逻辑全部复用 `GraphQueryService`。

### 功能需求

| 编号 | 需求 | 说明 |
|------|------|------|
| F2.1 | 引入 MCP Server | 基于 Spring AI 的 `spring-ai-starter-mcp-server-webmvc`，与现有 Web 服务同端口 |
| F2.2 | 核心 tools | `list_projects`、`search_types`、`get_type_detail`、`get_method_callers/callees`、`get_context_pack`（阶段一 F1.4）、`impact_analysis`、`find_cycles` |
| F2.3 | 分析触发 tool | `analyze_project`（本地路径模式）——支持 AI 在会话中主动索引新项目；长任务注意异步/超时处理 |
| F2.4 | 连接文档 | `docs/mcp-setup.md`：如何在 OpenCode / Claude Desktop 中配置本服务的 MCP 接入 |

### 验收标准

- 在 OpenCode 的 `opencode.jsonc` 中配置本服务后，agent 可通过自然语言完成：查项目结构 → 定位类型 → 拿上下文包 → 分析改动影响
- MCP tools 返回内容控制大小（截断策略），避免撑爆上下文

### 不做的事

- 不做 MCP 资源（resources）和提示（prompts），只做 tools
- 不做多用户鉴权（单机工具定位）

---

## 阶段三：增量更新（大型项目的可用性分水岭）

**目标：AI 高频迭代场景下，重新分析从"分钟级全量"变为"秒级增量"。**

当前 `replaceProject` 全量删除重建。本阶段实现基于 git diff 的增量分析。

### 功能需求

| 编号 | 需求 | 说明 |
|------|------|------|
| F3.1 | 分析状态记录 | `Project` 节点记录 `analyzedCommit`、`rootPath`（本地路径模式） |
| F3.2 | 变更检测 | 重新分析时 `git diff <analyzedCommit> HEAD` 得到变更文件集 |
| F3.3 | 增量替换 | 仅重新解析变更文件；删除已消失的类型节点及其边；未受影响的类型/边保持原样（保留 Javadoc 等属性，避免重算） |
| F3.4 | 降级策略 | 无 git 信息（纯本地目录）或 commit 不可追溯时，回退全量分析 |
| F3.5 | 手动开关 | API 参数 `full=true` 强制全量重建 |

### 验收标准

- 大型项目（万级文件）修改少量文件后重新分析，耗时 < 5s
- 增量分析后图谱数据与全量分析结果一致性抽样校验通过

### 不做的事

- 不做文件监听（watch）自动触发，保持显式调用
- 不做多模块 Maven 并行分析优化（先跑通正确性）

---

## 阶段四：分析维度增强（让 AI 更"懂"代码）

**目标：从"结构图谱"升级为"质量与语义图谱"，AI 能判断该看什么、忽略什么。**

### 功能需求

| 编号 | 需求 | 说明 |
|------|------|------|
| F4.1 | 死代码检测 | 无调用者且非入口（main、公开 REST 端点、测试）的方法列表 → AI 可放心忽略 |
| F4.2 | REST 端点索引 | 提取 `@RestController` / `@RequestMapping` 元数据，`Method` 节点标注 HTTP 路径 → "这个接口在哪实现"秒查 |
| F4.3 | 耦合度指标 | 类的扇入/扇出、包间依赖计数、上帝类候选（方法数+扇入扇出超阈值） |
| F4.4 | Git 热点 | 变更频率 TopN 文件/类型（高频变更 = 高风险区域，AI 优先关注） |
| F4.5 | 前端配合 | 上述结果在可视化页面展示（死代码高亮、热点着色等，按需裁剪） |

### 验收标准

- 每项指标有独立 API + （可选）MCP tool
- 死代码检测在真实项目上的误报率可接受（入口识别规则可配置）

---

## 阶段五：多语言与生态（远期）

**目标：从 Java 专用走向通用。**

| 编号 | 需求 | 说明 |
|------|------|------|
| F5.1 | tree-sitter 解析器 | Python / TypeScript / Go 的结构解析与调用提取（架构上抽象出 `LanguageParser` 接口，现有 JavaParser 实现成为其一） |
| F5.2 | 语义搜索 | 方法/类型 embedding + 向量检索，支持"找做 X 事的代码" |
| F5.3 | 团队共享 | 中心化部署 + 项目级隔离 + 鉴权（脱离单机工具定位后再做） |

---

## 全局约定

- **错误响应**：统一 `{"error": "..."}` JSON（`GlobalExceptionHandler` 已就位）
- **日志**：接口一行摘要（`RequestLoggingFilter`）+ 业务 `log.info`
- **兼容性**：`AnalysisResult` record 新增字段时同步更新 Neo4j 写入语句；全量替换模式下无迁移负担
- **依赖引入**：Spring AI 版本需与 Spring Boot 4.x 匹配，阶段二开始前先行验证

## 里程碑总览

```
阶段一 ──→ 阶段二 ──→ 阶段三 ──→ 阶段四 ──→ 阶段五
源码定位    MCP 接口    增量更新    分析增强    多语言
（地基）    （AI入口）  （可用性）  （深度）    （广度）
```

> 阶段一、二完成后即达成项目立项目标："AI 梳理大型项目更方便"的最小闭环。
