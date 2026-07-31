"""Content-addressable storage for deduplicated file and chunk blobs."""

import hashlib
import sqlite3

from db import get_connection


def blob_hash(content: str) -> str:
    return hashlib.sha256(content.encode("utf-8")).hexdigest()


def store_content_blob(content: str, conn: sqlite3.Connection | None = None) -> str:
    h = blob_hash(content)

    def _write(c: sqlite3.Connection) -> None:
        row = c.execute("SELECT hash FROM content_blobs WHERE hash = ?", (h,)).fetchone()
        if row:
            c.execute("UPDATE content_blobs SET ref_count = ref_count + 1 WHERE hash = ?", (h,))
        else:
            c.execute(
                "INSERT INTO content_blobs (hash, content, size, ref_count) VALUES (?, ?, ?, 1)",
                (h, content, len(content)),
            )

    if conn is not None:
        _write(conn)
    else:
        with get_connection() as c:
            _write(c)
    return h


def store_chunk_blob(content: str, conn: sqlite3.Connection | None = None) -> str:
    h = blob_hash(content)

    def _write(c: sqlite3.Connection) -> None:
        row = c.execute("SELECT hash FROM chunk_blobs WHERE hash = ?", (h,)).fetchone()
        if row:
            c.execute("UPDATE chunk_blobs SET ref_count = ref_count + 1 WHERE hash = ?", (h,))
        else:
            c.execute(
                "INSERT INTO chunk_blobs (hash, content, size, ref_count) VALUES (?, ?, ?, 1)",
                (h, content, len(content)),
            )

    if conn is not None:
        _write(conn)
    else:
        with get_connection() as c:
            _write(c)
    return h


def get_content_blob(h: str, conn: sqlite3.Connection | None = None) -> str | None:
    if conn is not None:
        row = conn.execute("SELECT content FROM content_blobs WHERE hash = ?", (h,)).fetchone()
        return row["content"] if row else None
    with get_connection() as c:
        row = c.execute("SELECT content FROM content_blobs WHERE hash = ?", (h,)).fetchone()
    return row["content"] if row else None


def get_chunk_blob(h: str, conn: sqlite3.Connection | None = None) -> str | None:
    if conn is not None:
        row = conn.execute("SELECT content FROM chunk_blobs WHERE hash = ?", (h,)).fetchone()
        return row["content"] if row else None
    with get_connection() as c:
        row = c.execute("SELECT content FROM chunk_blobs WHERE hash = ?", (h,)).fetchone()
    return row["content"] if row else None


def release_commit_refs(commit_sha: str, conn: sqlite3.Connection | None = None) -> None:
    """Drop snapshot refs before re-indexing a commit (blobs kept for dedup)."""

    def _write(c: sqlite3.Connection) -> None:
        c.execute("DELETE FROM commit_files WHERE commit_sha = ?", (commit_sha,))
        c.execute("DELETE FROM commit_chunks WHERE commit_sha = ?", (commit_sha,))

    if conn is not None:
        _write(conn)
    else:
        with get_connection() as c:
            _write(c)


def storage_stats(repo_id: str) -> dict:
    with get_connection() as conn:
        commits = conn.execute(
            "SELECT COUNT(*) AS c FROM repo_commits WHERE repo_id = ?", (repo_id,)
        ).fetchone()["c"]
        blobs = conn.execute("SELECT COUNT(*) AS c, COALESCE(SUM(size), 0) AS s FROM content_blobs").fetchone()
        chunks = conn.execute("SELECT COUNT(*) AS c FROM chunk_blobs").fetchone()["c"]
        file_refs = conn.execute(
            "SELECT COUNT(*) AS c FROM commit_files WHERE repo_id = ?", (repo_id,)
        ).fetchone()["c"]
    return {
        "indexedCommits": commits,
        "uniqueFileBlobs": blobs["c"],
        "uniqueChunkBlobs": chunks,
        "totalBlobBytes": blobs["s"],
        "fileReferences": file_refs,
    }
