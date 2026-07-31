"""Container-local ops tracing for CodeWiki build / query pipelines.

Writes JSONL under CODEWIKI_STORAGE_DIR/logs/ and one-line stderr summaries.
Does not expose anything to backend/frontend APIs.
"""
from __future__ import annotations

import json
import os
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def enabled() -> bool:
    raw = (os.environ.get("CODEWIKI_OPS_TRACE") or "1").strip().lower()
    return raw not in ("0", "false", "no", "off")


def content_chars() -> int:
    try:
        return max(200, int(os.environ.get("CODEWIKI_OPS_TRACE_CONTENT_CHARS") or "12000"))
    except ValueError:
        return 12000


def _storage_dir() -> Path:
    return Path(os.environ.get("CODEWIKI_STORAGE_DIR", "/app/storage"))


def _logs_dir() -> Path:
    path = _storage_dir() / "logs"
    path.mkdir(parents=True, exist_ok=True)
    return path


def new_id(kind: str) -> str:
    prefix = "build" if kind.startswith("build") else "query"
    return f"{prefix}_{uuid.uuid4().hex[:12]}"


def truncate(text: Any, limit: int | None = None) -> str:
    value = "" if text is None else str(text)
    max_chars = content_chars() if limit is None else max(0, limit)
    if len(value) <= max_chars:
        return value
    return value[:max_chars] + f"…(+{len(value) - max_chars} chars)"


def summarize_contexts(contexts: Any, *, max_items: int = 60) -> list[dict[str, Any]]:
    if not isinstance(contexts, list):
        return []
    out: list[dict[str, Any]] = []
    for item in contexts[:max_items]:
        if not isinstance(item, dict):
            out.append({"raw": truncate(item, 500)})
            continue
        out.append(
            {
                "file": item.get("file"),
                "line": item.get("line"),
                "endLine": item.get("endLine"),
                "symbolName": item.get("symbolName"),
                "symbolKind": item.get("symbolKind"),
                "score": item.get("score"),
                "retrievalType": item.get("retrievalType"),
                "sourceType": item.get("sourceType"),
                "content": truncate(item.get("content")),
            }
        )
    if len(contexts) > max_items:
        out.append({"_truncated": True, "omitted": len(contexts) - max_items})
    return out


def summarize_entities(entities: list[dict[str, Any]], *, max_items: int = 40) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for item in entities[:max_items]:
        out.append(
            {
                "id": item.get("id"),
                "name": item.get("name"),
                "type": item.get("type"),
                "score": item.get("score"),
                "reasons": item.get("reasons"),
                "file": item.get("file"),
            }
        )
    if len(entities) > max_items:
        out.append({"_truncated": True, "omitted": len(entities) - max_items})
    return out


def _stderr_summary(payload: dict[str, Any]) -> None:
    phase = payload.get("phase") or "?"
    kind = payload.get("kind") or "?"
    repo = payload.get("repo_id") or "-"
    trace = payload.get("trace_id") or "-"
    extra = ""
    for key in ("mode", "status", "message", "error", "entity_count", "seed_count", "context_count"):
        if key in payload and payload[key] not in (None, ""):
            extra += f" {key}={payload[key]}"
            break
    line = f"[codewiki-ops] {kind} {phase} repo={repo} trace={trace}{extra}\n"
    try:
        sys.stderr.write(line)
        sys.stderr.flush()
    except Exception:
        pass


def event(file_kind: str, payload: dict[str, Any]) -> None:
    """Append one JSONL event. file_kind is 'build' or 'query'."""
    if not enabled():
        return
    row = dict(payload)
    row.setdefault("ts", datetime.now(timezone.utc).isoformat())
    row.setdefault("kind", file_kind)
    try:
        day = datetime.now(timezone.utc).strftime("%Y%m%d")
        path = _logs_dir() / f"{file_kind}-{day}.jsonl"
        with open(path, "a", encoding="utf-8") as fh:
            fh.write(json.dumps(row, ensure_ascii=False, default=str) + "\n")
    except Exception as ex:
        try:
            sys.stderr.write(f"[codewiki-ops] write failed: {ex}\n")
            sys.stderr.flush()
        except Exception:
            pass
    _stderr_summary(row)


class TraceSession:
    """Helper that stamps a shared trace_id and records duration."""

    def __init__(self, file_kind: str, *, repo_id: str = "", **meta: Any):
        self.file_kind = file_kind
        self.trace_id = new_id(file_kind)
        self.repo_id = repo_id
        self.meta = meta
        self.started = time.monotonic()

    def emit(self, phase: str, **fields: Any) -> None:
        payload: dict[str, Any] = {
            "trace_id": self.trace_id,
            "kind": self.file_kind,
            "phase": phase,
            "repo_id": self.repo_id,
            **self.meta,
            **fields,
        }
        event(self.file_kind, payload)

    def elapsed_ms(self) -> int:
        return int((time.monotonic() - self.started) * 1000)


def extract_retrieve_summary(body: Any) -> dict[str, Any]:
    """Best-effort summarize upstream retrieve / ask JSON responses."""
    if body is None:
        return {}
    if not isinstance(body, dict):
        return {"raw": truncate(body, 1000)}

    summary: dict[str, Any] = {}
    for key in ("query", "question", "mode", "answer", "status"):
        if key in body:
            summary[key] = truncate(body[key], 2000) if key in ("answer", "query", "question") else body[key]

    for key in ("contexts", "chunks", "results", "items", "documents"):
        if key in body and isinstance(body[key], list):
            summary[key] = summarize_contexts(body[key])
            summary[f"{key}_count"] = len(body[key])

    entities = body.get("entities") or body.get("nodes") or body.get("seed_entities")
    if isinstance(entities, list):
        normalized: list[dict[str, Any]] = []
        for ent in entities:
            if isinstance(ent, dict):
                normalized.append(
                    {
                        "id": ent.get("id") or ent.get("node_id"),
                        "name": ent.get("name") or ent.get("symbolName"),
                        "type": ent.get("type") or ent.get("symbolKind"),
                        "score": ent.get("score"),
                        "file": ent.get("file") or ent.get("file_path"),
                    }
                )
            else:
                normalized.append({"id": str(ent)})
        summary["entities"] = summarize_entities(normalized)
        summary["entity_count"] = len(entities)

    return summary
