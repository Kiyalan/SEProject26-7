"""Runtime patches applied inside the CodeWiki container.

Loaded by entrypoint.py (API) and analyze_worker.py (child analyze process).
"""
from __future__ import annotations

import os
import pickle
import subprocess
import sys
import tempfile
from collections.abc import Callable, Iterable
from pathlib import Path

_PARSE_FILE_WORKER = Path(__file__).resolve().with_name("parse_file_worker.py")

# Auto-generated / minified sources that crash tree-sitter or add no useful graph signal.
_EXTRA_IGNORE_PATTERNS = [
    "**/generated/",
    "**/*.gen.ts",
    "**/*.gen.tsx",
    "**/*.gen.js",
    "**/*.gen.jsx",
    "**/*.min.js",
    "**/*.min.css",
    "**/*.map",
    "**/openapi-ts.config.ts",
]


def _log(msg: str) -> None:
    sys.stderr.write(f"[codewiki-patches] {msg}\n")
    sys.stderr.flush()


def apply_ignore_patches() -> None:
    """Extend CodeWiki default ignores so tree-sitter never sees known crashers."""
    try:
        from backend.app.services.repo_scanner import ignore as ignore_mod
    except Exception as ex:
        _log(f"ignore patch skipped: {ex}")
        return

    extra = list(_EXTRA_IGNORE_PATTERNS)
    env_extra = (os.environ.get("CODEWIKI_EXTRA_IGNORES") or "").strip()
    if env_extra:
        extra.extend([p.strip() for p in env_extra.split(",") if p.strip()])

    existing = list(getattr(ignore_mod, "DEFAULT_IGNORE_PATTERNS", []))
    merged = existing[:]
    for pattern in extra:
        if pattern not in merged:
            merged.append(pattern)
    ignore_mod.DEFAULT_IGNORE_PATTERNS = merged

    # Also patch IgnoreMatcher.__init__ in case it already captured defaults.
    original_init = ignore_mod.IgnoreMatcher.__init__

    def patched_init(self, root):  # type: ignore[no-untyped-def]
        original_init(self, root)
        try:
            self.add_lines(root, extra)
        except Exception as ex:
            _log(f"IgnoreMatcher extra patterns failed: {ex}")

    ignore_mod.IgnoreMatcher.__init__ = patched_init  # type: ignore[assignment]
    _log(f"ignore patch applied (+{len(extra)} patterns)")


def apply_parse_isolation_patch() -> None:
    """Parse each file in a subprocess so one SIGSEGV cannot kill the whole analyze."""
    enabled = (os.environ.get("CODEWIKI_AST_PARSE_ISOLATE") or "1").strip().lower()
    if enabled in ("0", "false", "no", "off"):
        _log("parse isolation disabled by env")
        return
    if not _PARSE_FILE_WORKER.is_file():
        _log(f"parse isolation skipped (missing {_PARSE_FILE_WORKER})")
        return

    try:
        from backend.app.services import ast_parser as ast_mod
        from backend.app.services.repo_scanner import ScannedFile
    except Exception as ex:
        _log(f"parse isolation skipped (import): {ex}")
        return

    timeout_sec = int(os.environ.get("CODEWIKI_AST_PARSE_FILE_TIMEOUT", "60"))

    def parse_scanned_files_isolated(
        parser,  # noqa: ARG001 — signature must match upstream
        files: Iterable,
        *,
        repo_root: Path,
        only_paths: set[str] | None = None,
        max_workers: int | None = None,  # noqa: ARG001
        content_provider=None,  # noqa: ARG001
        progress_callback: Callable[[int, int, str], None] | None = None,
    ):
        candidates = [
            scanned_file
            for scanned_file in files
            if getattr(scanned_file, "is_source", False)
            and (only_paths is None or scanned_file.path in only_paths)
        ]
        symbols: list = []
        errors: list[dict[str, str]] = []
        total = len(candidates)
        for index, scanned_file in enumerate(candidates, start=1):
            file_symbols, error = _parse_one_file_subprocess(scanned_file, repo_root, timeout_sec)
            if error is not None:
                errors.append(error)
                _log(f"skip crashed/failed file: {scanned_file.path} ({error.get('error')})")
            else:
                symbols.extend(file_symbols)
            if progress_callback is not None:
                progress_callback(index, total, scanned_file.path)
        return symbols, errors

    ast_mod.parse_scanned_files = parse_scanned_files_isolated  # type: ignore[assignment]
    _log("parse isolation patch applied (per-file subprocess)")


def _parse_one_file_subprocess(
    scanned_file,
    repo_root: Path,
    timeout_sec: int,
) -> tuple[list, dict[str, str] | None]:
    abs_path = getattr(scanned_file, "absolute_path", None) or str(
        Path(repo_root) / scanned_file.path
    )
    language = getattr(scanned_file, "language", None) or ""
    out_path = None
    try:
        with tempfile.NamedTemporaryFile(prefix="cw-parse-", suffix=".pkl", delete=False) as tmp:
            out_path = tmp.name
        proc = subprocess.run(
            [
                sys.executable,
                str(_PARSE_FILE_WORKER),
                str(abs_path),
                str(repo_root),
                str(language),
                out_path,
            ],
            capture_output=True,
            text=True,
            timeout=timeout_sec,
            check=False,
        )
        if proc.returncode != 0:
            detail = (proc.stderr or proc.stdout or "").strip()
            if proc.returncode == 139 or proc.returncode == -11:
                msg = f"tree-sitter SIGSEGV (exit 139); skipped. {detail[:200]}"
            else:
                msg = f"parse worker exit {proc.returncode}; skipped. {detail[:200]}"
            return [], {"file_path": scanned_file.path, "error": msg}

        with open(out_path, "rb") as fh:
            status, payload = pickle.load(fh)
        if status == "ok":
            return list(payload), None
        return [], {"file_path": scanned_file.path, "error": str(payload)}
    except subprocess.TimeoutExpired:
        return [], {
            "file_path": scanned_file.path,
            "error": f"parse timed out after {timeout_sec}s; skipped",
        }
    except Exception as ex:
        return [], {"file_path": scanned_file.path, "error": f"parse isolation error: {ex}"}
    finally:
        if out_path:
            try:
                os.unlink(out_path)
            except OSError:
                pass


_RICH_COMMUNITY_PROMPT = """Name and summarize graph communities for GraphRAG retrieval.

Goal: each community summary must be dense enough that a later Q&A system could answer
module-level questions using ONLY this summary plus the listed files/symbols.

For every community produce:
1) A concise developer-facing subsystem name (2-8 words, capability/workflow oriented).
2) A grounded summary of 3-6 sentences covering:
   - Primary responsibility / what this cluster does in the repo
   - Key files and symbols (classes, functions, endpoints) and their roles
   - Important inbound/outbound dependencies (what it uses / what uses it)
   - Typical questions this community can help answer
   - Unclear boundaries only when the graph evidence is weak

Rules:
- Use only the provided graph evidence (files, symbols, edges, deterministic summary).
- Do not invent modules, APIs, files, or dependencies.
- Prefer capability names over generic layer names (avoid Backend/Frontend/Core/Misc/Cluster N).
- Write a fresh summary; do not copy the deterministic Louvain template verbatim.
- Keep node membership unchanged.
- Return only JSON in the requested shape.
"""


def apply_community_naming_patches() -> None:
    """Make LLM community names/summaries dense enough for later GraphRAG-style Q&A.

    Does not change RepoPilot chat retrieve logic — only CodeWiki community naming quality.
    """
    try:
        import re

        from backend.app.services.community import namer as namer_mod
        from backend.app.services.community.naming import constants as const_mod
        from backend.app.services.community.naming import payloads as payload_mod
        from backend.app.services.community.naming import response as response_mod
        from backend.app.services import prompts as prompts_mod
        import backend.app.services.community.naming as naming_pkg
    except Exception as ex:
        _log(f"community naming patch skipped (import): {ex}")
        return

    max_communities = int(os.environ.get("CODEWIKI_MAX_COMMUNITIES_NAME", "150"))
    per_batch = int(os.environ.get("CODEWIKI_COMMUNITIES_PER_BATCH", "5"))
    summary_chars = int(os.environ.get("CODEWIKI_COMMUNITY_SUMMARY_CHARS", "2500"))

    const_mod.MAX_COMMUNITIES_PER_LLM_CALL = max(80, max_communities)
    const_mod.COMMUNITIES_PER_BATCH = max(3, min(per_batch, 8))
    const_mod.MAX_COMMUNITY_FILES = max(const_mod.MAX_COMMUNITY_FILES, 32)
    const_mod.MAX_COMMUNITY_SYMBOLS = max(const_mod.MAX_COMMUNITY_SYMBOLS, 48)
    const_mod.MAX_COMMUNITY_EDGES = max(const_mod.MAX_COMMUNITY_EDGES, 28)

    # Refresh import-time bindings used by namer / payloads / package exports.
    # community_payload slices files/symbols via payloads.MAX_COMMUNITY_* names.
    naming_pkg.MAX_COMMUNITIES_PER_LLM_CALL = const_mod.MAX_COMMUNITIES_PER_LLM_CALL
    naming_pkg.COMMUNITIES_PER_BATCH = const_mod.COMMUNITIES_PER_BATCH
    naming_pkg.MAX_COMMUNITY_FILES = const_mod.MAX_COMMUNITY_FILES
    naming_pkg.MAX_COMMUNITY_SYMBOLS = const_mod.MAX_COMMUNITY_SYMBOLS
    naming_pkg.MAX_COMMUNITY_EDGES = const_mod.MAX_COMMUNITY_EDGES
    namer_mod.MAX_COMMUNITIES_PER_LLM_CALL = const_mod.MAX_COMMUNITIES_PER_LLM_CALL
    namer_mod.COMMUNITIES_PER_BATCH = const_mod.COMMUNITIES_PER_BATCH
    payload_mod.MAX_COMMUNITY_FILES = const_mod.MAX_COMMUNITY_FILES
    payload_mod.MAX_COMMUNITY_SYMBOLS = const_mod.MAX_COMMUNITY_SYMBOLS
    payload_mod.MAX_COMMUNITY_EDGES = const_mod.MAX_COMMUNITY_EDGES

    def normalize_summary_rich(value, *, fallback: str) -> str:  # type: ignore[no-untyped-def]
        summary = re.sub(r"\s+", " ", str(value or "").strip())
        if not summary:
            summary = fallback
        return summary[:summary_chars].strip()

    response_mod.normalize_summary = normalize_summary_rich  # type: ignore[assignment]
    naming_pkg.normalize_summary = normalize_summary_rich  # type: ignore[assignment]

    original_payload = payload_mod.naming_payload

    def naming_payload_rich(*args, **kwargs):  # type: ignore[no-untyped-def]
        payload = original_payload(*args, **kwargs)
        payload["task"] = (
            "Produce Q&A-ready community names and multi-sentence summaries using only the "
            "provided files, symbols, deterministic summaries, and graph relationships. "
            "Keep node membership unchanged."
        )
        payload["summary_rules"] = [
            "Write 3-6 source-grounded sentences suitable for later retrieval-augmented answering.",
            "Cover responsibility, key files/symbols, inbound/outbound dependencies, and example questions.",
            "Do not copy the deterministic Louvain template summary.",
            "Call out unclear boundaries only when graph evidence is weak.",
        ]
        payload["naming_rules"] = [
            "Use concise developer-facing subsystem names, 2-8 words.",
            "Prefer capability/workflow names over generic layer names.",
            "Avoid Backend Subsystem, Frontend Subsystem, Community N, Cluster N, Misc, Core.",
            "Do not invent modules, products, files, or dependencies.",
            "Return one object per input community id.",
        ]
        payload["required_json_shape"] = {
            "communities": [
                {
                    "id": "community-id",
                    "name": "GraphRAG Retrieval Service",
                    "summary": (
                        "3-6 grounded sentences: purpose, key symbols/files, "
                        "dependencies, and questions this community can answer."
                    ),
                }
            ]
        }
        return payload

    payload_mod.naming_payload = naming_payload_rich  # type: ignore[assignment]
    naming_pkg.naming_payload = naming_payload_rich  # type: ignore[assignment]
    namer_mod.naming_payload = naming_payload_rich  # type: ignore[assignment]

    original_load_prompt = prompts_mod.load_prompt

    def load_prompt_rich(name: str) -> str:
        if name == "community_summary.md":
            return _RICH_COMMUNITY_PROMPT
        return original_load_prompt(name)

    prompts_mod.load_prompt = load_prompt_rich  # type: ignore[assignment]
    namer_mod.load_prompt = load_prompt_rich  # type: ignore[assignment]

    # Bust CachedLLMService entries that used the short-summary prompt.
    original_name = namer_mod.CommunityNamer.name_communities

    async def name_communities_v3(self, repo_id: str, *, max_communities: int | None = None):  # type: ignore[no-untyped-def]
        # Re-bind default to patched constant when caller omits max_communities.
        if max_communities is None:
            max_communities = const_mod.MAX_COMMUNITIES_PER_LLM_CALL
        # Monkeypatch prompt_version by wrapping llm_service.complete for this call path:
        # simplest: temporarily patch LLMOperation creation — instead call original after
        # ensuring prompt_version is updated via wrapping complete.
        llm_service = self.llm_service
        original_complete = llm_service.complete

        async def complete_v3(repo_id_arg, operation):  # type: ignore[no-untyped-def]
            try:
                operation.prompt_version = "community_naming:v3-qa-dense"
            except Exception:
                pass
            try:
                # dataclass/replace-style objects may be frozen; set via object.__setattr__
                object.__setattr__(operation, "prompt_version", "community_naming:v3-qa-dense")
            except Exception:
                pass
            return await original_complete(repo_id_arg, operation)

        llm_service.complete = complete_v3  # type: ignore[method-assign]
        try:
            return await original_name(self, repo_id, max_communities=max_communities)
        finally:
            llm_service.complete = original_complete  # type: ignore[method-assign]

    namer_mod.CommunityNamer.name_communities = name_communities_v3  # type: ignore[assignment]
    namer_mod.CommunityNamer.summarize_communities = name_communities_v3  # type: ignore[assignment]

    _log(
        "community naming patch applied "
        f"(max={const_mod.MAX_COMMUNITIES_PER_LLM_CALL}, "
        f"batch={const_mod.COMMUNITIES_PER_BATCH}, "
        f"files={const_mod.MAX_COMMUNITY_FILES}, "
        f"symbols={const_mod.MAX_COMMUNITY_SYMBOLS}, "
        f"summary_chars={summary_chars})"
    )


def apply_all() -> None:
    os.environ.setdefault("CODEWIKI_AST_PARSE_WORKERS", "1")
    apply_ignore_patches()
    apply_parse_isolation_patch()
    apply_community_naming_patches()
