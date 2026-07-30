# SEProject26-7 / RepoPilot

RepoPilot：GitHub OAuth、知识库（CodeWiki AST + GraphRAG）、智能问答、Issue 分析与仓库操作。

**新人本地部署**：请先阅读 [`LOCAL_SETUP.md`](LOCAL_SETUP.md)（环境安装 → 配置 `.env` → 一键启动）。

知识库概览会显示本地估算的 **代码行数**（`lineCount`）。GitHub 原生不提供仓库总 LOC，仅有 Linguist 语言占比（按字节）；本系统在同步后的工作区统计源码行数（排除 `node_modules`/`target` 等）。

## Docker 是做什么的？

Docker 只跑**服务端知识引擎**，不是给浏览器用户装的客户端：

| 容器 | 作用 |
|------|------|
| `postgres`（pgvector） | CodeWiki 的图、chunk、可选向量索引存储 |
| `codewiki`（Python 3.12） | AST 分析、GraphRAG 检索、按需 Wiki |

浏览器用户只打开前端页面，**不需要安装 Docker**。  
只有**部署/开发本机**（跑 Spring Boot 后端的那台机器）需要 Docker，因为后端会调用本机 `127.0.0.1:8001` 上的 CodeWiki。

没有 Docker / CodeWiki 时：登录、仓库列表、Issue 列表等仍可用；**构建知识库、GraphRAG 问答、Wiki** 会失败。

## 本地启动（推荐顺序）

### 1. 启动 CodeWiki + PostgreSQL

```bash
docker compose up -d postgres codewiki
docker compose ps
```

健康后：<http://127.0.0.1:8001/api/health> 应返回 `{"status":"ok"}`。

可选：在仓库根目录 `.env` 配置 CodeWiki LLM（Wiki / 向量需要）：

```dotenv
CODEWIKI_LLM__DEFAULT__MODEL=openai/gpt-4.1
CODEWIKI_LLM__DEFAULT__API_KEY=sk-...
CODEWIKI_LLM__PROFILES__EMBEDDING__MODEL=openai/text-embedding-3-small
CODEWIKI_LLM__PROFILES__EMBEDDING__API_KEY=sk-...
CODEWIKI_INCLUDE_EMBEDDINGS=true
```

`CODEWIKI_INCLUDE_EMBEDDINGS` 默认 `true`（构建主体会走 embedding LLM）。若只想要 AST 图、不要向量，可设为 `false`。

### CodeWiki 构建稳定性（大仓库）

若构建过程中 CodeWiki「隔一段时间就停」或扫到一定文件后卡住，常见原因是：

1. **健康检查过严**：分析占满 CPU 时 `/api/health` 短暂无响应 → 容器被判 unhealthy / 重启。  
2. **僵尸分析任务**：容器重启后 Postgres 里仍留着 `status=running` 的 run，之后每次 `analyze` 都会复用它，进度永远不动。

本仓库已做的缓解：

- 放宽 `docker-compose.yml` / Dockerfile 健康检查（60s 间隔、30s 超时、更长 start_period）
- `services/codewiki/entrypoint.py` 启动时清理陈旧 `running` 任务，并附加大目录排除规则
- 后端构建前检测僵尸 run，必要时删除并重新注册后全量分析
- 默认 **开启 embedding**（`CODEWIKI_INCLUDE_EMBEDDINGS=true`），构建会调用 embedding LLM；大仓库耗时与费用会明显上升，可临时设为 `false` 回退到纯图索引
- 构建还会**同步**调用社区 LLM 命名（`/communities/name`）；失败则整次构建失败，不再静默跳过

重新部署 CodeWiki：

```powershell
docker compose up -d --build postgres codewiki
```

Docker Desktop 建议给 WSL2 至少 **4GB 内存**；构建大仓时不要手动 `restart` codewiki。

### 2. 启动后端

```powershell
cd backend
.\run.ps1
```

`run.ps1` 会：结束占用 8000 的旧 RepoPilot 进程 → 检查 CodeWiki 健康 → `.\mvnw.cmd package`（Maven Wrapper，无需本机安装 Maven）→ 启动最新 JAR。

### 3. 启动前端

```powershell
cd frontend
npm install
npm run dev
```

打开 <http://localhost:5173>。

也可用根目录一键脚本（需已安装 Docker / JDK 21+ / Node；后端通过 `backend/mvnw` 自动下载 Maven）：

```powershell
.\start-dev.ps1
```

## 架构简述

```text
浏览器 → Vite 前端(:5173) → Spring Boot(:8000)
                         ├─ GitHub OAuth / API
                         ├─ H2：任务、质量、Issue 缓存、仓库投影
                         └─ CodeWiki(:8001) + PostgreSQL/pgvector
                              （JGit 把仓库同步到 backend/data/repos 后只读挂载）
```

- 公开 API 仍由 Spring Boot 提供，契约在 [`contract/openapi.json`](contract/openapi.json)。
- 知识构建只索引当前 **HEAD**；历史 commit 用 JGit log/diff，不做逐 commit GraphRAG。
- Wiki **按需**生成，不在每次 build 时自动全量跑。

## 许可证与限制

CodeWiki 0.6.5 为 MIT、Alpha、单用户单实例，适合本地/答辩演示，不宜直接公网多租户部署。
