"""Run one CodeWiki analyze job in a child process.

Isolates tree-sitter / native AST crashes (SIGSEGV, exit 139) so the API
container stays alive and the run is marked failed instead of leaving a zombie.
"""
from __future__ import annotations

import asyncio
import os
import sys
import traceback
from dataclasses import asdict


def _mark_failed(run_id: str, error: str) -> None:
    from backend.app.database import get_store

    store = get_store()
    current = store.get_analysis_run(run_id)
    stats = dict(current.stats) if current is not None else {}
    stats["progress"] = {
        "stage": "failed",
        "label": "Failed",
        "message": error,
    }
    try:
        store.finish_analysis_run(run_id, status="failed", stats=stats, error=error)
    except ValueError:
        pass


def _looks_like_index(value) -> bool:
    if isinstance(value, int):
        return True
    if isinstance(value, str) and value.lstrip("-").isdigit():
        return True
    return False


async def _run(repo_id: str, run_id: str, name_communities: bool) -> None:
    from backend.app.api.runs import (
        AnalysisRunProgress,
        _name_communities_background,
        _queued_or_skipped_community_naming,
    )
    from backend.app.database import get_store
    from backend.app.services.analyzer import AnalysisService
    from backend.app.services.async_tasks import repo_write_lock
    from ops_trace import TraceSession, truncate

    store = get_store()
    trace = TraceSession(
        "build",
        repo_id=repo_id,
        run_id=run_id,
        name_communities=name_communities,
        pid=os.getpid(),
    )
    trace.emit("build.start", message="analyze worker started")

    try:
        sample_every = max(5, int(os.environ.get("CODEWIKI_OPS_TRACE_PROGRESS_EVERY") or "25"))
    except ValueError:
        sample_every = 25

    async with repo_write_lock(repo_id):
        progress = AnalysisRunProgress(store, run_id)
        state = {"last_stage": "", "n": 0}

        def traced_progress(*args, **kwargs):
            result = progress.update(*args, **kwargs)
            try:
                stage = ""
                label = ""
                message = ""
                path = ""
                index = None
                total = None
                if len(args) >= 3:
                    if _looks_like_index(args[0]):
                        index, total, path = args[0], args[1], str(args[2])
                        stage = "parse"
                        message = path
                    else:
                        stage, label, message = str(args[0]), str(args[1]), str(args[2])
                elif kwargs:
                    stage = str(kwargs.get("stage") or "")
                    label = str(kwargs.get("label") or "")
                    message = str(kwargs.get("message") or kwargs.get("path") or "")
                    path = str(kwargs.get("path") or "")
                    index = kwargs.get("index")
                    total = kwargs.get("total")

                state["n"] += 1
                stage_changed = bool(stage) and stage != state["last_stage"]
                sample_hit = state["n"] == 1 or state["n"] % sample_every == 0
                if stage_changed or sample_hit:
                    trace.emit(
                        "build.kg.progress",
                        stage=stage or state["last_stage"],
                        label=label,
                        message=truncate(message, 500),
                        path=truncate(path, 300) if path else None,
                        index=index,
                        total=total,
                        sample_n=state["n"],
                    )
                if stage:
                    state["last_stage"] = stage
            except Exception:
                pass
            return result

        try:
            analysis = await AnalysisService(store=store).analyze_with_community_summaries(
                repo_id,
                name_communities=False,
                run_id=run_id,
                progress_callback=traced_progress,
            )
            result = analysis.analysis
            naming_result = _queued_or_skipped_community_naming(repo_id) if name_communities else None
            stats = {
                **result.stats(),
                "progress": {
                    "stage": "done",
                    "label": "Done",
                    "message": (
                        f"Analysis complete: {result.node_count} nodes, "
                        f"{result.edge_count} edges, {result.community_count} communities."
                    ),
                },
            }
            if naming_result is not None:
                stats["community_naming"] = asdict(naming_result)
            store.update_analysis_run_stats(run_id, stats)
            trace.emit(
                "build.kg.done",
                status="completed",
                node_count=getattr(result, "node_count", None),
                edge_count=getattr(result, "edge_count", None),
                community_count=getattr(result, "community_count", None),
                duration_ms=trace.elapsed_ms(),
                progress_samples=state["n"],
            )
        except Exception as exc:
            trace.emit(
                "build.kg.failed",
                status="failed",
                error=truncate(exc, 2000),
                duration_ms=trace.elapsed_ms(),
            )
            _mark_failed(run_id, str(exc))
            raise

    if name_communities:
        naming_result = _queued_or_skipped_community_naming(repo_id)
        if naming_result.status == "queued":
            await _name_communities_background(repo_id)


def main() -> int:
    if len(sys.argv) < 4:
        print("usage: analyze_worker.py <repo_id> <run_id> <0|1>", file=sys.stderr)
        return 2
    os.environ.setdefault("CODEWIKI_NO_KEYRING", "1")
    os.environ.setdefault("CODEWIKI_AST_PARSE_WORKERS", "1")
    try:
        from codewiki_patches import apply_all

        apply_all()
    except Exception as ex:
        print(f"codewiki_patches failed: {ex}", file=sys.stderr)

    repo_id, run_id, flag = sys.argv[1], sys.argv[2], sys.argv[3]
    name_communities = flag in ("1", "true", "True", "yes")
    try:
        asyncio.run(_run(repo_id, run_id, name_communities))
        return 0
    except Exception:
        traceback.print_exc()
        try:
            _mark_failed(run_id, f"Analyze worker exception: {sys.exc_info()[1]}")
        except Exception:
            pass
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
