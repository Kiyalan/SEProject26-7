import base64
import difflib
import json
from datetime import datetime, timezone

from content_store import (
    get_content_blob,
    release_commit_refs,
    storage_stats,
    store_chunk_blob,
    store_content_blob,
)
from db import get_connection, init_db
from knowledge_utils import (
    MAX_FILE_BYTES,
    MAX_FILES,
    FileRecord,
    build_tree,
    chunk_text,
    detect_language,
    extract_dependencies,
    extract_language_stats,
    extract_modules,
    extract_repo_summary,
    file_priority,
    is_text_file,
    should_skip_path,
)


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


async def fetch_file_at_ref(full_name: str, path: str, ref: str, token: str) -> str | None:
    data = await github_get(f"/repos/{full_name}/contents/{path}", token, {"ref": ref})
    if not isinstance(data, dict):
        return None
    if data.get("encoding") == "base64" and data.get("content"):
        raw = base64.b64decode(data["content"]).decode("utf-8", errors="ignore")
        return raw[:MAX_FILE_BYTES]
    return None


async def fetch_commits(full_name: str, token: str, max_commits: int) -> list[dict]:
    data = await github_get(
        f"/repos/{full_name}/commits",
        token,
        {"per_page": min(max_commits, 100), "sha": "HEAD"},
    )
    if not isinstance(data, list):
        return []
    return data[:max_commits]


async def fetch_changed_files(
    full_name: str, base_sha: str, head_sha: str, token: str
) -> set[str]:
    """利用 GitHub Compare API 获取 base..head 之间变更的文件列表。
    
    GitHub 的 Compare API 直接返回两个 commit 之间所有变化的文件路径，
    这比全量拉取 tree 再比对高效得多：
    - 只产生若干 API 调用
    - 未变化的文件无需重复拉取内容
    """
    try:
        data = await github_get(
            f"/repos/{full_name}/compare/{base_sha}...{head_sha}",
            token,
        )
    except RuntimeError:
        return set()  # 比较跨度太大时 GitHub 可能拒绝，回退到全量模式

    files = data.get("files", []) if isinstance(data, dict) else []
    changed: set[str] = set()
    for f_item in files:
        if not isinstance(f_item, dict):
            continue
        path = f_item.get("filename", "")
        if path:
            changed.add(path)
    return changed


def _copy_unchanged_files_from_parent(
    conn, repo_id: str, commit_sha: str, parent_sha: str, exclude_paths: set[str]
) -> tuple[int, int]:
    """将 parent commit 中未变化的文件/块引用复制到当前 commit。
    
    返回 (复制的文件数, 复制的块数)。
    这是增量索引的核心：只对变化的文件重新拉取内容，其余直接复用父 commit 的 blob 哈希。
    """
    file_count = 0
    chunk_count = 0

    # 复制未变更文件的 file 记录
    for row in conn.execute(
        """
        SELECT path, blob_hash, file_type, size, language
        FROM commit_files
        WHERE repo_id = ? AND commit_sha = ? AND path NOT IN (
            SELECT value FROM json_each(?)
        )
        """,
        (repo_id, parent_sha, json.dumps(list(exclude_paths))),
    ).fetchall():
        conn.execute(
            """
            INSERT OR REPLACE INTO commit_files
            (repo_id, commit_sha, path, blob_hash, file_type, size, language)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            (repo_id, commit_sha, row["path"], row["blob_hash"], row["file_type"], row["size"], row["language"]),
        )
        file_count += 1

    # 复制未变更文件的 chunk 记录（blob 引用，不复制内容）
    for row in conn.execute(
        """
        SELECT file_path, chunk_index, chunk_hash, start_line
        FROM commit_chunks
        WHERE repo_id = ? AND commit_sha = ? AND file_path NOT IN (
            SELECT value FROM json_each(?)
        )
        """,
        (repo_id, parent_sha, json.dumps(list(exclude_paths))),
    ).fetchall():
        conn.execute(
            """
            INSERT OR REPLACE INTO commit_chunks
            (repo_id, commit_sha, file_path, chunk_index, chunk_hash, start_line)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            (repo_id, commit_sha, row["file_path"], row["chunk_index"], row["chunk_hash"], row["start_line"]),
        )
        chunk_count += 1

    return file_count, chunk_count


def get_index_settings(repo_id: str) -> dict:
    init_db()
    with get_connection() as conn:
        row = conn.execute(
            "SELECT * FROM repo_index_settings WHERE repo_id = ?", (repo_id,)
        ).fetchone()
        index = conn.execute("SELECT active_commit_sha FROM repo_index WHERE repo_id = ?", (repo_id,)).fetchone()
    active = ""
    if index and index["active_commit_sha"]:
        active = index["active_commit_sha"]
    elif row and row["active_commit_sha"]:
        active = row["active_commit_sha"]
    if not row:
        return {"indexEachCommit": False, "maxCommits": 30, "activeCommitSha": active}
    return {
        "indexEachCommit": bool(row["index_each_commit"]),
        "maxCommits": row["max_commits"],
        "activeCommitSha": active or row["active_commit_sha"] or "",
    }


def save_index_settings(repo_id: str, settings: dict) -> dict:
    init_db()
    with get_connection() as conn:
        conn.execute(
            """
            INSERT INTO repo_index_settings (repo_id, index_each_commit, max_commits, active_commit_sha)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(repo_id) DO UPDATE SET
                index_each_commit = excluded.index_each_commit,
                max_commits = excluded.max_commits,
                active_commit_sha = excluded.active_commit_sha
            """,
            (
                repo_id,
                1 if settings.get("indexEachCommit") else 0,
                settings.get("maxCommits", 30),
                settings.get("activeCommitSha", ""),
            ),
        )
        if settings.get("activeCommitSha"):
            conn.execute(
                "UPDATE repo_index SET active_commit_sha = ? WHERE repo_id = ?",
                (settings["activeCommitSha"], repo_id),
            )
    return get_index_settings(repo_id)


def _nested_get(data: object, *keys: str, default: str = "") -> str:
    cur = data
    for key in keys:
        if not isinstance(cur, dict):
            return default
        cur = cur.get(key)
    return default if cur is None else str(cur)


async def index_commit_snapshot(
    repo_id: str,
    full_name: str,
    commit: dict,
    token: str,
    *,
    parent_sha_for_incremental: str = "",
) -> dict:
    """构建单个 commit 的知识库快照。

    策略：GitHub 每个 commit 保存完整文件树快照，可通过 tree API 获取。
    如果传入了 parent_sha_for_incremental：
      1. 先通过 Compare API 获取变更文件集合
      2. 只拉取变更文件的内容，其余从父 commit 复制 blob 引用
    如果未传入：全量拉取（首次索引）。
    """
    commit_sha = commit["sha"]
    commit_info = commit.get("commit") or {}
    parent_sha = (commit.get("parents") or [{}])[0].get("sha", "")
    author_login = _nested_get(commit, "author", "login") or _nested_get(commit_info, "author", "name")
    committed_at = _nested_get(commit_info, "author", "date")[:19].replace("T", " ")

    effective_parent = parent_sha_for_incremental or parent_sha

    # 尝试增量索引：如果父 commit 已索引且可以比较，只拉变更文件
    incremental = False
    changed_paths: set[str] = set()
    if effective_parent:
        with get_connection() as _conn:
            parent_indexed = _conn.execute(
                "SELECT file_count FROM repo_commits WHERE repo_id = ? AND commit_sha = ?",
                (repo_id, effective_parent),
            ).fetchone()
        if parent_indexed and (parent_indexed["file_count"] or 0) > 0:
            changed_paths = await fetch_changed_files(full_name, effective_parent, commit_sha, token)
            if changed_paths:
                incremental = True

    tree_data = await github_get(
        f"/repos/{full_name}/git/trees/{commit_sha}",
        token,
        {"recursive": "1"},
    )
    tree = tree_data.get("tree", []) if isinstance(tree_data, dict) else []

    tree_size_by_path: dict[str, int] = {}
    candidate_paths: list[str] = []
    for item in tree:
        if item.get("type") != "blob":
            continue
        path = item.get("path", "")
        size = item.get("size", 0) or 0
        if not path or should_skip_path(path):
            continue
        if not is_text_file(path):
            continue
        if size > MAX_FILE_BYTES:
            continue
        tree_size_by_path[path] = size
        candidate_paths.append(path)

    all_selected = sorted(
        candidate_paths,
        key=lambda p: file_priority(p, tree_size_by_path.get(p, 0)),
    )[:MAX_FILES]

    # 增量模式：区分「需要重新拉取」 vs 「可从父 commit 复制」
    fetch_paths: list[str]
    inherit_paths: list[str]
    if incremental and changed_paths:
        changed_set = set(changed_paths)
        fetch_paths = [p for p in all_selected if p in changed_set]
        inherit_paths = [p for p in all_selected if p not in changed_set]
    else:
        fetch_paths = all_selected
        inherit_paths = []

    files: list[FileRecord] = []
    file_map: dict[str, str] = {}

    for path in fetch_paths:
        content = await fetch_file_at_ref(full_name, path, commit_sha, token)
        if content is None:
            continue
        files.append(
            FileRecord(
                path=path,
                file_type="file",
                size=len(content),
                language=detect_language(path),
                content=content,
            )
        )
        file_map[path] = content

    folder_paths = {
        "/".join(path.split("/")[:i])
        for path in all_selected
        for i in range(1, len(path.split("/")))
    }

    chunk_count = 0
    copied_files = 0
    copied_chunks = 0

    with get_connection() as conn:
        release_commit_refs(commit_sha, conn)

        # 增量：从父 commit 复制未变更文件的引用
        if inherit_paths and effective_parent:
            copied_files, copied_chunks = _copy_unchanged_files_from_parent(
                conn, repo_id, commit_sha, effective_parent,
                set(fetch_paths) | {
                    p for p in all_selected
                    for i in range(1, len(p.split("/")))
                },
            )

        # 写入新拉取的文件记录
        for path in sorted(set(fetch_paths) | folder_paths):
            is_file = path in file_map
            if is_file:
                blob_hash = store_content_blob(file_map[path], conn)
                conn.execute(
                    """
                    INSERT OR REPLACE INTO commit_files
                    (repo_id, commit_sha, path, blob_hash, file_type, size, language)
                    VALUES (?, ?, ?, ?, 'file', ?, ?)
                    """,
                    (repo_id, commit_sha, path, blob_hash, len(file_map[path]), detect_language(path)),
                )
            else:
                conn.execute(
                    """
                    INSERT OR IGNORE INTO commit_files
                    (repo_id, commit_sha, path, blob_hash, file_type, size, language)
                    VALUES (?, ?, ?, '', 'folder', 0, NULL)
                    """,
                    (repo_id, commit_sha, path),
                )

        for file in files:
            for chunk in chunk_text(file.content or "", file.path):
                chunk_hash = store_chunk_blob(chunk["content"], conn)
                conn.execute(
                    """
                    INSERT OR REPLACE INTO commit_chunks
                    (repo_id, commit_sha, file_path, chunk_index, chunk_hash, start_line)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    (
                        repo_id,
                        commit_sha,
                        chunk["file_path"],
                        chunk["chunk_index"],
                        chunk_hash,
                        chunk["start_line"],
                    ),
                )
                chunk_count += 1

        modules = extract_modules(files)
        readme_path = next(
            (path for path in file_map if path.lower().rsplit("/", 1)[-1].startswith("readme")),
            "",
        )
        readme_preview = file_map.get(readme_path, "")[:800]

        # 增量模式：summary/languages 需要合并继承文件的信息
        if incremental and effective_parent:
            parent_row = conn.execute(
                "SELECT summary, languages, readme_path, readme_preview FROM repo_commits WHERE repo_id = ? AND commit_sha = ?",
                (repo_id, effective_parent),
            ).fetchone()
            if parent_row:
                parent_langs: dict = {}
                try:
                    parent_langs = json.loads(parent_row["languages"] or "{}")
                except json.JSONDecodeError:
                    pass
                new_langs = extract_language_stats(files) if files else {}
                for lang, count in new_langs.items():
                    parent_langs[lang] = parent_langs.get(lang, 0) + count
                languages = parent_langs
                summary = extract_repo_summary(full_name, file_map, modules) or parent_row["summary"]
                if not readme_path:
                    readme_path = parent_row["readme_path"] or ""
                if not readme_preview:
                    readme_preview = parent_row["readme_preview"] or ""
            else:
                summary = extract_repo_summary(full_name, file_map, modules)
                languages = extract_language_stats(files) if files else {}
        else:
            summary = extract_repo_summary(full_name, file_map, modules)
            languages = extract_language_stats(files) if files else {}
        total_file_count = len(files) + copied_files
        total_chunk_count = chunk_count + copied_chunks

        conn.execute(
            """
            INSERT INTO repo_commits
            (repo_id, commit_sha, parent_sha, message, author, committed_at, indexed_at,
             status, summary, languages, readme_path, readme_preview, file_count, chunk_count)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'ready', ?, ?, ?, ?, ?, ?)
            ON CONFLICT(repo_id, commit_sha) DO UPDATE SET
                parent_sha = excluded.parent_sha,
                message = excluded.message,
                author = excluded.author,
                committed_at = excluded.committed_at,
                indexed_at = excluded.indexed_at,
                status = 'ready',
                summary = excluded.summary,
                languages = excluded.languages,
                readme_path = excluded.readme_path,
                readme_preview = excluded.readme_preview,
                file_count = excluded.file_count,
                chunk_count = excluded.chunk_count
            """,
            (
                repo_id,
                commit_sha,
                parent_sha,
                (commit_info.get("message") or "")[:500],
                author_login,
                committed_at,
                datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M"),
                summary,
                json.dumps(languages, ensure_ascii=False),
                readme_path,
                readme_preview,
                total_file_count,
                total_chunk_count,
            ),
        )

    return {
        "commitSha": commit_sha,
        "shortSha": commit_sha[:12],
        "fileCount": total_file_count,
        "chunkCount": total_chunk_count,
        "message": (commit_info.get("message") or "")[:120],
        "incremental": incremental,
        "fetchedFiles": len(files),
        "copiedFiles": copied_files,
    }


async def build_repo_knowledge(
    repo_id: str,
    token: str,
    *,
    index_each_commit: bool = False,
    max_commits: int = 30,
    commit_shas: list[str] | None = None,
) -> dict:
    init_db()
    repo = await github_get(f"/repositories/{repo_id}", token)
    if not isinstance(repo, dict):
        raise RuntimeError("仓库不存在")

    full_name = repo["full_name"]
    branch = repo.get("default_branch", "main")
    topics = repo.get("topics") or []
    license_name = (repo.get("license") or {}).get("spdx_id") or (repo.get("license") or {}).get("name") or ""

    previous_status = "idle"
    with get_connection() as conn:
        existing = conn.execute("SELECT status FROM repo_index WHERE repo_id = ?", (repo_id,)).fetchone()
        if existing and existing["status"] in {"ready", "idle"}:
            previous_status = existing["status"]

    try:
        with get_connection() as conn:
            conn.execute(
                """
                INSERT INTO repo_index (repo_id, full_name, default_branch, status)
                VALUES (?, ?, ?, 'indexing')
                ON CONFLICT(repo_id) DO UPDATE SET status='indexing', full_name=excluded.full_name
                """,
                (repo_id, full_name, branch),
            )
            conn.execute(
                """
                INSERT INTO repo_index_settings (repo_id, index_each_commit, max_commits)
                VALUES (?, ?, ?)
                ON CONFLICT(repo_id) DO UPDATE SET
                    index_each_commit = excluded.index_each_commit,
                    max_commits = excluded.max_commits
                """,
                (repo_id, 1 if index_each_commit else 0, max_commits),
            )

        if commit_shas:
            targets: list[dict] = []
            for sha in commit_shas:
                commit = await github_get(f"/repos/{full_name}/commits/{sha}", token)
                if isinstance(commit, dict):
                    targets.append(commit)
        elif index_each_commit:
            targets = await fetch_commits(full_name, token, max_commits)
        else:
            branch_info = await github_get(f"/repos/{full_name}/branches/{branch}", token)
            head_sha = _nested_get(branch_info, "commit", "sha") if isinstance(branch_info, dict) else ""
            if not head_sha:
                raise RuntimeError("无法获取默认分支 HEAD")
            commit = await github_get(f"/repos/{full_name}/commits/{head_sha}", token)
            targets = [commit] if isinstance(commit, dict) else []

        indexed: list[dict] = []
        prev_sha: str = ""
        for commit in targets:
            # 将前一个已索引 commit 的 SHA 传入，启用增量索引（Compare API）
            result = await index_commit_snapshot(
                repo_id, full_name, commit, token,
                parent_sha_for_incremental=prev_sha,
            )
            indexed.append(result)
            prev_sha = commit.get("sha", "")

        if not indexed:
            raise RuntimeError("没有可索引的 commit")

        latest = indexed[0]
        latest_sha = latest["commitSha"]

        with get_connection() as conn:
            latest_row = conn.execute(
                "SELECT * FROM repo_commits WHERE repo_id = ? AND commit_sha = ?",
                (repo_id, latest_sha),
            ).fetchone()
            conn.execute(
                """
                UPDATE repo_index
                SET indexed_at = ?, file_count = ?, chunk_count = ?, status = 'ready',
                    summary = ?, languages = ?, readme_path = ?,
                    commit_sha = ?, topics = ?, license_name = ?, readme_preview = ?,
                    active_commit_sha = ?
                WHERE repo_id = ?
                """,
                (
                    latest_row["indexed_at"] if latest_row else datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M"),
                    latest_row["file_count"] if latest_row else 0,
                    latest_row["chunk_count"] if latest_row else 0,
                    latest_row["summary"] if latest_row else "",
                    latest_row["languages"] if latest_row else "{}",
                    latest_row["readme_path"] if latest_row else "",
                    latest_sha[:12],
                    json.dumps(topics, ensure_ascii=False),
                    license_name,
                    latest_row["readme_preview"] if latest_row else "",
                    latest_sha,
                    repo_id,
                ),
            )
            conn.execute(
                "UPDATE repo_index_settings SET active_commit_sha = ? WHERE repo_id = ?",
                (latest_sha, repo_id),
            )
            _mirror_commit_to_legacy(conn, repo_id, latest_sha)

        stats = storage_stats(repo_id)
        return {
            "repoId": repo_id,
            "fullName": full_name,
            "indexedCommits": len(indexed),
            "commits": indexed,
            "activeCommitSha": latest_sha,
            "deduplication": stats,
            "status": "ready",
        }
    except Exception:
        with get_connection() as conn:
            conn.execute(
                "UPDATE repo_index SET status = ? WHERE repo_id = ? AND status = 'indexing'",
                (previous_status, repo_id),
            )
        raise


def list_indexed_commits(repo_id: str) -> list[dict]:
    init_db()
    with get_connection() as conn:
        rows = conn.execute(
            """
            SELECT commit_sha, parent_sha, message, author, committed_at, indexed_at,
                   file_count, chunk_count, status
            FROM repo_commits WHERE repo_id = ?
            ORDER BY committed_at DESC
            """,
            (repo_id,),
        ).fetchall()
    return [
        {
            "commitSha": row["commit_sha"],
            "shortSha": row["commit_sha"][:12],
            "parentSha": row["parent_sha"],
            "message": row["message"],
            "author": row["author"],
            "committedAt": row["committed_at"],
            "indexedAt": row["indexed_at"],
            "fileCount": row["file_count"],
            "chunkCount": row["chunk_count"],
            "status": row["status"],
        }
        for row in rows
    ]


def _row_val(row, key: str, default: str = "") -> str:
    if row is None:
        return default
    if key not in row.keys():
        return default
    value = row[key]
    return default if value is None else str(value)


def _legacy_has_data(conn, repo_id: str) -> bool:
    row = conn.execute(
        "SELECT COUNT(*) AS c FROM repo_files WHERE repo_id = ? AND file_type = 'file'",
        (repo_id,),
    ).fetchone()
    return bool(row and row["c"] > 0)


def _mirror_commit_to_legacy(conn, repo_id: str, commit_sha: str) -> None:
    """Keep legacy repo_files/repo_chunks in sync for RAG and older overview paths."""
    conn.execute("DELETE FROM repo_files WHERE repo_id = ?", (repo_id,))
    conn.execute("DELETE FROM repo_chunks WHERE repo_id = ?", (repo_id,))

    for row in conn.execute(
        """
        SELECT path, file_type, size, language
        FROM commit_files
        WHERE repo_id = ? AND commit_sha = ?
        ORDER BY path
        """,
        (repo_id, commit_sha),
    ).fetchall():
        conn.execute(
            """
            INSERT INTO repo_files (repo_id, path, file_type, size, language)
            VALUES (?, ?, ?, ?, ?)
            """,
            (repo_id, row["path"], row["file_type"], row["size"], row["language"]),
        )

    for row in conn.execute(
        """
        SELECT cc.file_path, cc.chunk_index, cc.start_line, cb.content
        FROM commit_chunks cc
        JOIN chunk_blobs cb ON cb.hash = cc.chunk_hash
        WHERE cc.repo_id = ? AND cc.commit_sha = ?
        ORDER BY cc.file_path, cc.chunk_index
        """,
        (repo_id, commit_sha),
    ).fetchall():
        conn.execute(
            """
            INSERT INTO repo_chunks (repo_id, file_path, chunk_index, content, start_line)
            VALUES (?, ?, ?, ?, ?)
            """,
            (repo_id, row["file_path"], row["chunk_index"], row["content"], row["start_line"]),
        )


def _best_commit_row(conn, repo_id: str, preferred_sha: str | None = None):
    if preferred_sha:
        row = conn.execute(
            "SELECT * FROM repo_commits WHERE repo_id = ? AND commit_sha = ?",
            (repo_id, preferred_sha),
        ).fetchone()
        if row and (row["file_count"] or 0) > 0:
            return row, preferred_sha

    rows = conn.execute(
        """
        SELECT * FROM repo_commits
        WHERE repo_id = ? AND file_count > 0
        ORDER BY committed_at DESC
        """,
        (repo_id,),
    ).fetchall()
    if rows:
        row = rows[0]
        return row, row["commit_sha"]
    return None, None


def _resolve_commit_sha(repo_id: str, commit_sha: str | None) -> str | None:
    settings = get_index_settings(repo_id)
    if commit_sha:
        return commit_sha
    if settings["activeCommitSha"]:
        return settings["activeCommitSha"]
    init_db()
    with get_connection() as conn:
        index = conn.execute("SELECT active_commit_sha FROM repo_index WHERE repo_id = ?", (repo_id,)).fetchone()
        if index and index["active_commit_sha"]:
            return index["active_commit_sha"]
        row = conn.execute(
            """
            SELECT commit_sha FROM repo_commits
            WHERE repo_id = ? AND file_count > 0
            ORDER BY committed_at DESC LIMIT 1
            """,
            (repo_id,),
        ).fetchone()
    return row["commit_sha"] if row else None


def get_knowledge_overview(repo_id: str, commit_sha: str | None = None) -> dict:
    init_db()
    resolved = _resolve_commit_sha(repo_id, commit_sha)

    with get_connection() as conn:
        index = conn.execute("SELECT * FROM repo_index WHERE repo_id = ?", (repo_id,)).fetchone()
        if not index and not resolved:
            return _empty_overview(repo_id)

        commit_row, commit_sha_used = _best_commit_row(conn, repo_id, resolved)
        if commit_row and commit_sha_used:
            return _overview_from_commit(index, commit_row, repo_id, commit_sha_used)

        if index and (index["status"] == "ready" or _legacy_has_data(conn, repo_id)):
            if index["status"] == "indexing" and _legacy_has_data(conn, repo_id):
                conn.execute(
                    "UPDATE repo_index SET status = 'ready' WHERE repo_id = ?",
                    (repo_id,),
                )
                index = conn.execute("SELECT * FROM repo_index WHERE repo_id = ?", (repo_id,)).fetchone()
            return _legacy_overview(index, repo_id)

    return _empty_overview(repo_id)


def _empty_overview(repo_id: str) -> dict:
    return {
        "repoId": repo_id,
        "status": "not_indexed",
        "tree": [],
        "modules": [],
        "dependencies": [],
        "fileCount": 0,
        "chunkCount": 0,
        "summary": "",
        "languages": {},
        "indexedFiles": [],
        "commits": [],
        "settings": get_index_settings(repo_id),
    }


def _overview_from_commit(index, commit_row, repo_id: str, commit_sha: str) -> dict:
    with get_connection() as conn:
        rows = conn.execute(
            "SELECT path, file_type, size, language, blob_hash FROM commit_files WHERE commit_sha = ? ORDER BY path",
            (commit_sha,),
        ).fetchall()

    files = [
        FileRecord(path=row["path"], file_type=row["file_type"], size=row["size"], language=row["language"])
        for row in rows
        if row["file_type"] == "file"
    ]
    paths = [row["path"] for row in rows]
    file_map: dict[str, str] = {}
    for row in rows:
        if row["file_type"] != "file" or not row["blob_hash"]:
            continue
        content = get_content_blob(row["blob_hash"])
        if content:
            file_map[row["path"]] = content[:500]

    try:
        languages = json.loads(commit_row["languages"] or "{}")
    except json.JSONDecodeError:
        languages = {}

    try:
        topics = json.loads(index["topics"] or "[]") if index else []
    except json.JSONDecodeError:
        topics = []

    indexed_files = [
        {"path": row["path"], "size": row["size"], "language": row["language"] or "—"}
        for row in rows
        if row["file_type"] == "file"
    ][:40]

    return {
        "repoId": repo_id,
        "fullName": index["full_name"] if index else "",
        "status": commit_row["status"],
        "indexedAt": commit_row["indexed_at"],
        "fileCount": commit_row["file_count"],
        "chunkCount": commit_row["chunk_count"],
        "summary": commit_row["summary"],
        "languages": languages,
        "readmePath": commit_row["readme_path"],
        "readmePreview": commit_row["readme_preview"],
        "commitSha": commit_sha,
        "shortSha": commit_sha[:12],
        "topics": topics,
        "license": index["license_name"] if index and "license_name" in index.keys() else "",
        "tree": build_tree(paths),
        "modules": extract_modules(files),
        "dependencies": extract_dependencies(file_map),
        "indexedFiles": indexed_files,
        "commits": list_indexed_commits(repo_id),
        "settings": get_index_settings(repo_id),
        "deduplication": storage_stats(repo_id),
        "storageModel": {
            "displayed": [
                "summary", "readmePreview", "tree", "languages", "modules",
                "dependencies", "commitTimeline", "compareDiff",
            ],
            "databaseOnly": [
                "content_blobs / chunk_blobs (deduplicated by SHA-256 hash)",
                "commit_files / commit_chunks (per-commit references, shared blobs)",
                "chunk embeddings (reserved)",
            ],
            "dedupStrategy": "相同文件/片段内容只存一份 blob，各 commit 通过 hash 引用，不丢失任何历史切片",
        },
    }


def _legacy_overview(index, repo_id: str) -> dict:
    with get_connection() as conn:
        rows = conn.execute(
            "SELECT path, file_type, size, language FROM repo_files WHERE repo_id = ? ORDER BY path",
            (repo_id,),
        ).fetchall()
        file_map: dict[str, str] = {}
        for row in rows:
            if row["file_type"] != "file":
                continue
            chunk = conn.execute(
                "SELECT content FROM repo_chunks WHERE repo_id = ? AND file_path = ? ORDER BY chunk_index LIMIT 1",
                (repo_id, row["path"]),
            ).fetchone()
            if chunk:
                file_map[row["path"]] = chunk["content"]

    files = [
        FileRecord(path=row["path"], file_type=row["file_type"], size=row["size"], language=row["language"])
        for row in rows
        if row["file_type"] == "file"
    ]
    try:
        languages = json.loads(index["languages"] or "{}")
    except json.JSONDecodeError:
        languages = {}

    return {
        "repoId": repo_id,
        "fullName": index["full_name"],
        "status": index["status"],
        "indexedAt": index["indexed_at"],
        "fileCount": index["file_count"],
        "chunkCount": index["chunk_count"],
        "summary": index["summary"],
        "languages": languages,
        "readmePath": index["readme_path"],
        "readmePreview": _row_val(index, "readme_preview"),
        "commitSha": _row_val(index, "commit_sha"),
        "tree": build_tree([row["path"] for row in rows]),
        "modules": extract_modules(files),
        "dependencies": extract_dependencies(file_map),
        "indexedFiles": [],
        "commits": list_indexed_commits(repo_id),
        "settings": get_index_settings(repo_id),
    }


def compare_commits(repo_id: str, base_sha: str, head_sha: str, preview_limit: int = 5) -> dict:
    init_db()
    with get_connection() as conn:
        base_files = {
            row["path"]: row["blob_hash"]
            for row in conn.execute(
                "SELECT path, blob_hash FROM commit_files WHERE repo_id = ? AND commit_sha = ? AND file_type = 'file'",
                (repo_id, base_sha),
            ).fetchall()
        }
        head_files = {
            row["path"]: row["blob_hash"]
            for row in conn.execute(
                "SELECT path, blob_hash FROM commit_files WHERE repo_id = ? AND commit_sha = ? AND file_type = 'file'",
                (repo_id, head_sha),
            ).fetchall()
        }

    base_paths = set(base_files)
    head_paths = set(head_files)
    added = sorted(head_paths - base_paths)
    removed = sorted(base_paths - head_paths)
    modified: list[dict] = []
    unchanged = 0

    for path in sorted(base_paths & head_paths):
        if base_files[path] == head_files[path]:
            unchanged += 1
        else:
            modified.append({"path": path, "baseHash": base_files[path][:8], "headHash": head_files[path][:8]})

    previews: list[dict] = []
    for item in modified[:preview_limit]:
        path = item["path"]
        old_content = get_content_blob(base_files[path]) or ""
        new_content = get_content_blob(head_files[path]) or ""
        diff_lines = list(
            difflib.unified_diff(
                old_content.splitlines(),
                new_content.splitlines(),
                fromfile=f"{path}@{base_sha[:7]}",
                tofile=f"{path}@{head_sha[:7]}",
                lineterm="",
            )
        )[:80]
        previews.append({"path": path, "diff": "\n".join(diff_lines)})

    with get_connection() as conn:
        base_row = conn.execute(
            "SELECT message, committed_at FROM repo_commits WHERE repo_id = ? AND commit_sha = ?",
            (repo_id, base_sha),
        ).fetchone()
        head_row = conn.execute(
            "SELECT message, committed_at FROM repo_commits WHERE repo_id = ? AND commit_sha = ?",
            (repo_id, head_sha),
        ).fetchone()

    shared_blobs = len({h for h in base_files.values()} & {h for h in head_files.values()})

    return {
        "baseSha": base_sha,
        "headSha": head_sha,
        "baseMessage": base_row["message"] if base_row else "",
        "headMessage": head_row["message"] if head_row else "",
        "added": added,
        "removed": removed,
        "modified": [m["path"] for m in modified],
        "unchanged": unchanged,
        "sharedBlobCount": shared_blobs,
        "previews": previews,
    }
