import json
import uuid
from datetime import datetime, timezone

from db import get_connection, init_db


def _now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")


def _conversation_exists(conversation_id: str) -> bool:
    init_db()
    with get_connection() as conn:
        row = conn.execute(
            "SELECT 1 FROM conversations WHERE id = ?", (conversation_id,)
        ).fetchone()
    return row is not None


def list_conversations(repo_id: str) -> list[dict]:
    init_db()
    with get_connection() as conn:
        rows = conn.execute(
            """
            SELECT c.id, c.repo_id, c.title, c.created_at, c.updated_at,
                   (SELECT COUNT(*) FROM conversation_messages WHERE conversation_id = c.id) AS msg_count
            FROM conversations c
            WHERE c.repo_id = ?
            ORDER BY c.updated_at DESC
            """,
            (repo_id,),
        ).fetchall()
    return [
        {
            "id": row["id"],
            "repoId": row["repo_id"],
            "title": row["title"],
            "createdAt": row["created_at"],
            "updatedAt": row["updated_at"],
            "msgCount": row["msg_count"],
        }
        for row in rows
    ]


def create_conversation(repo_id: str, title: str = "") -> dict:
    init_db()
    conv_id = str(uuid.uuid4())
    now = _now()
    with get_connection() as conn:
        conn.execute(
            "INSERT INTO conversations (id, repo_id, title, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
            (conv_id, repo_id, title or "新对话", now, now),
        )
    return {
        "id": conv_id,
        "repoId": repo_id,
        "title": title or "新对话",
        "createdAt": now,
        "updatedAt": now,
        "msgCount": 0,
    }


def get_conversation_messages(conversation_id: str) -> list[dict]:
    init_db()
    with get_connection() as conn:
        rows = conn.execute(
            """
            SELECT id, conversation_id, role, content, question_type, citations, created_at
            FROM conversation_messages
            WHERE conversation_id = ?
            ORDER BY id ASC
            """,
            (conversation_id,),
        ).fetchall()
    return [
        {
            "id": str(row["id"]),
            "conversationId": row["conversation_id"],
            "role": row["role"],
            "content": row["content"],
            "questionType": row["question_type"] or None,
            "citations": json.loads(row["citations"] or "[]"),
            "createdAt": row["created_at"],
        }
        for row in rows
    ]


def save_message(
    conversation_id: str,
    role: str,
    content: str,
    question_type: str = "",
    citations: list | None = None,
) -> dict:
    init_db()
    now = _now()
    citations_json = json.dumps(citations or [], ensure_ascii=False)
    with get_connection() as conn:
        cur = conn.execute(
            "INSERT INTO conversation_messages (conversation_id, role, content, question_type, citations, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            (conversation_id, role, content, question_type, citations_json, now),
        )
        conn.execute(
            "UPDATE conversations SET updated_at = ? WHERE id = ?",
            (now, conversation_id),
        )
        # Auto-title from first user message
        title_row = conn.execute(
            "SELECT title FROM conversations WHERE id = ?", (conversation_id,)
        ).fetchone()
        if title_row and (not title_row["title"] or title_row["title"] == "新对话"):
            msg_count = conn.execute(
                "SELECT COUNT(*) AS c FROM conversation_messages WHERE conversation_id = ?",
                (conversation_id,),
            ).fetchone()["c"]
            if msg_count == 1 and role == "user":
                conn.execute(
                    "UPDATE conversations SET title = ? WHERE id = ?",
                    (content[:60], conversation_id),
                )

    return {
        "id": str(cur.lastrowid),
        "conversationId": conversation_id,
        "role": role,
        "content": content,
        "questionType": question_type or None,
        "citations": citations or [],
        "createdAt": now,
    }


def update_conversation_title(conversation_id: str, title: str) -> None:
    init_db()
    with get_connection() as conn:
        conn.execute(
            "UPDATE conversations SET title = ?, updated_at = ? WHERE id = ?",
            (title, _now(), conversation_id),
        )


def delete_conversation(conversation_id: str) -> None:
    init_db()
    with get_connection() as conn:
        conn.execute("DELETE FROM conversations WHERE id = ?", (conversation_id,))
