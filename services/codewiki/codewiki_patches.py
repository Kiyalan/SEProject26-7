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


def apply_all() -> None:
    os.environ.setdefault("CODEWIKI_AST_PARSE_WORKERS", "1")
    apply_ignore_patches()
    apply_parse_isolation_patch()
