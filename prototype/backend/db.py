import sqlite3
from pathlib import Path

DB_PATH = Path(__file__).parent / "data" / "knowledge.db"


def get_connection() -> sqlite3.Connection:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DB_PATH, timeout=30.0)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA busy_timeout=30000")
    conn.execute("PRAGMA foreign_keys = ON")
    return conn


def init_db() -> None:
    with get_connection() as conn:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS repo_index (
                repo_id TEXT PRIMARY KEY,
                full_name TEXT NOT NULL,
                default_branch TEXT,
                indexed_at TEXT,
                file_count INTEGER DEFAULT 0,
                chunk_count INTEGER DEFAULT 0,
                status TEXT DEFAULT 'idle',
                summary TEXT DEFAULT '',
                languages TEXT DEFAULT '{}',
                readme_path TEXT DEFAULT '',
                commit_sha TEXT DEFAULT '',
                topics TEXT DEFAULT '[]',
                license_name TEXT DEFAULT '',
                readme_preview TEXT DEFAULT ''
            );

            CREATE TABLE IF NOT EXISTS repo_files (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                repo_id TEXT NOT NULL,
                path TEXT NOT NULL,
                file_type TEXT NOT NULL,
                size INTEGER DEFAULT 0,
                language TEXT,
                UNIQUE(repo_id, path)
            );

            CREATE TABLE IF NOT EXISTS repo_chunks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                repo_id TEXT NOT NULL,
                file_path TEXT NOT NULL,
                chunk_index INTEGER NOT NULL,
                content TEXT NOT NULL,
                start_line INTEGER DEFAULT 1
            );

            CREATE INDEX IF NOT EXISTS idx_chunks_repo ON repo_chunks(repo_id);
            CREATE INDEX IF NOT EXISTS idx_files_repo ON repo_files(repo_id);

            CREATE TABLE IF NOT EXISTS issue_analysis (
                issue_id TEXT PRIMARY KEY,
                repo_id TEXT NOT NULL,
                issue_number INTEGER,
                issue_title TEXT NOT NULL,
                issue_type TEXT NOT NULL,
                confidence REAL DEFAULT 0,
                summary TEXT NOT NULL,
                suggested_reply TEXT NOT NULL,
                reason TEXT DEFAULT '',
                related_files TEXT DEFAULT '[]',
                analyzed_at TEXT NOT NULL,
                llm_enhanced INTEGER DEFAULT 0
            );

            CREATE INDEX IF NOT EXISTS idx_issue_analysis_repo ON issue_analysis(repo_id);

            -- Content-addressable blobs (deduplicated across commits)
            CREATE TABLE IF NOT EXISTS content_blobs (
                hash TEXT PRIMARY KEY,
                content TEXT NOT NULL,
                size INTEGER NOT NULL,
                ref_count INTEGER DEFAULT 1
            );

            CREATE TABLE IF NOT EXISTS chunk_blobs (
                hash TEXT PRIMARY KEY,
                content TEXT NOT NULL,
                size INTEGER NOT NULL,
                ref_count INTEGER DEFAULT 1
            );

            -- Per-commit knowledge snapshots
            CREATE TABLE IF NOT EXISTS repo_commits (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                repo_id TEXT NOT NULL,
                commit_sha TEXT NOT NULL,
                parent_sha TEXT DEFAULT '',
                message TEXT DEFAULT '',
                author TEXT DEFAULT '',
                committed_at TEXT DEFAULT '',
                indexed_at TEXT DEFAULT '',
                status TEXT DEFAULT 'ready',
                summary TEXT DEFAULT '',
                languages TEXT DEFAULT '{}',
                readme_path TEXT DEFAULT '',
                readme_preview TEXT DEFAULT '',
                file_count INTEGER DEFAULT 0,
                chunk_count INTEGER DEFAULT 0,
                UNIQUE(repo_id, commit_sha)
            );

            CREATE INDEX IF NOT EXISTS idx_repo_commits_repo ON repo_commits(repo_id);

            CREATE TABLE IF NOT EXISTS commit_files (
                repo_id TEXT NOT NULL,
                commit_sha TEXT NOT NULL,
                path TEXT NOT NULL,
                blob_hash TEXT NOT NULL,
                file_type TEXT NOT NULL,
                size INTEGER DEFAULT 0,
                language TEXT,
                PRIMARY KEY (commit_sha, path)
            );

            CREATE INDEX IF NOT EXISTS idx_commit_files_repo ON commit_files(repo_id, commit_sha);

            CREATE TABLE IF NOT EXISTS commit_chunks (
                repo_id TEXT NOT NULL,
                commit_sha TEXT NOT NULL,
                file_path TEXT NOT NULL,
                chunk_index INTEGER NOT NULL,
                chunk_hash TEXT NOT NULL,
                start_line INTEGER DEFAULT 1,
                PRIMARY KEY (commit_sha, file_path, chunk_index)
            );

            CREATE INDEX IF NOT EXISTS idx_commit_chunks_repo ON commit_chunks(repo_id, commit_sha);

            CREATE TABLE IF NOT EXISTS repo_index_settings (
                repo_id TEXT PRIMARY KEY,
                index_each_commit INTEGER DEFAULT 0,
                max_commits INTEGER DEFAULT 30,
                active_commit_sha TEXT DEFAULT ''
            );
            """
        )

        # Lightweight migrations for databases created by earlier prototype builds.
        columns = {row["name"] for row in conn.execute("PRAGMA table_info(repo_index)").fetchall()}
        for name, ddl in {
            "summary": "ALTER TABLE repo_index ADD COLUMN summary TEXT DEFAULT ''",
            "languages": "ALTER TABLE repo_index ADD COLUMN languages TEXT DEFAULT '{}'",
            "readme_path": "ALTER TABLE repo_index ADD COLUMN readme_path TEXT DEFAULT ''",
            "commit_sha": "ALTER TABLE repo_index ADD COLUMN commit_sha TEXT DEFAULT ''",
            "topics": "ALTER TABLE repo_index ADD COLUMN topics TEXT DEFAULT '[]'",
            "license_name": "ALTER TABLE repo_index ADD COLUMN license_name TEXT DEFAULT ''",
            "readme_preview": "ALTER TABLE repo_index ADD COLUMN readme_preview TEXT DEFAULT ''",
            "active_commit_sha": "ALTER TABLE repo_index ADD COLUMN active_commit_sha TEXT DEFAULT ''",
        }.items():
            if name not in columns:
                conn.execute(ddl)

        # Migration: issue_analysis.llm_enhanced (added after initial release)
        issue_columns = {row["name"] for row in conn.execute("PRAGMA table_info(issue_analysis)").fetchall()}
        if "llm_enhanced" not in issue_columns:
            conn.execute("ALTER TABLE issue_analysis ADD COLUMN llm_enhanced INTEGER DEFAULT 0")

        # ── 对话历史 ──
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS conversations (
                id TEXT PRIMARY KEY,
                repo_id TEXT NOT NULL,
                title TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            );

            CREATE INDEX IF NOT EXISTS idx_conv_repo ON conversations(repo_id);

            CREATE TABLE IF NOT EXISTS conversation_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conversation_id TEXT NOT NULL,
                role TEXT NOT NULL CHECK(role IN ('user', 'assistant')),
                content TEXT NOT NULL,
                question_type TEXT DEFAULT '',
                citations TEXT DEFAULT '[]',
                created_at TEXT NOT NULL,
                FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_cmsg_conv ON conversation_messages(conversation_id);
            """
        )
