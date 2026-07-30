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

构建知识库 / GraphRAG 查询的详细过程日志写在容器内（不推到前后端）：

```bash
docker compose exec codewiki ls /app/storage/logs
docker compose exec codewiki tail -n 50 /app/storage/logs/query-$(date +%Y%m%d).jsonl
docker compose logs codewiki --since 10m 2>&1 | findstr codewiki-ops
```

可用环境变量 `CODEWIKI_OPS_TRACE=0` 关闭；`CODEWIKI_OPS_TRACE_CONTENT_CHARS` 控制正文截断长度。

可选：在仓库根目录 `.env` 配置 CodeWiki LLM（**标准 GraphRAG 构建必填**：社区命名 + 实体向量）：

```dotenv
CODEWIKI_LLM__MODE=sdk
CODEWIKI_LLM__DEFAULT__MODEL=openrouter/deepseek/deepseek-v4-flash
CODEWIKI_LLM__DEFAULT__ENDPOINT=https://openrouter.ai/api/v1
CODEWIKI_LLM__DEFAULT__API_KEY=sk-or-...
CODEWIKI_LLM__PROFILES__EMBEDDING__MODEL=openrouter/qwen/qwen3-embedding-4b
CODEWIKI_LLM__PROFILES__EMBEDDING__ENDPOINT=https://openrouter.ai/api/v1
CODEWIKI_LLM__PROFILES__EMBEDDING__API_KEY=sk-or-...
CODEWIKI_INCLUDE_EMBEDDINGS=true
CODEWIKI_PGVECTOR_HALFVEC=auto
```

可从根目录 `.env.example` 复制。**Embedding 的 API Key 不要留空。** 模型名须带 `openrouter/` 前缀（CodeWiki/LiteLLM 在 model 含 `/` 时会忽略 `PROVIDER_TYPE`）。配置后执行 `docker compose up -d --build codewiki`。

默认推荐 **Qwen3-Embedding-4B（2560 维）**。pgvector 的 `vector` HNSW 上限 2000 维，本仓库在 `dims>2000` 时自动改用 **`halfvec` + HNSW**（上限 4000）。勿用 8B（4096，超出 halfvec HNSW）。也可用 `openrouter/openai/text-embedding-3-small`（1536）。

未配置时会出现 `provider/strong-coding-model`；Embedding 配成裸 `openai/...` 时可能出现 OpenAI `403 Terms Of Service`。`CODEWIKI_INCLUDE_EMBEDDINGS` 默认 **`true`**。

### CodeWiki 构建稳定性（大仓库）

若构建过程中 CodeWiki「隔一段时间就停」或扫到一定文件后卡住，常见原因是：

1. **健康检查过严**：分析占满 CPU 时 `/api/health` 短暂无响应 → 容器被判 unhealthy / 重启。  
2. **僵尸分析任务**：容器重启后 Postgres 里仍留着 `status=running` 的 run，之后每次 `analyze` 都会复用它，进度永远不动。

本仓库已做的缓解：

- 放宽 `docker-compose.yml` / Dockerfile 健康检查（60s 间隔、30s 超时、更长 start_period）
- `services/codewiki/entrypoint.py` 启动时清理陈旧 `running` 任务，并附加大目录排除规则
- 后端构建前检测僵尸 run，必要时删除并重新注册后全量分析
- 标准 GraphRAG 默认开启实体 embedding（`CODEWIKI_INCLUDE_EMBEDDINGS=true`）；须配置 `CODEWIKI_LLM__PROFILES__EMBEDDING__*`，否则构建会回退或失败

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
# Windows
.\start-dev.ps1
```

```bash
# Linux
chmod +x start-dev.sh backend/run.sh backend/mvnw
./start-dev.sh
# 无 GUI 时后端/前端在后台跑，日志：logs/backend.log、logs/frontend.log
# 确认后端：curl -sI http://127.0.0.1:8000/auth/github   # 期望 302
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
