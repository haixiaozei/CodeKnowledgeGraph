# 服务端部署指南

> ⚠️ **部署前必读——安全模型**
> 本服务当前**无鉴权**，且"分析本地路径"能力等价于对服务器文件系统的读取授权：
> `POST /api/projects {"path": "/"}` 会把整个文件系统解析进图谱，随后源码可经 API 读出。
> 因此**严禁将 8080 端口直接暴露公网**。三种可接受的暴露方式（任选其一）：
> 1. 仅内网/VPN 使用
> 2. 反向代理 + 认证（见下文"远程接入"）
> 3. 防火墙 IP 白名单

## 方案一：Docker Compose（推荐）

仓库已提供 `Dockerfile`（多阶段构建，运行镜像含 git）与 `docker-compose.yml`（app + neo4j 双容器）。

```bash
# 1. 设置 Neo4j 密码(生产环境务必用强密码)
echo "NEO4J_PASSWORD=你的强密码" > .env

# 2. 构建并启动
docker compose up -d --build

# 3. 查看日志 / 状态
docker compose logs -f app
curl http://127.0.0.1:8080/api/projects
```

要点：

- 两个端口都只绑定 `127.0.0.1`，公网访问必须走反向代理
- 分析"本地路径"时，把仓库放到宿主机 `./workspace/` 下（已挂载到容器 `/workspace`），path 填 `/workspace/仓库名`
- 数据持久化在 `neo4j-data` 卷；升级服务只需 `docker compose up -d --build`（图谱数据保留）
- 备份：`docker run --rm -v <项目名>_neo4j-data:/data -v $PWD:/backup alpine tar czf /backup/neo4j-backup.tar.gz /data`

## 方案二：裸机 jar + systemd

### 1. 服务器准备

```bash
# JDK 21 / git / Neo4j 5(安装方式按发行版,或用上面的 Docker 单独跑 Neo4j)
java -version   # 需要 21
git --version
```

### 2. 打包并上传

```bash
./mvnw -q package -DskipTests
scp target/*.jar server:/opt/codekg/app.jar
```

### 3. systemd 服务

```ini
# /etc/systemd/system/codekg.service
[Unit]
Description=CodeKnowledgeGraph
After=network.target neo4j.service

[Service]
User=codekg
WorkingDirectory=/opt/codekg
# 配置覆盖: 命令行参数或环境变量均可,见下文"配置项速查"
Environment=SPRING_DATA_NEO4J_URI=bolt://localhost:7687
Environment=SPRING_DATA_NEO4J_AUTHENTICATION_USERNAME=neo4j
Environment=SPRING_DATA_NEO4J_AUTHENTICATION_PASSWORD=你的密码
ExecStart=/usr/bin/java -Xmx1g -jar /opt/codekg/app.jar
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload && sudo systemctl enable --now codekg
sudo systemctl status codekg   # journalctl -u codekg -f 看日志
```

## 配置项速查

所有配置都可用环境变量或启动参数覆盖（Spring Boot 标准规则）：

| 配置 | 环境变量 | 默认值 | 说明 |
|------|---------|--------|------|
| Neo4j 地址 | `SPRING_DATA_NEO4J_URI` | `bolt://localhost:7687` | 服务器模式指向容器内/远端 Neo4j |
| Neo4j 账号/密码 | `SPRING_DATA_NEO4J_AUTHENTICATION_USERNAME` / `_PASSWORD` | `neo4j` / `codekg123` | **生产必须改密码** |
| 服务端口 | `SERVER_PORT` | `8080` | |

示例（启动参数方式）：`java -jar app.jar --spring.data.neo4j.uri=bolt://db.internal:7687`

## 远程接入 MCP（让外部 AI 客户端连服务器）

服务端前置：nginx 反向代理 + 认证。示例（token 头认证）：

```nginx
server {
  listen 443 ssl;
  server_name codekg.example.com;
  # ssl_certificate ...;

  # 简单 token 校验(生产建议 Spring Security 或 OAuth 代理,如 oauth2-proxy)
  if ($http_x_api_key != "你的长随机token") { return 401; }

  location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_read_timeout 3600s;   # 大仓库分析是长请求
  }
}
```

客户端配置（支持自定义头的客户端）：

```jsonc
// OpenCode
{ "mcp": { "servers": { "code-graph": {
    "type": "remote", "url": "https://codekg.example.com/mcp",
    "oauth": false,
    "headers": { "X-Api-Key": "{env:CODEKG_TOKEN}" } } } } }
```

```bash
# Claude Code
claude mcp add --transport http code-graph https://codekg.example.com/mcp \
  --header "X-Api-Key: $CODEKG_TOKEN"
```

> 注意：Claude Desktop 的 http 类型不支持自定义头，这类客户端建议走内网/VPN + IP 白名单。

## 运维备忘

- **日志**：journalctl（systemd）或 `docker compose logs`；每个 `/api` 请求有一行摘要日志
- **升级**：替换 jar 后 `systemctl restart codekg`；Compose 方式 `up -d --build`。图谱数据在 Neo4j 中，升级不丢
- **大仓库分析**：后台任务（MCP `analyze_project`）单线程串行；HTTP 模式是同步长请求，反代需放宽超时
- **备份**：Neo4j 数据卷定期备份即可（图谱可随时由源码重建，备份主要是省时间）
- **服务器上分析代码**：Git URL 模式会用服务器本机的 git 凭证（ssh key 等）；私有仓库需先在服务器配好凭证
