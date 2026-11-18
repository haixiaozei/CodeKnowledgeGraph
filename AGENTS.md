# 项目开发约定

## 开发流程

- **按阶段开发**:所有新功能必须遵循 `docs/requirements.md` 的阶段规划,不越阶段实现后续内容,每个阶段的"不做的事"是硬约束
- **分支策略**:禁止直接在 `main` 分支开发。每个阶段基于最新 `main` 拉取独立分支:
  ```bash
  git checkout main && git pull --ff-only   # 同步(如果需要)
  git checkout -b feature/phase<N>-<主题>
  ```
- **合并**:阶段完成并通过验收标准后,将该分支合并回 `main`(合并前确认验收标准全部满足)
- **提交**:每次修改完成后立即 `git commit`(本地),**禁止 `git push` 到远程仓库**

## 技术约定

- 错误响应统一 JSON 格式:`{"error": "..."}`(由 `GlobalExceptionHandler` 保证,业务代码不要手写 try/catch 转响应)
- 所有 `/api/**` 请求会自动打印一行摘要日志(`RequestLoggingFilter`),无需在 controller 内手动打点
- 日志用 SLF4J,INFO 及以上级别默认输出到控制台
- 前端是 `src/main/resources/static/index.html` 单文件(零构建、深色主题、抽屉式详情面板),保持此形态,不引入前端构建工具
- 改完 Java 代码必须运行 `./mvnw -q compile -DskipTests` 验证编译

## 环境

- 启动:先确保 Neo4j 运行(bolt://localhost:7687),再 `./mvnw spring-boot:run`
- 访问:http://localhost:8080
- 注意:静态资源与 class 从 `target/classes` 加载,改代码后需重启应用
