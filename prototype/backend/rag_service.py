import re

from db import get_connection, init_db
from knowledge_service import _legacy_has_data, _resolve_commit_sha

STOP_WORDS = {
    "the",
    "and",
    "for",
    "with",
    "this",
    "that",
    "什么",
    "如何",
    "怎么",
    "哪里",
    "在哪",
    "是什么",
    "请问",
    "项目",
    "代码",
}

QUERY_SYNONYMS = {
    "启动": ["run", "dev", "start", "scripts", "serve", "uvicorn", "npm"],
    "运行": ["run", "dev", "start", "scripts", "serve", "uvicorn", "npm"],
    "依赖": ["dependencies", "requirements", "package.json", "requirements.txt"],
    "路由": ["router", "routes", "react-router", "route"],
    "入口": ["main", "app", "index"],
    "配置": ["config", "settings", "env", "vite.config", "tsconfig"],
    "接口": ["api", "endpoint", "fetch", "request"],
    "issue": ["issue", "issues", "bug", "question"],
}


def classify_question(question: str) -> str:
    q = question.lower()
    if re.search(r"什么|是什么|what", q):
        return "what"
    if re.search(r"哪里|在哪|where|路径|文件", q):
        return "where"
    if re.search(r"如何|怎么|怎样|how", q):
        return "how"
    return "what"


def tokenize(question: str) -> list[str]:
    parts = re.findall(r"[a-zA-Z_][a-zA-Z0-9_./-]*|[\u4e00-\u9fff]{2,}", question.lower())
    tokens = [p for p in parts if p not in STOP_WORDS and len(p) > 1]
    expanded = list(tokens)
    for token in tokens:
        expanded.extend(QUERY_SYNONYMS.get(token, []))
    return list(dict.fromkeys(expanded))


def retrieve_chunks(repo_id: str, question: str, limit: int = 5, commit_sha: str | None = None) -> list[dict]:
    init_db()
    tokens = tokenize(question)
    if not tokens:
        return []

    resolved = _resolve_commit_sha(repo_id, commit_sha)

    with get_connection() as conn:
        if resolved:
            rows = conn.execute(
                """
                SELECT cc.file_path, cc.chunk_index, cc.start_line, cb.content
                FROM commit_chunks cc
                JOIN chunk_blobs cb ON cb.hash = cc.chunk_hash
                WHERE cc.repo_id = ? AND cc.commit_sha = ?
                """,
                (repo_id, resolved),
            ).fetchall()
            if not rows and _legacy_has_data(conn, repo_id):
                rows = conn.execute(
                    "SELECT file_path, chunk_index, content, start_line FROM repo_chunks WHERE repo_id = ?",
                    (repo_id,),
                ).fetchall()
        else:
            rows = conn.execute(
                "SELECT file_path, chunk_index, content, start_line FROM repo_chunks WHERE repo_id = ?",
                (repo_id,),
            ).fetchall()

    scored: list[tuple[float, dict]] = []
    for row in rows:
        content_lower = row["content"].lower()
        path_lower = row["file_path"].lower()
        score = 0.0
        if path_lower.endswith(("readme.md", "package.json", "requirements.txt")):
            score += 1.5
        for token in tokens:
            if token in path_lower:
                score += 5.0
            score += min(content_lower.count(token), 6)
        if score > 0:
            scored.append(
                (
                    score,
                    {
                        "file": row["file_path"],
                        "line": row["start_line"],
                        "content": row["content"][:500],
                        "score": score,
                    },
                )
            )

    scored.sort(key=lambda item: item[0], reverse=True)
    return [item[1] for item in scored[:limit]]
