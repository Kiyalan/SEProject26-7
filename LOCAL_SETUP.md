# 本地快速部署指南

面向新人：从 GitHub 拿到代码后，按本文即可在本机跑起 RepoPilot。

> 推荐系统：**Windows 10/11** + PowerShell。  
> macOS / Linux 也可，见文末「非 Windows」。

---

## 1. 需要安装的软件

| 软件 | 版本建议 | 用途 | 检查命令 |
|------|----------|------|----------|
| Git | 任意较新版 | 拉取代码 | `git --version` |
| Docker Desktop | 最新稳定版 | CodeWiki + Postgres | `docker version` |
| JDK | **21** | 后端 | `java -version` |
| Maven | 3.9+ | 编译后端 | `mvn -version` |
| Node.js | **18+**（建议 LTS） | 前端 | `node -v` / `npm -v` |

说明：

- **Docker 必须装且保持运行**，否则无法构建知识库、GraphRAG 问答、Wiki。
- Docker Desktop 建议给 WSL2 / 虚拟机至少 **4GB 内存**。
- 本地开发默认用 **H2** 文件库，**不必**单独安装 MySQL。

---

## 2. 获取代码

```powershell
git clone <仓库 HTTPS 或 SSH 地址>
cd SEProject26-7
```

建议检出当前开发分支（以组内约定为准），例如：

```powershell
git checkout feature/cloud
git pull
```

---

## 3. 配置环境变量（必做）

```powershell
cd backend
copy .env.example .env
```

用编辑器打开 `backend\.env`，按下面填写。

### 3.1 最少必填（能登录、看仓库）

| 变量 | 说明 |
|------|------|
| `GITHUB_CLIENT_ID` | GitHub OAuth App 的 Client ID |
| `GITHUB_CLIENT_SECRET` | GitHub OAuth App 的 Client Secret |
| `GITHUB_CALLBACK_URL` | 本地固定为 `http://localhost:5173/auth/callback` |
| `FRONTEND_URL` | 本地固定为 `http://localhost:5173` |
| `JWT_SECRET` | 任意一串随机字符（例如 32 位以上） |

### 3.2 问答 / FAQ 建议填写

| 变量 | 说明 |
|------|------|
| `LLM_API_KEY` | 如 OpenRouter API Key |
| `LLM_BASE_URL` | 默认 `https://openrouter.ai/api/v1` |
| `LLM_MODEL` | 默认可用 `openai/gpt-oss-20b:free`（以实际可用模型为准） |

> 组内若有现成的 `backend\.env`，直接拷贝最快。  
> **不要**把含真实密钥的 `.env` 提交到 Git。

### 3.3 自己创建 GitHub OAuth App（若组里没有共用）

1. 打开 [GitHub Developer Settings → OAuth Apps](https://github.com/settings/developers) → **New OAuth App**
2. **Homepage URL**：`http://localhost:5173`
3. **Authorization callback URL**：`http://localhost:5173/auth/callback`
4. 创建后把 Client ID / Secret 写入 `backend\.env`

---

## 4. 一键启动（推荐）

确认 **Docker Desktop 已启动**，在仓库根目录执行：

```powershell
.\start-dev.ps1
```

脚本会：

1. 加载 `backend\.env`
2. 释放占用的 `8000` / `5173` 端口
3. 启动（或复用已健康的）`postgres` + `codewiki`
4. 新开窗口启动后端（`backend\run.ps1`）
5. 新开窗口启动前端（`npm install` + `npm run dev`）

启动成功后访问：

| 服务 | 地址 |
|------|------|
| 前端（打开这个） | http://localhost:5173 |
| 后端 API | http://localhost:8000 |
| CodeWiki 健康检查 | http://127.0.0.1:8001/api/health |

健康检查应返回类似：`{"status":"ok"}`。

在前端用 **GitHub 登录** 即可。

---

## 5. 分步启动（排查问题时用）

### 5.1 CodeWiki + Postgres

```powershell
# 在仓库根目录
docker compose up -d --build postgres codewiki
docker compose ps
```

首次构建镜像可能较慢，请耐心等待。确认：

```powershell
# 浏览器或
Invoke-RestMethod http://127.0.0.1:8001/api/health
```

### 5.2 后端

```powershell
cd backend
.\run.ps1
```

会执行 `mvn package` 并启动 `http://localhost:8000`。

### 5.3 前端

```powershell
cd frontend
npm install
npm run dev
```

打开 http://localhost:5173 。

---

## 6. 功能可用性对照

| 能力 | 需要什么 |
|------|----------|
| 打开页面、静态浏览 | 前端 |
| GitHub 登录、仓库列表、Issue | 后端 + 正确的 OAuth |
| 构建知识库 / 智能问答 / Wiki | Docker（CodeWiki）+ 后端 |
| 问答质量较好 | 有效的 `LLM_API_KEY` |

没有 Docker 时：登录和仓库相关功能可能仍可用，但**知识库构建与 GraphRAG 问答会失败**。

---

## 7. 常见问题

### Docker 未启动 / compose 失败

- 先打开 Docker Desktop，等待引擎 Ready。
- 再执行：`docker compose up -d --build postgres codewiki`

### CodeWiki 长时间 unhealthy

```powershell
docker compose logs -f codewiki
```

首次 `build` 失败可重试；内存过小请调高 Docker 内存限制。

### 端口被占用（8000 / 5173 / 8001）

- 再跑一次 `.\start-dev.ps1`（会尝试释放 8000/5173）。
- 或手动结束占用进程后重试。

### 登录后跳转失败 / OAuth 报错

- 检查 Callback 是否为 `http://localhost:5173/auth/callback`（前后端、GitHub App 三处一致）。
- 确认 `GITHUB_CLIENT_ID` / `SECRET` 无多余空格、未用错应用。

### 问答提示未配置模型 / 请求失败

- 检查 `LLM_API_KEY`、`LLM_BASE_URL`、`LLM_MODEL`。
- 也可在前端「设置 → LLM」里填写（会写入本地 `backend/data/llm-config.json`）。

### 知识库构建中途卡住或中断

构建进行中请遵守：

- **不要**再次运行 `.\start-dev.ps1`
- **不要**随便 `docker compose up` / `restart` codewiki

需要重建 CodeWiki 镜像时（仅在无人构建时）：

```powershell
docker compose up -d --build postgres codewiki
```

---

## 8. 日常开发提示

- 本地业务数据在 `backend/data/`（H2、仓库克隆等），一般勿提交。
- 修改后端 Java 后，在后端窗口 `Ctrl+C`，再执行 `.\run.ps1`。
- 修改前端后，Vite 通常热更新；大改可重启 `npm run dev`。
- 知识图谱主要索引**默认分支工作区**；分支列表等问题由后端 Git 元数据辅助回答。

---

## 9. 非 Windows（简要）

1. 安装同等依赖：Docker、JDK 21、Maven、Node 18+。
2. 配置 `backend/.env`（同上）。
3. 启动知识引擎：

   ```bash
   docker compose up -d --build postgres codewiki
   ```

4. 后端：

   ```bash
   cd backend
   mvn -q package -DskipTests
   java -jar target/repopilot-backend-1.0.0.jar
   ```

5. 前端：

   ```bash
   cd frontend
   npm install
   npm run dev
   ```

`start-dev.ps1` / `run.ps1` 为 PowerShell 脚本；在 macOS/Linux 请用上面的手动步骤（或自行改写成 shell）。

---

## 10. 5 分钟检查清单

- [ ] Docker Desktop 已运行  
- [ ] 已安装 JDK 21、Maven、Node  
- [ ] 已 `copy backend\.env.example backend\.env` 并填好 OAuth（及建议的 LLM）  
- [ ] 根目录执行 `.\start-dev.ps1`  
- [ ] http://127.0.0.1:8001/api/health 返回 ok  
- [ ] http://localhost:5173 可打开并用 GitHub 登录  

完成以上即可开始本地开发与演示。
