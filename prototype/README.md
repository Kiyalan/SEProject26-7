# RepoPilot

GitHub 仓库问答与 Issue 分析系统（小学期课题 #A8）

第 7 组小学期项目 · 组长：林圣

## 技术栈

- 前端：React + TypeScript + Vite + Ant Design
- 后端：Python FastAPI + GitHub OAuth + [uv](https://docs.astral.sh/uv/)

## 本地运行

### 0. 安装 uv（首次）

```bash
# Windows (PowerShell)
powershell -ExecutionPolicy ByPass -c "irm https://astral.sh/uv/install.ps1 | iex"
```

安装后重新打开终端，执行 `uv --version` 确认可用。

### 1. 安装依赖

```bash
npm install
npm run setup:backend
```

`setup:backend` 会在 `backend/.venv` 创建虚拟环境并安装 Python 依赖。

### 2. 配置环境变量

复制 `backend/.env.example` 为 `backend/.env`，填写 GitHub OAuth App 的 Client ID 和 Secret。

### 3. 启动

```bash
# 终端 1：后端
npm run dev:backend

# 终端 2：前端
npm run dev
```

浏览器打开 http://localhost:5173

## 知识库与智能问答

1. 进入「知识库」页，选择仓库并点击 **构建索引**
2. 进入「智能问答」页，针对已索引仓库提问

### LLM 配置（OpenRouter，推荐）

在 `backend/.env` 中配置（复制 `backend/.env.example` 为 `backend/.env` 后填入，**勿将真实 Key 提交到 Git**）：

```env
# 在 https://openrouter.ai/keys 创建后填入：
LLM_API_KEY=
LLM_BASE_URL=https://openrouter.ai/api/v1
LLM_MODEL=tencent/hy3:free
```

| 模式 | 条件 | 效果 |
|------|------|------|
| **检索摘要** | `LLM_API_KEY` 为空 | 关键词检索 + 片段摘要 |
| **LLM 增强** | 填入 OpenRouter Key | 问答与 Issue 分析使用 `tencent/hy3:free` |

配置后顶栏显示绿色 **LLM** 标签；修改 `.env` 后需重启后端。

## 协作说明

- `main`：稳定版本
- `develop`：日常开发合并分支
- 功能开发请从 `develop` 拉 `feature/xxx` 分支，完成后提 Pull Request

## 文档

见 `docs/` 目录（Vision、用例模型、迭代计划）。
