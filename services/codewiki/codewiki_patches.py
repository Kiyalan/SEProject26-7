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


def apply_leiden_primary_patch() -> None:
    """Prefer Leiden community detection on the AST entity graph (standard GraphRAG)."""
    algo = (os.environ.get("CODEWIKI_COMMUNITY_ALGORITHM") or "leiden").strip().lower()
    if algo in ("0", "false", "off", "louvain"):
        _log(f"leiden primary patch skipped (CODEWIKI_COMMUNITY_ALGORITHM={algo})")
        return
    try:
        from backend.app.services.community import detector as detector_mod
        import networkx as nx
    except Exception as ex:
        _log(f"leiden primary patch skipped (import): {ex}")
        return

    def _partition_leiden_first(graph, *, resolution: float = 1.0):
        if graph.number_of_nodes() == 0:
            return [], "empty"
        if graph.number_of_edges() == 0:
            return (
                [set(component) for component in nx.connected_components(graph)],
                "connected_components",
            )

        # 1) networkx Leiden (preferred)
        try:
            leiden_fn = getattr(nx.community, "leiden_communities", None)
            if leiden_fn is not None:
                communities = leiden_fn(
                    graph,
                    weight="weight",
                    resolution=resolution,
                    seed=42,
                )
                return [set(community) for community in communities], "networkx_leiden"
        except Exception as ex:
            _log(f"networkx leiden failed, trying graspologic: {ex}")

        # 2) graspologic Leiden
        try:
            leiden_communities = detector_mod._graspologic_leiden_communities(
                graph, resolution=resolution
            )
            if leiden_communities is not None:
                return leiden_communities, "graspologic_leiden"
        except Exception as ex:
            _log(f"graspologic leiden failed, falling back to louvain: {ex}")

        # 3) Louvain / greedy fallback
        try:
            communities = nx.algorithms.community.louvain_communities(
                graph,
                weight="weight",
                resolution=resolution,
                seed=42,
            )
            return [set(community) for community in communities], "networkx_louvain"
        except Exception:
            communities = nx.algorithms.community.greedy_modularity_communities(
                graph,
                weight="weight",
            )
            return [set(community) for community in communities], "networkx_greedy_modularity"

    detector_mod._partition = _partition_leiden_first  # type: ignore[assignment]
    _log("leiden primary community detection patch applied")


def apply_standard_graphrag_routes() -> None:
    """Mount standard GraphRAG build / local / global endpoints on the FastAPI app."""
    try:
        from graphrag_standard import router as standard_router
    except Exception as ex:
        _log(f"standard graphrag routes skipped (import router): {ex}")
        return

    try:
        from backend.app import main as main_mod

        original_create_app = main_mod.create_app

        def create_app_with_standard():
            app = original_create_app()
            _include_standard_router(app, standard_router)
            return app

        main_mod.create_app = create_app_with_standard  # type: ignore[assignment]
        if getattr(main_mod, "app", None) is not None:
            _include_standard_router(main_mod.app, standard_router)
        _log("standard graphrag routes mounted (/graphrag/build-standard|local-search|global-search)")
    except Exception as ex:
        _log(f"standard graphrag routes mount failed: {ex}")


def _include_standard_router(app, standard_router) -> None:
    # Avoid double-mount if apply_all runs twice.
    existing = {
        getattr(route, "path", None)
        for route in getattr(app, "routes", [])
    }
    if any(
        isinstance(path, str) and path.endswith("/graphrag/local-search")
        for path in existing
    ):
        return
    app.include_router(standard_router, prefix="/api/repos", tags=["graphrag-standard"])


def _try_wrap_retrieve_handlers() -> bool:
    """Monkey-patch upstream retrieve callables when importable."""
    from ops_trace import TraceSession, extract_retrieve_summary, truncate

    wrapped_any = False
    candidates = [
        ("backend.app.api.graphrag", ("retrieve", "graphrag_retrieve", "retrieve_endpoint")),
        ("backend.app.api.repos", ("retrieve", "graphrag_retrieve")),
        ("backend.app.services.graphrag.retrieve", ("retrieve", "run_retrieve", "search")),
        ("backend.app.services.graphrag.service", ("retrieve", "local_retrieve")),
        ("backend.app.services.retrieval", ("retrieve",)),
    ]

    import inspect

    for module_name, attr_names in candidates:
        try:
            mod = __import__(module_name, fromlist=["*"])
        except Exception:
            continue
        for attr in attr_names:
            original = getattr(mod, attr, None)
            if original is None or not callable(original):
                continue
            if getattr(original, "_codewiki_ops_traced", False):
                wrapped_any = True
                continue

            if inspect.iscoroutinefunction(original):

                async def _async_wrapped(*args, __original=original, __name=attr, **kwargs):
                    repo_id = kwargs.get("repo_id") or (args[0] if args else "")
                    query = (
                        kwargs.get("query")
                        or kwargs.get("question")
                        or kwargs.get("q")
                        or ""
                    )
                    if not query and len(args) >= 2:
                        query = args[1]
                    trace = TraceSession("query", repo_id=str(repo_id or ""), mode="retrieve")
                    trace.emit(
                        "query.start",
                        query=truncate(query, 2000),
                        handler=__name,
                        message="retrieve handler started",
                    )
                    try:
                        result = await __original(*args, **kwargs)
                        summary = extract_retrieve_summary(result)
                        trace.emit("query.context", **summary)
                        trace.emit(
                            "query.done",
                            status="ok",
                            duration_ms=trace.elapsed_ms(),
                            context_count=summary.get("contexts_count")
                            or summary.get("chunks_count")
                            or summary.get("results_count"),
                        )
                        return result
                    except Exception as exc:
                        trace.emit(
                            "query.done",
                            status="error",
                            error=truncate(exc, 2000),
                            duration_ms=trace.elapsed_ms(),
                        )
                        raise

                _async_wrapped._codewiki_ops_traced = True  # type: ignore[attr-defined]
                setattr(mod, attr, _async_wrapped)
            else:

                def _sync_wrapped(*args, __original=original, __name=attr, **kwargs):
                    repo_id = kwargs.get("repo_id") or (args[0] if args else "")
                    query = (
                        kwargs.get("query")
                        or kwargs.get("question")
                        or kwargs.get("q")
                        or ""
                    )
                    if not query and len(args) >= 2:
                        query = args[1]
                    trace = TraceSession("query", repo_id=str(repo_id or ""), mode="retrieve")
                    trace.emit(
                        "query.start",
                        query=truncate(query, 2000),
                        handler=__name,
                        message="retrieve handler started",
                    )
                    try:
                        result = __original(*args, **kwargs)
                        summary = extract_retrieve_summary(result)
                        trace.emit("query.context", **summary)
                        trace.emit(
                            "query.done",
                            status="ok",
                            duration_ms=trace.elapsed_ms(),
                            context_count=summary.get("contexts_count")
                            or summary.get("chunks_count")
                            or summary.get("results_count"),
                        )
                        return result
                    except Exception as exc:
                        trace.emit(
                            "query.done",
                            status="error",
                            error=truncate(exc, 2000),
                            duration_ms=trace.elapsed_ms(),
                        )
                        raise

                _sync_wrapped._codewiki_ops_traced = True  # type: ignore[attr-defined]
                setattr(mod, attr, _sync_wrapped)

            wrapped_any = True
            _log(f"ops trace wrapped {module_name}.{attr}")

    return wrapped_any


def _install_ops_trace_middleware(app) -> None:
    """HTTP middleware fallback for retrieve / vanilla graphrag build."""
    if getattr(app, "_codewiki_ops_middleware", False):
        return

    from starlette.requests import Request
    from starlette.responses import Response

    from ops_trace import TraceSession, extract_retrieve_summary, truncate

    @app.middleware("http")
    async def codewiki_ops_trace_middleware(request: Request, call_next):
        path = request.url.path or ""
        is_retrieve = path.endswith("/graphrag/retrieve")
        is_build = path.endswith("/graphrag/build") and not path.endswith("/graphrag/build-standard")
        if request.method.upper() != "POST" or not (is_retrieve or is_build):
            return await call_next(request)

        body_bytes = await request.body()

        async def receive():
            return {"type": "http.request", "body": body_bytes, "more_body": False}

        request = Request(request.scope, receive)

        parts = [p for p in path.split("/") if p]
        repo_id = ""
        if "repos" in parts:
            try:
                repo_id = parts[parts.index("repos") + 1]
            except (ValueError, IndexError):
                repo_id = ""

        req_json: dict = {}
        try:
            import json as _json

            if body_bytes:
                parsed = _json.loads(body_bytes.decode("utf-8"))
                if isinstance(parsed, dict):
                    req_json = parsed
        except Exception:
            req_json = {}

        kind = "query" if is_retrieve else "build"
        mode = "retrieve" if is_retrieve else "graphrag_build"
        query = req_json.get("query") or req_json.get("question") or req_json.get("q") or ""
        trace = TraceSession(kind, repo_id=repo_id, mode=mode, path=path)
        if is_retrieve:
            trace.emit(
                "query.start",
                query=truncate(query, 2000),
                request= {k: truncate(v, 500) if isinstance(v, str) else v for k, v in list(req_json.items())[:20]},
                message="retrieve HTTP middleware",
            )
        else:
            trace.emit(
                "build.index.start",
                message="upstream graphrag/build",
                request={k: v for k, v in list(req_json.items())[:20]},
            )

        try:
            response = await call_next(request)
        except Exception as exc:
            phase = "query.done" if is_retrieve else "build.index.done"
            trace.emit(phase, status="error", error=truncate(exc, 2000), duration_ms=trace.elapsed_ms())
            raise

        resp_body = b""
        if hasattr(response, "body_iterator"):
            chunks = []
            async for chunk in response.body_iterator:
                chunks.append(chunk if isinstance(chunk, (bytes, bytearray)) else bytes(chunk))
            resp_body = b"".join(chunks)
        elif getattr(response, "body", None) is not None:
            resp_body = response.body

        parsed_resp = None
        try:
            import json as _json

            if resp_body:
                parsed_resp = _json.loads(resp_body.decode("utf-8"))
        except Exception:
            parsed_resp = None

        if is_retrieve:
            summary = extract_retrieve_summary(parsed_resp if isinstance(parsed_resp, dict) else {"raw": parsed_resp})
            trace.emit("query.context", **summary)
            trace.emit(
                "query.done",
                status="ok" if response.status_code < 400 else "error",
                http_status=response.status_code,
                duration_ms=trace.elapsed_ms(),
                context_count=summary.get("contexts_count")
                or summary.get("chunks_count")
                or summary.get("results_count"),
            )
        else:
            if isinstance(parsed_resp, dict):
                trace.emit("build.index.done", status="ok" if response.status_code < 400 else "error",
                           http_status=response.status_code, duration_ms=trace.elapsed_ms(), **{
                               k: parsed_resp.get(k)
                               for k in (
                                   "status",
                                   "chunk_count",
                                   "embedding_count",
                                   "entity_count",
                                   "community_count",
                               )
                               if k in parsed_resp
                           })
            else:
                trace.emit(
                    "build.index.done",
                    status="ok" if response.status_code < 400 else "error",
                    http_status=response.status_code,
                    duration_ms=trace.elapsed_ms(),
                    raw=truncate(resp_body[:1000], 1000),
                )

        return Response(
            content=resp_body,
            status_code=response.status_code,
            headers=dict(response.headers),
            media_type=response.media_type,
            background=getattr(response, "background", None),
        )

    app._codewiki_ops_middleware = True  # type: ignore[attr-defined]
    _log("ops trace HTTP middleware installed for /graphrag/retrieve and /graphrag/build")


def apply_ops_trace_patches() -> None:
    """Trace retrieve (+ upstream build) inside the container."""
    try:
        wrapped = _try_wrap_retrieve_handlers()
        if not wrapped:
            _log("ops trace: no upstream retrieve symbol found; using HTTP middleware")
    except Exception as ex:
        _log(f"ops trace retrieve wrap skipped: {ex}")

    try:
        from backend.app import main as main_mod

        original_create_app = main_mod.create_app

        def create_app_with_ops_trace():
            app = original_create_app()
            _install_ops_trace_middleware(app)
            return app

        main_mod.create_app = create_app_with_ops_trace  # type: ignore[assignment]
        if getattr(main_mod, "app", None) is not None:
            _install_ops_trace_middleware(main_mod.app)
        _log("ops trace create_app wrapper applied")
    except Exception as ex:
        _log(f"ops trace middleware patch failed: {ex}")


# pgvector HNSW: vector ≤2000 dims; halfvec ≤4000 dims (pgvector ≥0.7).
_PGVECTOR_HNSW_MAX_DIMS = 2000
_PGVECTOR_HALFVEC_HNSW_MAX_DIMS = 4000


def _use_pgvector_halfvec(dimensions: int) -> bool:
    """Use halfvec storage/index when float32 vector HNSW cannot index the dim."""
    mode = (os.environ.get("CODEWIKI_PGVECTOR_HALFVEC") or "auto").strip().lower()
    if mode in ("1", "true", "yes", "on", "always"):
        return True
    if mode in ("0", "false", "no", "off", "never"):
        return False
    return dimensions > _PGVECTOR_HNSW_MAX_DIMS


def apply_pgvector_halfvec_patch() -> None:
    """Store high-dim embeddings as halfvec so HNSW works above 2000 dims.

    Upstream CodeWiki always creates ``vector(N)`` + ``vector_cosine_ops`` HNSW,
    which fails for models like Qwen3-Embedding-4B (2560) / 8B (4096).
    halfvec HNSW supports up to 4000 dims.
    """
    try:
        from backend.app.db.repositories import embeddings as emb_mod
        from backend.app.db.repositories.embeddings import CodeChunkEmbeddingRepositoryMixin
        from sqlalchemy import text
    except Exception as ex:
        _log(f"pgvector halfvec patch skipped (import): {ex}")
        return

    original_ensure = emb_mod._ensure_pg_vector_table
    original_search = CodeChunkEmbeddingRepositoryMixin._search_code_chunk_embeddings_pgvector

    def _pg_embedding_udt_name(session, vec_table: str) -> str | None:
        row = session.execute(
            text(
                """
                SELECT t.typname
                  FROM pg_attribute a
                  JOIN pg_class c ON a.attrelid = c.oid
                  JOIN pg_type t ON a.atttypid = t.oid
                 WHERE c.relname = :table_name
                   AND a.attname = 'embedding'
                   AND a.attnum > 0
                   AND NOT a.attisdropped
                """
            ),
            {"table_name": vec_table},
        ).first()
        return str(row[0]) if row and row[0] else None

    def _ensure_pg_vector_table(session, dimensions: int, *, pgvector_schema: str) -> None:
        if dimensions > _PGVECTOR_HALFVEC_HNSW_MAX_DIMS:
            raise ValueError(
                f"Embedding dimensions {dimensions} exceed pgvector halfvec HNSW limit "
                f"({_PGVECTOR_HALFVEC_HNSW_MAX_DIMS}). Use Qwen3-Embedding-4B (2560) or "
                "a Matryoshka/truncated dimension ≤4000."
            )
        use_half = _use_pgvector_halfvec(dimensions)
        vec_table = emb_mod._vec_table_name(dimensions)
        schema = emb_mod._quote_identifier(pgvector_schema)
        col_type = f"{schema}.halfvec({dimensions})" if use_half else f"{schema}.vector({dimensions})"
        ops = f"{schema}.halfvec_cosine_ops" if use_half else f"{schema}.vector_cosine_ops"
        expected_udt = "halfvec" if use_half else "vector"

        if emb_mod._pg_vector_table_exists(session, vec_table):
            actual_udt = _pg_embedding_udt_name(session, vec_table)
            if actual_udt and actual_udt != expected_udt:
                _log(
                    f"recreating {vec_table}: embedding type {actual_udt} -> {expected_udt}"
                )
                session.execute(text(f"DROP TABLE IF EXISTS {vec_table} CASCADE"))

        session.execute(
            text(
                f"""
                CREATE TABLE IF NOT EXISTS {vec_table} (
                  id BIGSERIAL PRIMARY KEY,
                  repo_id TEXT NOT NULL,
                  model TEXT NOT NULL,
                  chunk_id TEXT NOT NULL,
                  embedding {col_type} NOT NULL
                )
                """
            )
        )
        session.execute(
            text(
                f"""
                CREATE INDEX IF NOT EXISTS idx_{vec_table}_repo_model
                ON {vec_table} (repo_id, model)
                """
            )
        )
        session.execute(
            text(
                f"""
                CREATE INDEX IF NOT EXISTS idx_{vec_table}_embedding_hnsw
                ON {vec_table} USING hnsw (embedding {ops})
                """
            )
        )

    def _search_code_chunk_embeddings_pgvector(
        self,
        repo_id: str,
        *,
        model: str,
        query_embedding: list[float],
        limit: int,
    ):
        dimensions = len(query_embedding)
        use_half = _use_pgvector_halfvec(dimensions)
        vec_table = emb_mod._vec_table_name(dimensions)
        pgvector_schema = emb_mod._quote_identifier(self.pgvector_schema)
        cast_type = f"{pgvector_schema}.halfvec" if use_half else f"{pgvector_schema}.vector"
        with self.orm_session() as session:
            if not emb_mod._pg_vector_table_exists(session, vec_table):
                return []
            rows = (
                session.execute(
                    text(
                        f"""
                    SELECT
                        chunk_id,
                        embedding OPERATOR({pgvector_schema}.<=>)
                          CAST(:embedding AS {cast_type}) AS distance
                    FROM {vec_table}
                    WHERE repo_id = :repo_id
                      AND model = :model
                    ORDER BY embedding OPERATOR({pgvector_schema}.<=>)
                      CAST(:embedding AS {cast_type})
                    LIMIT :limit
                    """
                    ),
                    {
                        "embedding": emb_mod._pgvector_literal(query_embedding),
                        "repo_id": repo_id,
                        "model": model,
                        "limit": limit,
                    },
                )
                .mappings()
                .all()
            )

        from backend.app.models import CodeChunkSearchHit

        chunk_ids = [row["chunk_id"] for row in rows]
        chunks = {chunk.id: chunk for chunk in self.get_code_chunks_by_ids(repo_id, chunk_ids)}
        hits: list = []
        for row in rows:
            chunk = chunks.get(row["chunk_id"])
            if chunk is None:
                continue
            distance = float(row["distance"])
            score = max(0.0, 1.0 - distance)
            hits.append(CodeChunkSearchHit(chunk=chunk, score=score, match_type="pgvector"))
        return hits[:limit]

    emb_mod._ensure_pg_vector_table = _ensure_pg_vector_table  # type: ignore[assignment]
    CodeChunkEmbeddingRepositoryMixin._search_code_chunk_embeddings_pgvector = (  # type: ignore[assignment]
        _search_code_chunk_embeddings_pgvector
    )
    # Keep references so linters/tests can see originals were considered.
    _ = (original_ensure, original_search)
    _log(
        "pgvector halfvec patch applied "
        f"(mode={os.environ.get('CODEWIKI_PGVECTOR_HALFVEC', 'auto')!r}, "
        f"hnsw vector≤{_PGVECTOR_HNSW_MAX_DIMS}, halfvec≤{_PGVECTOR_HALFVEC_HNSW_MAX_DIMS})"
    )


def apply_all() -> None:
    os.environ.setdefault("CODEWIKI_AST_PARSE_WORKERS", "1")
    os.environ.setdefault("CODEWIKI_COMMUNITY_ALGORITHM", "leiden")
    apply_ignore_patches()
    apply_parse_isolation_patch()
    apply_leiden_primary_patch()
    apply_pgvector_halfvec_patch()
    apply_standard_graphrag_routes()
    apply_ops_trace_patches()
