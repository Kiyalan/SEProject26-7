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


async def _run(repo_id: str, run_id: str, name_communities: bool) -> None:
    from backend.app.api.runs import (
        AnalysisRunProgress,
        _name_communities_background,
        _queued_or_skipped_community_naming,
    )
    from backend.app.database import get_store
    from backend.app.services.analyzer import AnalysisService
    from backend.app.services.async_tasks import repo_write_lock

    store = get_store()
    async with repo_write_lock(repo_id):
        progress = AnalysisRunProgress(store, run_id)
        try:
            analysis = await AnalysisService(store=store).analyze_with_community_summaries(
                repo_id,
                name_communities=False,
                run_id=run_id,
                progress_callback=progress.update,
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
        except Exception as exc:
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
