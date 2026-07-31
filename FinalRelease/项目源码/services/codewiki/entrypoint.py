"""CodeWiki container entrypoint — stabilize long analyzes on Windows/Docker.

Problems this mitigates:
1. Compose SIGTERM / recreate kills in-process analyze workers.
2. Tree-sitter AST parse can SIGSEGV (exit 139) under threaded parse — that used
   to kill the whole API process; analyze now runs in a child process.
3. Postgres status=running zombies after crash/restart → POST /analyze stuck forever.
4. Heavy dirs make scan/parse crawl on bind mounts.
"""
from __future__ import annotations

import asyncio
import faulthandler
import os
import signal
import subprocess
import sys
import traceback
from datetime import datetime, timezone
from pathlib import Path

_real_sys_exit = sys.exit
_real_os_exit = os._exit
_STORAGE = Path(os.environ.get("CODEWIKI_STORAGE_DIR", "/app/storage"))
_TRACE = _STORAGE / "exit_trace.log"
_REASON = _STORAGE / "last_shutdown_reason"
_FAULT = _STORAGE / "faulthandler.log"
_ANALYZE_WORKER = Path(__file__).resolve().with_name("analyze_worker.py")


def _log(msg: str) -> None:
    line = f"[codewiki-entrypoint] {msg}\n"
    try:
        _STORAGE.mkdir(parents=True, exist_ok=True)
        with open(_TRACE, "a", encoding="utf-8") as f:
            f.write(line)
    except Exception:
        pass
    sys.stderr.write(line)
    sys.stderr.flush()


def _write_reason(reason: str) -> None:
    try:
        _STORAGE.mkdir(parents=True, exist_ok=True)
        _REASON.write_text(reason.strip() + "\n", encoding="utf-8")
    except Exception as ex:
        _log(f"write shutdown reason failed: {ex}")


def _read_reason() -> str:
    try:
        if _REASON.is_file():
            return _REASON.read_text(encoding="utf-8").strip().lower()
    except Exception:
        pass
    return ""


def _failure_message_for_previous_death() -> str:
    """Map previous shutdown marker → user-facing analysis_run.error."""
    prev = _read_reason()
    if prev in ("sigterm", "sigint"):
        return (
            "Worker lost: container received SIGTERM/SIGINT "
            "(docker compose stop/recreate or start-dev) — please rebuild knowledge"
        )
    if prev == "running":
        return (
            "Worker lost: CodeWiki process crashed during analysis "
            "(likely AST/tree-sitter SIGSEGV, exit 139) — please rebuild knowledge"
        )
    if prev.startswith("exit:"):
        code = prev.split(":", 1)[-1]
        return (
            f"Worker lost: CodeWiki exited unexpectedly (code {code}) "
            "— please rebuild knowledge"
        )
    return (
        "Worker lost on container restart — please rebuild knowledge"
    )


def _debug_exit(code=0):
    msg = f"[DEBUG-EXIT] sys.exit({code}) called from:\n{''.join(traceback.format_stack())}"
    _log(msg)
    if code != 0:
        _write_reason(f"exit:{code}")
    _real_sys_exit(code)


def _debug_os_exit(code=0):
    msg = f"[DEBUG-EXIT] os._exit({code}) called from:\n{''.join(traceback.format_stack())}"
    _log(msg)
    if code != 0:
        _write_reason(f"exit:{code}")
    _real_os_exit(code)


def _signal_handler(signum, frame):
    name = signal.Signals(signum).name if hasattr(signal, "Signals") else str(signum)
    msg = f"[DEBUG-SIGNAL] Received {name} ({signum})\n{''.join(traceback.format_stack(frame))}"
    _log(msg)
    _write_reason(name.lower())
    # Exit 0 so a deliberate stop does not trip restart:on-failure.
    _real_sys_exit(0)


sys.exit = _debug_exit  # type: ignore[assignment]
os._exit = _debug_os_exit  # type: ignore[assignment]
signal.signal(signal.SIGTERM, _signal_handler)
signal.signal(signal.SIGINT, _signal_handler)


def _dsn_from_env() -> str | None:
    raw = (os.environ.get("CODEWIKI_DATABASE_URL") or "").strip()
    if not raw:
        return None
    for prefix in ("postgresql+psycopg://", "postgresql+asyncpg://", "postgres+psycopg://"):
        if raw.startswith(prefix):
            return "postgresql://" + raw[len(prefix) :]
    return raw


def _clear_stale_running_runs() -> None:
    """Fail every in-flight analysis on process start (workers die with the process)."""
    dsn = _dsn_from_env()
    if not dsn or not dsn.startswith("postgresql"):
        _log("skip stale-run cleanup (non-postgres DATABASE_URL)")
        return
    try:
        import psycopg
    except Exception as ex:
        _log(f"skip stale-run cleanup (psycopg missing): {ex}")
        return

    now = datetime.now(timezone.utc)
    finished = now.strftime("%Y-%m-%dT%H:%M:%S.%f+00:00")
    error = _failure_message_for_previous_death()
    try:
        with psycopg.connect(dsn, connect_timeout=10) as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    UPDATE analysis_run
                       SET status = 'failed',
                           error = COALESCE(NULLIF(error, ''), %s),
                           finished_at = %s
                     WHERE status IN ('running', 'queued', 'pending')
                    """,
                    (error, finished),
                )
                cleared = cur.rowcount or 0
                conn.commit()
            _log(f"stale-run cleanup done, rows touched={cleared}, reason={_read_reason()!r}")
    except Exception as ex:
        _log(f"stale-run cleanup failed: {ex}")


def _apply_exclude_defaults() -> None:
    """Best-effort: exclude heavy/non-source paths that slow Windows bind mounts."""
    excludes = os.environ.get(
        "CODEWIKI_EXTRA_EXCLUDES",
        "target,node_modules,dist,build,coverage,.git,.idea,.vscode,"
        "package-lock.json,yarn.lock,pnpm-lock.yaml,"
        "*.min.js,*.min.css,*.map,*.jar,*.war,*.class,"
        "*.pdf,*.png,*.jpg,*.jpeg,*.gif,*.webp,*.ico,*.mp4,*.zip,"
        "UIPrototype,.mvn",
    )
    if not excludes.strip():
        return
    try:
        cmd = ["codewiki", "config", "agent", "--exclude", excludes]
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
        _log(
            f"config agent --exclude exit={result.returncode} "
            f"out={result.stdout[:200]!r} err={result.stderr[:200]!r}"
        )
    except Exception as ex:
        _log(f"config agent --exclude skipped: {ex}")


def _patch_analyze_subprocess_isolation() -> None:
    """Run analyze in a child process so SIGSEGV does not kill the API server."""
    try:
        from backend.app.api import runs
    except Exception as ex:
        _log(f"analyze isolation patch skipped (import): {ex}")
        return

    if not _ANALYZE_WORKER.is_file():
        _log(f"analyze isolation patch skipped (missing {_ANALYZE_WORKER})")
        return

    async def _analyze_background_isolated(
        repo_id: str, run_id: str, name_communities: bool
    ) -> None:
        flag = "1" if name_communities else "0"
        _log(f"spawn analyze worker repo={repo_id} run={run_id}")
        try:
            proc = await asyncio.create_subprocess_exec(
                sys.executable,
                str(_ANALYZE_WORKER),
                repo_id,
                run_id,
                flag,
                stdout=None,
                stderr=None,
            )
            code = await proc.wait()
        except Exception as ex:
            _log(f"analyze worker spawn failed: {ex}")
            _fail_run_best_effort(
                run_id,
                f"Analyze worker failed to start: {ex}",
            )
            return

        if code == 0:
            _log(f"analyze worker finished ok run={run_id}")
            return

        # 139 = 128+11 SIGSEGV; 134 = SIGABRT; etc.
        if code == 139 or code == -11:
            err = (
                "Analyze worker crashed with SIGSEGV (exit 139) during AST parse — "
                "please rebuild knowledge"
            )
        elif code < 0:
            err = f"Analyze worker killed by signal {-code} — please rebuild knowledge"
        else:
            err = f"Analyze worker exited with code {code} — please rebuild knowledge"
        _log(f"analyze worker failed run={run_id}: {err}")
        _fail_run_best_effort(run_id, err)

    runs._analyze_background = _analyze_background_isolated  # type: ignore[assignment]
    _log("analyze isolation patch applied (subprocess worker)")


def _fail_run_best_effort(run_id: str, error: str) -> None:
    try:
        from backend.app.database import get_store

        store = get_store()
        current = store.get_analysis_run(run_id)
        stats = dict(current.stats) if current is not None else {}
        stats["progress"] = {
            "stage": "failed",
            "label": "Failed",
            "message": error,
        }
        store.finish_analysis_run(run_id, status="failed", stats=stats, error=error)
    except Exception as ex:
        _log(f"fail_run_best_effort failed: {ex}")


def _bootstrap() -> None:
    _log(f"bootstrap at {datetime.now(timezone.utc).isoformat()}")
    os.environ.setdefault("CODEWIKI_NO_KEYRING", "1")
    os.environ.setdefault("CODEWIKI_LLM__CACHE_ENABLED", "true")
    os.environ.setdefault("CODEWIKI_LLM__MAX_RETRIES", "3")
    os.environ.setdefault("CODEWIKI_LLM__TIMEOUT_SECONDS", "180")
    # Tree-sitter native parsers frequently SIGSEGV under threaded parse on Docker/Windows.
    os.environ.setdefault("CODEWIKI_AST_PARSE_WORKERS", "1")
    os.environ.setdefault("CODEWIKI_AST_PARSE_ISOLATE", "1")
    try:
        _STORAGE.mkdir(parents=True, exist_ok=True)
        fault_fh = open(_FAULT, "a", encoding="utf-8")
        faulthandler.enable(file=fault_fh, all_threads=True)
        _log(f"faulthandler enabled -> {_FAULT}")
    except Exception as ex:
        _log(f"faulthandler enable failed: {ex}")
        try:
            faulthandler.enable(all_threads=True)
        except Exception:
            pass

    _clear_stale_running_runs()
    _apply_exclude_defaults()
    _write_reason("running")


_bootstrap()

from backend.app.cli import main  # noqa: E402

try:
    from codewiki_patches import apply_all

    apply_all()
except Exception as ex:
    _log(f"codewiki_patches failed: {ex}")

_patch_analyze_subprocess_isolation()

if __name__ == "__main__":
    sys.argv[0] = "codewiki"
    main()
