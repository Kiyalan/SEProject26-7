"""多仓库总览：聚合 GitHub 原生元数据 + 本地知识库索引状态。"""

import json
from collections import Counter

from db import get_connection, init_db


async def github_get(path: str, token: str, params: dict | None = None) -> object:
    import httpx

    async with httpx.AsyncClient(timeout=60.0) as client:
        response = await client.get(
            f"https://api.github.com{path}",
            headers={
                "Authorization": f"Bearer {token}",
                "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28",
            },
            params=params,
        )
    if response.status_code >= 400:
        raise RuntimeError(response.text)
    return response.json()


def _local_index_map() -> dict[str, dict]:
    init_db()
    with get_connection() as conn:
        rows = conn.execute(
            """
            SELECT repo_id, full_name, status, indexed_at, file_count, chunk_count,
                   commit_sha, languages
            FROM repo_index
            """
        ).fetchall()
        commit_counts = {
            row["repo_id"]: row["c"]
            for row in conn.execute(
                "SELECT repo_id, COUNT(*) AS c FROM repo_commits GROUP BY repo_id"
            ).fetchall()
        }
    result: dict[str, dict] = {}
    for row in rows:
        try:
            langs = json.loads(row["languages"] or "{}")
        except json.JSONDecodeError:
            langs = {}
        result[row["repo_id"]] = {
            "indexed": row["status"] == "ready",
            "indexedAt": row["indexed_at"] or "",
            "fileCount": row["file_count"] or 0,
            "chunkCount": row["chunk_count"] or 0,
            "commitSha": row["commit_sha"] or "",
            "languages": langs,
            "commitSnapshots": commit_counts.get(row["repo_id"], 0),
        }
    return result


async def build_portfolio_overview(token: str, max_repos: int = 50) -> dict:
    repos = await github_get(
        "/user/repos",
        token,
        {
            "visibility": "all",
            "affiliation": "owner,collaborator,organization_member",
            "sort": "updated",
            "per_page": min(max_repos, 100),
            "page": 1,
        },
    )
    if not isinstance(repos, list):
        raise RuntimeError("GitHub 返回格式异常")

    local = _local_index_map()
    items: list[dict] = []
    lang_counter: Counter[str] = Counter()
    total_stars = 0
    total_open_issues = 0
    indexed_count = 0
    total_files = 0
    total_chunks = 0

    for repo in repos[:max_repos]:
        repo_id = str(repo["id"])
        primary_lang = repo.get("language") or "Unknown"
        lang_counter[primary_lang] += 1
        stars = repo.get("stargazers_count", 0) or 0
        open_issues = repo.get("open_issues_count", 0) or 0
        total_stars += stars
        total_open_issues += open_issues

        local_info = local.get(repo_id, {})
        if local_info.get("indexed"):
            indexed_count += 1
            total_files += local_info.get("fileCount", 0)
            total_chunks += local_info.get("chunkCount", 0)

        pushed = (repo.get("pushed_at") or "")[:10]
        updated = (repo.get("updated_at") or "")[:10]

        items.append(
            {
                "repoId": repo_id,
                "fullName": repo["full_name"],
                "description": repo.get("description") or "",
                "language": primary_lang,
                "stars": stars,
                "openIssues": open_issues,
                "sizeKb": repo.get("size", 0),
                "private": repo.get("private", False),
                "pushedAt": pushed,
                "updatedAt": updated,
                "htmlUrl": repo.get("html_url", ""),
                "topics": repo.get("topics") or [],
                "knowledge": {
                    "indexed": local_info.get("indexed", False),
                    "indexedAt": local_info.get("indexedAt", ""),
                    "fileCount": local_info.get("fileCount", 0),
                    "chunkCount": local_info.get("chunkCount", 0),
                    "commitSnapshots": local_info.get("commitSnapshots", 0),
                },
            }
        )

    # 按最近 push 排序
    items.sort(key=lambda x: x["pushedAt"], reverse=True)

    # 语言分布（主语言维度）
    lang_total = sum(lang_counter.values()) or 1
    languageBreakdown = [
        {"language": lang, "count": count, "percent": round(count / lang_total * 100, 1)}
        for lang, count in lang_counter.most_common(12)
    ]

    # 技术栈聚类（简单规则）
    clusters: dict[str, list[str]] = {
        "TypeScript/JavaScript": [],
        "Python": [],
        "Other": [],
    }
    for item in items:
        lang = item["language"]
        if lang in ("TypeScript", "JavaScript"):
            clusters["TypeScript/JavaScript"].append(item["fullName"])
        elif lang == "Python":
            clusters["Python"].append(item["fullName"])
        else:
            clusters["Other"].append(item["fullName"])

    return {
        "summary": {
            "repoCount": len(items),
            "indexedCount": indexed_count,
            "indexRate": round(indexed_count / len(items) * 100, 1) if items else 0,
            "totalStars": total_stars,
            "totalOpenIssues": total_open_issues,
            "totalIndexedFiles": total_files,
            "totalChunks": total_chunks,
        },
        "languageBreakdown": languageBreakdown,
        "clusters": {k: v[:8] for k, v in clusters.items() if v},
        "timeline": [
            {"fullName": i["fullName"], "pushedAt": i["pushedAt"], "indexedAt": i["knowledge"]["indexedAt"]}
            for i in items[:15]
        ],
        "repos": items,
        "notes": [
            "语言/Star/Issue 来自 GitHub API，无需 LLM",
            "索引状态来自本地 repo_index，仅已构建知识库的仓库有 fileCount/chunkCount",
            "后续可在此面板加入 LLM 生成「账户仓库组合一句话纵览」",
        ],
    }
