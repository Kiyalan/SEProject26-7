# SEProject26-7 / RepoPilot

RepoPilot：GitHub OAuth、知识库（CodeWiki AST + GraphRAG）、智能问答、Issue 分析与仓库操作。

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

`CODEWIKI_INCLUDE_EMBEDDINGS` 默认 `false`。未配 embedding 时 GraphRAG 仍可用 AST 图 + 全文 + 图扩展。

### 2. 启动后端

```powershell
cd backend
.\run.ps1
```

`run.ps1` 会：结束占用 8000 的旧 RepoPilot 进程 → 检查 CodeWiki 健康 → `mvn package` → 启动最新 JAR。

### 3. 启动前端

```powershell
cd frontend
npm install
npm run dev
```

打开 <http://localhost:5173>。

也可用根目录一键脚本（需已安装 Docker / Java / Node）：

```powershell
.\start-dev.ps1
```

## 架构简述

```text
浏览器 → Vite 前端 → Spring Boot(:8000)
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
