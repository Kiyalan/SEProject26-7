"""
知识库索引策略：明确「必须索引 / 可选 / 不索引」及与各功能模块的关系。
"""

# 为 RAG 问答、Issue 关联文件检索所必需
REQUIRED_PATH_PATTERNS = [
    "readme",
    "package.json",
    "requirements.txt",
    "pyproject.toml",
    "src/",
    "app/",
    "backend/",
    "docs/",
]

# 建议索引以提升 What/How 回答质量
RECOMMENDED_PATTERNS = [
    "*.md",
    "vite.config",
    "tsconfig",
    "dockerfile",
    ".env.example",
    "main.py",
    "main.ts",
    "main.tsx",
]

# 永不索引（体积大、噪声高、无助于课题功能）
EXCLUDED_DIRS = {
    "node_modules",
    ".git",
    "dist",
    "build",
    ".next",
    "__pycache__",
    ".venv",
    "venv",
    "coverage",
    ".idea",
    ".vscode",
    "vendor",
    "target",
}

# 仅存数据库、不在知识库页完整展示
STORE_ONLY = [
    "chunk_blobs（全文分块，供 RAG / Issue 检索）",
    "content_blobs（去重后的完整文件内容）",
    "commit_chunks / commit_files（按 commit 的历史引用）",
]

# 页面展示，不参与向量/关键词检索
DISPLAY_ONLY = [
    "languages 统计",
    "dependencies 标签",
    "modules 概览",
    "commit 时间线",
    "storageModel 说明",
]

FEATURE_MATRIX = {
    "智能问答 (UC-03)": {
        "needs": ["README", "入口与路由代码", "配置与依赖说明", "chunk_blobs"],
        "not_needed": ["图片/二进制", "lock 文件全文", "测试快照"],
    },
    "Issue 分析 (UC-04)": {
        "needs": ["与 Issue 关键词匹配的源码片段", "README/文档", "chunk_blobs"],
        "not_needed": ["完整 git 历史", "无关第三方 vendor"],
    },
    "历史对比": {
        "needs": ["commit_files 按 commit 快照", "content_blobs 去重"],
        "not_needed": ["chunk 级对比 UI（文件级已足够）"],
    },
    "自动 PR（阶段三）": {
        "needs": ["相关源文件全文 blob", "Issue 正文", "依赖配置"],
        "not_needed": ["已编译产物", "minified 资源"],
    },
}


# GitHub 已归纳 / 可直接 API 提取，无需 LLM 再总结
GITHUB_NATIVE = {
    "repo_metadata": [
        "name, description, topics, license, default_branch, visibility",
        "stargazers_count, forks_count, open_issues_count, size",
        "created_at, updated_at, pushed_at",
    ],
    "language_stats": [
        "GET /repos/{owner}/{repo}/languages — 各语言字节占比（与 GitHub 语言条一致）",
    ],
    "commits": [
        "sha, message, author, committed_at, parent — commit 时间线",
    ],
    "issues_prs": [
        "title, body, labels, state, comments — Issue/PR 元数据（非知识库索引主体）",
    ],
    "dependency_hints": [
        "package.json / requirements.txt 原文可拉取，依赖名列表可规则解析",
    ],
}

# 需拉取原文，但用规则处理即可（不必 LLM）
EXTRACT_AND_PARSE = {
    "items": [
        "README / docs 原文（截断预览）",
        "目录树（git tree API）",
        "配置文件全文（package.json, pyproject.toml 等）",
        "源码文件分块（chunk_blobs，供关键词/向量检索）",
        "模块划分（按顶层目录聚合）",
        "依赖列表（JSON/文本解析）",
    ],
    "reason": "GitHub 不提供「项目如何运行」的语义答案，需保留原文供 RAG 检索",
}

# 需摘要/归纳；规则可生成初稿
SUMMARIZE_RULES = {
    "items": [
        "仓库一句话摘要（README 首段 + 模块名拼接）",
        "知识库索引状态（文件数、chunk 数、最近 commit）",
        "commit 间文件 diff 统计（增删改数量）",
    ],
    "reason": "展示用短文本，避免把全文塞进 UI",
}

# 建议 LLM 归纳（可选，API Key 配置后）
LLM_SYNTHESIS = {
    "items": [
        "跨文件的项目定位（What）与自然语言问答回答",
        "Issue 类型判断 + 建议回复（结合知识库片段）",
        "（规划）单仓库技术栈与架构一段话总结",
        "（规划）多仓库组合视角：用户所有项目的分工与演进叙述",
    ],
    "reason": "需要跨片段推理，规则模板质量有限",
}

MULTI_REPO_PORTFOLIO = {
    "user_need": [
        "同一账户下多仓库的语言/规模/活跃度对比",
        "按时间线看哪些项目在近期有 push / 索引更新",
        "发现「相似技术栈」或「重复造轮子」的仓库对",
        "仪表盘纵览：总 Star、总 Open Issue、已索引比例",
    ],
    "data_already_available": [
        "各 repo 的 GitHub 原生元数据（无需重建知识库）",
        "本地 repo_index / repo_commits 的 indexed_at、file_count、languages",
    ],
    "difficulty": {
        "dashboard_basic": "低（1–2 天）：聚合 /api/repos + repo_index 表做表格与条形图",
        "cross_repo_compare": "中（3–5 天）：语言分布对比、规模排序、最近活跃时间线",
        "semantic_linking": "高（1–2 周+）：需 embedding 或 LLM 判断「项目 A 与 B 是否同类/可复用」",
        "llm_portfolio_summary": "中（2–3 天）：把所有仓库 README 摘要拼成 prompt，生成一段话纵览",
    },
}


def policy_overview() -> dict:
    return {
        "required": REQUIRED_PATH_PATTERNS,
        "recommended": RECOMMENDED_PATTERNS,
        "excludedDirs": sorted(EXCLUDED_DIRS),
        "storeOnly": STORE_ONLY,
        "displayOnly": DISPLAY_ONLY,
        "featureMatrix": FEATURE_MATRIX,
        "dataSourceTiers": {
            "githubNative": GITHUB_NATIVE,
            "extractAndParse": EXTRACT_AND_PARSE,
            "summarizeRules": SUMMARIZE_RULES,
            "llmSynthesis": LLM_SYNTHESIS,
        },
        "multiRepoPortfolio": MULTI_REPO_PORTFOLIO,
        "limits": {
            "maxFilesPerCommit": 180,
            "maxFileBytes": 80_000,
            "chunkSize": 900,
        },
    }
