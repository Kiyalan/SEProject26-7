"""Standard GraphRAG build + Local/Global search on CodeWiki AST entity graph.

Mounted under /api/repos/{repo_id}/graphrag/* by codewiki_patches.
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import random
import re
import sys
from pathlib import Path
from typing import Any

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

ENTITY_PREFIX = "__entity__/"
ENTITY_NODE_TYPES = {
    "file",
    "config",
    "class",
    "function",
    "method",
    "schema",
    "endpoint",
    "module",
    "interface",
}
MAX_ENTITY_NEIGHBORS = 12
MAX_LOCAL_ENTITIES = 24
MAX_LOCAL_COMMUNITIES = 12
MAX_LOCAL_EDGES = 60
MAX_CODE_WINDOWS = 12
MAX_CODE_WINDOW_CHARS = 12000
DEFAULT_MAP_BATCH = 4
HELPFULNESS_RE = re.compile(r'"helpfulness"\s*:\s*(\d+(?:\.\d+)?)', re.I)
_NON_SOURCE_SUFFIXES = (
    ".html",
    ".htm",
    ".css",
    ".gif",
    ".png",
    ".jpg",
    ".jpeg",
    ".webp",
    ".ico",
    ".map",
    ".min.js",
    ".min.css",
)

router = APIRouter()


def _log(msg: str) -> None:
    sys.stderr.write(f"[graphrag-standard] {msg}\n")
    sys.stderr.flush()


class BuildStandardRequest(BaseModel):
    include_embeddings: bool = True
    max_entities: int = 4000


class LocalSearchRequest(BaseModel):
    query: str
    max_hops: int = 2
    top_k: int = 20


class GlobalSearchRequest(BaseModel):
    query: str
    level: int = 0
    map_batch_size: int = DEFAULT_MAP_BATCH
    dynamic_selection: bool = True
    max_map_batches: int = 8


# ---------------------------------------------------------------------------
# Build: entity description docs + embeddings
# ---------------------------------------------------------------------------


async def build_standard_index(
    repo_id: str,
    *,
    include_embeddings: bool = True,
    max_entities: int = 4000,
) -> dict[str, Any]:
    from backend.app.config import get_settings
    from backend.app.database import CodeChunkRecord, get_store
    from backend.app.services.embedding_index import EmbeddingIndex
    from backend.app.services.graphrag.utils import estimate_tokens, stable_id
    from backend.app.services.llm.gateway import LLMGateway
    from ops_trace import TraceSession, truncate

    store = get_store()
    repo = store.get_repo(repo_id)
    if repo is None:
        raise ValueError(f"Repository not found: {repo_id}")

    trace = TraceSession(
        "build",
        repo_id=repo_id,
        include_embeddings=include_embeddings,
        max_entities=max_entities,
        repo_name=getattr(repo, "name", None),
        repo_path=getattr(repo, "path", None),
    )

    nodes, edges = store.get_graph(repo_id)
    if not nodes:
        raise ValueError("Run analysis before standard GraphRAG build.")

    communities = store.list_graph_communities(repo_id)
    trace.emit(
        "build.index.start",
        node_count=len(nodes),
        edge_count=len(edges),
        community_count=len(communities),
        message="standard GraphRAG index build started",
    )

    community_by_node: dict[str, list[Any]] = {}
    for community in communities:
        for node_id in community.node_ids or []:
            community_by_node.setdefault(str(node_id), []).append(community)

    adj: dict[str, list[tuple[str, str]]] = {}
    for edge in edges:
        adj.setdefault(edge.source_id, []).append((edge.type, edge.target_id))
        adj.setdefault(edge.target_id, []).append((f"rev:{edge.type}", edge.source_id))

    node_by_id = {node.id: node for node in nodes}
    entity_nodes = [
        node
        for node in nodes
        if node.type in ENTITY_NODE_TYPES and not (node.metadata or {}).get("external")
    ]
    entity_nodes = sorted(
        entity_nodes,
        key=lambda n: (0 if n.type in {"class", "function", "method", "endpoint"} else 1, n.name or ""),
    )[: max(1, max_entities)]

    type_counts: dict[str, int] = {}
    for node in entity_nodes:
        type_counts[node.type] = type_counts.get(node.type, 0) + 1
    trace.emit(
        "build.index.entities",
        entity_count=len(entity_nodes),
        type_counts=type_counts,
        sample_names=[n.name for n in entity_nodes[:20]],
    )

    entity_chunks: list[CodeChunkRecord] = []
    for node in entity_nodes:
        content = _entity_document(node, adj, node_by_id, community_by_node)
        content_hash = hashlib.sha256(content.encode("utf-8")).hexdigest()
        file_path = f"{ENTITY_PREFIX}{node.type}/{node.id}"
        chunk_id = stable_id(repo_id, "entity", node.id, content_hash)
        entity_chunks.append(
            CodeChunkRecord(
                id=chunk_id,
                repo_id=repo_id,
                node_id=node.id,
                file_path=file_path,
                start_line=node.start_line or 1,
                end_line=node.end_line or (node.start_line or 1),
                content=content,
                content_hash=content_hash,
                token_count=estimate_tokens(content),
            )
        )

    # Keep non-entity chunks (source windows) if present; replace entity docs.
    existing = store.list_code_chunks(repo_id)
    kept = [c for c in existing if not str(c.file_path or "").startswith(ENTITY_PREFIX)]
    merged = kept + entity_chunks
    store.sync_code_chunks(repo_id, merged)
    trace.emit(
        "build.index.chunks",
        entity_chunks=len(entity_chunks),
        source_chunk_count=len(kept),
        chunk_count=len(merged),
        existing_before=len(existing),
    )

    embedding_count = 0
    embedding_model: str | None = None
    embedding_error: str | None = None
    if include_embeddings and entity_chunks:
        trace.emit("build.index.embed", status="started", entity_chunks=len(entity_chunks))
        try:
            settings = get_settings()
            llm = LLMGateway(settings)
            # Embed entity docs only — Local Search must not be pure source-chunk retrieval.
            result = await EmbeddingIndex(store, llm).build(repo_id, entity_chunks)
            embedding_count = result.count
            embedding_model = result.model
            trace.emit(
                "build.index.embed",
                status="done",
                embedding_count=embedding_count,
                embedding_model=embedding_model,
            )
        except Exception as ex:
            embedding_error = str(ex)
            _log(f"entity embedding failed (docs still saved): {ex}")
            trace.emit(
                "build.index.embed",
                status="error",
                error=truncate(ex, 2000),
            )

    payload = {
        "repo_id": repo_id,
        "status": "built_without_embeddings" if embedding_error else "built",
        "entity_count": len(entity_chunks),
        "chunk_count": len(merged),
        "source_chunk_count": len(kept),
        "embedding_count": embedding_count,
        "embedding_model": embedding_model,
        "embedding_error": embedding_error,
        "community_count": len(communities),
    }
    trace.emit("build.index.done", duration_ms=trace.elapsed_ms(), **payload)
    return payload


def _entity_document(node, adj, node_by_id, community_by_node) -> str:
    lines = [
        f"Entity: {node.name}",
        f"Type: {node.type}",
        f"File: {node.file_path or ''}",
        f"Lines: {node.start_line or ''}-{node.end_line or ''}",
        f"Language: {node.language or ''}",
        f"SymbolId: {node.symbol_id or ''}",
    ]
    communities = community_by_node.get(node.id, [])
    if communities:
        names = []
        for community in communities[:3]:
            label = community.name or community.id
            summary = (community.summary or "").strip().replace("\n", " ")
            if len(summary) > 180:
                summary = summary[:180] + "…"
            names.append(f"{label}: {summary}" if summary else str(label))
        lines.append("Communities: " + " | ".join(names))

    neighbors = adj.get(node.id, [])[:MAX_ENTITY_NEIGHBORS]
    if neighbors:
        rel_lines = []
        for edge_type, other_id in neighbors:
            other = node_by_id.get(other_id)
            other_name = other.name if other is not None else other_id
            other_type = other.type if other is not None else "?"
            rel_lines.append(f"- {edge_type} → {other_name} ({other_type})")
        lines.append("Relations:\n" + "\n".join(rel_lines))
    return "\n".join(lines) + "\n"


def _is_source_path(file_path: str | None) -> bool:
    """Skip reports/assets; keep real source/config paths for code windows."""
    path = (file_path or "").replace("\\", "/").strip()
    if not path or path.startswith(ENTITY_PREFIX):
        return False
    lowered = path.lower()
    if lowered.startswith("reports/") or "/reports/" in lowered:
        return False
    return not any(lowered.endswith(suffix) for suffix in _NON_SOURCE_SUFFIXES)


def _read_repo_slice(
    repo_path: str | None,
    file_path: str | None,
    start_line: int | None,
    end_line: int | None,
    *,
    max_chars: int = MAX_CODE_WINDOW_CHARS,
) -> tuple[str, int, int] | None:
    if not repo_path or not _is_source_path(file_path):
        return None
    rel = (file_path or "").replace("\\", "/").lstrip("/")
    if not rel or ".." in rel.split("/"):
        return None
    path = Path(repo_path) / rel
    if not path.is_file():
        return None
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None
    lines = text.splitlines()
    if not lines:
        return None
    start = max(1, int(start_line or 1))
    end = int(end_line) if end_line else len(lines)
    end = min(max(start, end), len(lines))
    snippet = "\n".join(lines[start - 1 : end])
    if len(snippet) > max_chars:
        snippet = snippet[:max_chars] + "\n…"
    return snippet, start, end


def _entity_source_window(store, repo_id: str, repo_path: str | None, node) -> dict[str, Any] | None:
    """Resolve source text for an entity: stored source chunks first, then repo file slice."""
    node_id = getattr(node, "id", None)
    if node_id:
        for chunk in store.get_code_chunks_by_node_ids(repo_id, [node_id]):
            chunk_path = str(chunk.file_path or "")
            if chunk_path.startswith(ENTITY_PREFIX) or not _is_source_path(chunk_path):
                continue
            content = (chunk.content or "").strip()
            if not content:
                continue
            return {
                "file": chunk.file_path,
                "line": chunk.start_line or 1,
                "endLine": chunk.end_line or chunk.start_line or 1,
                "content": content[:MAX_CODE_WINDOW_CHARS],
            }

    sliced = _read_repo_slice(
        repo_path,
        getattr(node, "file_path", None),
        getattr(node, "start_line", None),
        getattr(node, "end_line", None),
    )
    if sliced is None:
        return None
    content, start, end = sliced
    return {
        "file": getattr(node, "file_path", None),
        "line": start,
        "endLine": end,
        "content": content,
    }


# ---------------------------------------------------------------------------
# Local Search
# ---------------------------------------------------------------------------


async def local_search(repo_id: str, query: str, *, max_hops: int = 2, top_k: int = 20) -> dict[str, Any]:
    from backend.app.config import get_settings
    from backend.app.database import get_store
    from backend.app.services.embedding_index import EmbeddingIndex
    from backend.app.services.file_roles import filter_wiki_graph
    from backend.app.services.graphrag.expansion import expand, related_edges
    from backend.app.services.graphrag.models import NodeHit
    from backend.app.services.llm.gateway import LLMGateway
    from ops_trace import TraceSession, summarize_contexts, summarize_entities, truncate

    store = get_store()
    repo = store.get_repo(repo_id)
    if repo is None:
        raise ValueError(f"Repository not found: {repo_id}")

    query = (query or "").strip()
    if not query:
        raise ValueError("query is required")

    trace = TraceSession(
        "query",
        repo_id=repo_id,
        mode="local",
        max_hops=max_hops,
        top_k=top_k,
    )
    trace.emit("query.start", query=truncate(query, 2000), message="local search started")

    graph_nodes, graph_edges = store.get_graph(repo_id)
    if not graph_nodes:
        raise ValueError("Run analysis before local search.")
    nodes, edges = filter_wiki_graph(graph_nodes, graph_edges)
    node_by_id = {node.id: node for node in nodes}

    entity_chunks = [
        c for c in store.list_code_chunks(repo_id) if str(c.file_path or "").startswith(ENTITY_PREFIX)
    ]
    if not entity_chunks:
        raise ValueError("Entity embeddings missing. Run POST .../graphrag/build-standard first.")

    settings = get_settings()
    llm = LLMGateway(settings)
    index = EmbeddingIndex(store, llm)
    hits = await index.search(repo_id, query, entity_chunks, limit=max(top_k * 2, 24))

    seed_hits: dict[str, NodeHit] = {}
    entity_scores: dict[str, float] = {}
    for hit in hits:
        chunk = hit.chunk
        if not str(chunk.file_path or "").startswith(ENTITY_PREFIX):
            continue
        node_id = chunk.node_id
        if not node_id or node_id not in node_by_id:
            continue
        score = float(getattr(hit, "score", 0.0) or 0.0)
        entity_scores[node_id] = max(entity_scores.get(node_id, 0.0), score)
        prev = seed_hits.get(node_id)
        if prev is None or score > prev.score:
            seed_hits[node_id] = NodeHit(node_id=node_id, score=score, reasons={"entity_vector"})

    if not seed_hits:
        # Fallback: name substring match on entities
        lower = query.lower()
        for node in nodes:
            if node.type not in ENTITY_NODE_TYPES:
                continue
            name = (node.name or "").lower()
            if name and (name in lower or any(t in name for t in lower.split() if len(t) >= 3)):
                seed_hits[node.id] = NodeHit(node_id=node.id, score=0.35, reasons={"name_match"})
                entity_scores[node.id] = 0.35
                if len(seed_hits) >= top_k:
                    break

    if not seed_hits:
        raise ValueError("Local search found no matching entities for the query.")

    seed_hits = dict(
        sorted(seed_hits.items(), key=lambda item: item[1].score, reverse=True)[:MAX_LOCAL_ENTITIES]
    )
    seed_entities = []
    for node_id, hit in seed_hits.items():
        node = node_by_id.get(node_id)
        seed_entities.append(
            {
                "id": node_id,
                "name": getattr(node, "name", None),
                "type": getattr(node, "type", None),
                "score": round(float(hit.score), 4),
                "reasons": sorted(hit.reasons) if hit.reasons else [],
                "file": getattr(node, "file_path", None),
            }
        )
    trace.emit(
        "query.entities",
        seed_count=len(seed_entities),
        vector_hit_count=len(hits),
        entities=summarize_entities(seed_entities),
    )

    max_hops = max(0, min(int(max_hops), 4))
    selected_ids, hops, scores = expand(seed_hits, edges, max_hops=max_hops)
    graph_edges = related_edges(edges, selected_ids)

    communities = store.list_graph_communities(repo_id)
    selected_communities = []
    for community in communities:
        member_ids = set(community.node_ids or [])
        overlap = member_ids & selected_ids
        if not overlap:
            continue
        weight = len(overlap) / max(1, len(member_ids))
        selected_communities.append((weight, community))
    selected_communities.sort(key=lambda item: (-item[0], item[1].level or 0, item[1].rank or 0))
    selected_communities = selected_communities[:MAX_LOCAL_COMMUNITIES]

    trace.emit(
        "query.expand",
        expanded_count=len(selected_ids),
        max_hops=max_hops,
        hop_summary={nid: hops.get(nid) for nid in list(selected_ids)[:40]},
        community_count=len(selected_communities),
        communities=[
            {
                "id": c.id,
                "name": c.name,
                "weight": round(weight, 4),
                "level": c.level,
            }
            for weight, c in selected_communities
        ],
    )

    degree: dict[str, int] = {node.id: 0 for node in nodes}
    for edge in edges:
        if edge.source_id in degree:
            degree[edge.source_id] += 1
        if edge.target_id in degree:
            degree[edge.target_id] += 1
    max_degree = max(degree.values()) if degree else 1

    ranked_entities: list[tuple[float, str]] = []
    for node_id in selected_ids:
        vec = entity_scores.get(node_id, 0.0)
        hop = hops.get(node_id, 99)
        graph_prox = 1.0 / (hop + 1)
        centrality = (degree.get(node_id, 0) / max_degree) if max_degree else 0.0
        score = 0.45 * vec + 0.30 * graph_prox + 0.15 * centrality + 0.10 * scores.get(node_id, 0.0)
        ranked_entities.append((score, node_id))
    ranked_entities.sort(key=lambda item: (-item[0], item[1]))

    candidate_entities = []
    for score, node_id in ranked_entities[:MAX_LOCAL_ENTITIES]:
        node = node_by_id.get(node_id)
        candidate_entities.append(
            {
                "id": node_id,
                "name": getattr(node, "name", None),
                "type": getattr(node, "type", None),
                "score": round(float(score), 4),
                "file": getattr(node, "file_path", None),
            }
        )
    trace.emit(
        "query.candidates",
        ranked_entities=summarize_entities(candidate_entities),
        relation_edge_count=min(len(graph_edges), MAX_LOCAL_EDGES),
        community_candidates=[
            {"id": c.id, "name": c.name, "weight": round(w, 4)} for w, c in selected_communities
        ],
    )

    contexts: list[dict[str, Any]] = []
    for score, node_id in ranked_entities[:MAX_LOCAL_ENTITIES]:
        node = node_by_id.get(node_id)
        if node is None:
            continue
        entity_docs = [c for c in entity_chunks if c.node_id == node_id]
        content = entity_docs[0].content if entity_docs else f"{node.type} {node.name}"
        contexts.append(
            {
                "file": node.file_path or f"entity/{node.id}",
                "line": node.start_line or 1,
                "endLine": node.end_line or (node.start_line or 1),
                "symbolName": node.name,
                "symbolKind": node.type,
                "content": content,
                "score": round(float(score) * 100, 2),
                "retrievalType": "vector",
                "sourceType": "entity",
            }
        )

    for weight, community in selected_communities:
        summary = (community.summary or "").strip()
        name = community.name or community.id
        contexts.append(
            {
                "file": f"codewiki/community/{name}",
                "line": 1,
                "endLine": 1,
                "symbolName": name,
                "symbolKind": "community",
                "content": f"community: {name}\nlevel={community.level}\n{summary}",
                "score": round(50 + weight * 40, 2),
                "retrievalType": "community",
                "sourceType": "community_report",
            }
        )

    rel_lines = []
    for edge in graph_edges[:MAX_LOCAL_EDGES]:
        src = node_by_id.get(edge.source_id)
        tgt = node_by_id.get(edge.target_id)
        src_name = src.name if src else edge.source_id
        tgt_name = tgt.name if tgt else edge.target_id
        rel_lines.append(f"- {src_name} -[{edge.type}]-> {tgt_name}")
    if rel_lines:
        contexts.append(
            {
                "file": "codewiki/graph-relationships",
                "line": 1,
                "endLine": 1,
                "content": "Graph relationships:\n" + "\n".join(rel_lines),
                "score": 90,
                "retrievalType": "graph",
                "sourceType": "relationship",
            }
        )

    # Supplement entity retrieval with corresponding source code (disk fallback when
    # only __entity__ docs are indexed). Embedding still uses entity docs only.
    ordered_for_code: list[tuple[float, str]] = []
    seen_for_code: set[str] = set()
    for score, node_id in ranked_entities:
        if node_id in seed_hits and node_id not in seen_for_code:
            ordered_for_code.append((score, node_id))
            seen_for_code.add(node_id)
    for score, node_id in ranked_entities:
        if node_id not in seen_for_code:
            ordered_for_code.append((score, node_id))
            seen_for_code.add(node_id)

    code_added = 0
    seen_windows: set[tuple[str | None, int, int]] = set()
    for score, node_id in ordered_for_code:
        if code_added >= MAX_CODE_WINDOWS:
            break
        node = node_by_id.get(node_id)
        if node is None:
            continue
        window = _entity_source_window(store, repo_id, getattr(repo, "path", None), node)
        if window is None:
            continue
        key = (window.get("file"), int(window["line"]), int(window["endLine"]))
        if key in seen_windows:
            continue
        seen_windows.add(key)
        contexts.append(
            {
                "file": window["file"],
                "line": window["line"],
                "endLine": window["endLine"],
                "symbolName": node.name,
                "symbolKind": node.type,
                "content": window["content"],
                "score": round(float(score) * 80, 2),
                "retrievalType": "graph",
                "sourceType": "code_window",
            }
        )
        code_added += 1

    result = {
        "repo_id": repo_id,
        "query": query,
        "mode": "local",
        "seed_count": len(seed_hits),
        "expanded_count": len(selected_ids),
        "community_count": len(selected_communities),
        "contexts": contexts,
    }
    trace.emit(
        "query.context",
        context_count=len(contexts),
        contexts=summarize_contexts(contexts),
    )
    trace.emit(
        "query.done",
        status="ok",
        seed_count=len(seed_hits),
        expanded_count=len(selected_ids),
        community_count=len(selected_communities),
        context_count=len(contexts),
        duration_ms=trace.elapsed_ms(),
    )
    return result


# ---------------------------------------------------------------------------
# Global Search + Dynamic Community Selection
# ---------------------------------------------------------------------------


async def global_search(
    repo_id: str,
    query: str,
    *,
    level: int = 0,
    map_batch_size: int = DEFAULT_MAP_BATCH,
    dynamic_selection: bool = True,
    max_map_batches: int = 8,
) -> dict[str, Any]:
    from backend.app.config import get_settings
    from backend.app.database import get_store
    from backend.app.services.llm.gateway import LLMGateway
    from ops_trace import TraceSession, summarize_contexts, truncate

    store = get_store()
    repo = store.get_repo(repo_id)
    if repo is None:
        raise ValueError(f"Repository not found: {repo_id}")

    query = (query or "").strip()
    if not query:
        raise ValueError("query is required")

    trace = TraceSession(
        "query",
        repo_id=repo_id,
        mode="global",
        level=level,
        dynamic_selection=dynamic_selection,
        map_batch_size=map_batch_size,
        max_map_batches=max_map_batches,
    )
    trace.emit("query.start", query=truncate(query, 2000), message="global search started")

    communities = store.list_graph_communities(repo_id)
    if not communities:
        raise ValueError("No communities available. Run analysis with community naming first.")

    level = max(0, int(level))
    roots = [c for c in communities if int(c.level or 0) == level]
    if not roots:
        roots = [c for c in communities if int(c.level or 0) == 0] or list(communities)

    settings = get_settings()
    llm = LLMGateway(settings)

    selected = list(roots)
    pruned_ids: list[str] = []
    if dynamic_selection and roots:
        selected, pruned_ids = await _dynamic_community_selection(llm, query, communities, roots)

    trace.emit(
        "query.communities",
        root_count=len(roots),
        selected_count=len(selected),
        pruned_count=len(pruned_ids),
        selected_communities=[
            {"id": c.id, "name": c.name, "level": c.level, "rank": c.rank} for c in selected[:40]
        ],
        pruned_community_ids=pruned_ids[:40],
    )

    weights = _occurrence_weights(store, repo_id, selected)

    shuffled = list(selected)
    random.shuffle(shuffled)
    batch_size = max(1, min(int(map_batch_size), 12))
    batches: list[list[Any]] = []
    for i in range(0, len(shuffled), batch_size):
        batch = shuffled[i : i + batch_size]
        batch.sort(key=lambda c: (-weights.get(c.id, 0.0), c.rank or 0))
        batches.append(batch)
    batches = batches[: max(1, int(max_map_batches))]

    map_tasks = [
        _map_batch(llm, query, batch, weights, batch_index)
        for batch_index, batch in enumerate(batches, start=1)
    ]
    map_answers = await asyncio.gather(*map_tasks)
    useful = [item for item in map_answers if float(item.get("helpfulness") or 0) > 0]

    trace.emit(
        "query.map",
        batch_count=len(batches),
        useful_count=len(useful),
        map_answers=[
            {
                "batch": item.get("batch"),
                "helpfulness": item.get("helpfulness"),
                "answer": truncate(item.get("answer"), 1500),
            }
            for item in map_answers
        ],
    )

    if not useful:
        map_errors = [str(item.get("error")) for item in map_answers if item.get("error")]
        if map_errors and len(map_errors) >= len(map_answers):
            answer = (
                "全局搜索 Map 步骤调用 LLM 失败："
                f"{map_errors[0]}。"
                "请检查 CodeWiki LLM 配置（CODEWIKI_LLM__*）后重试。"
            )
        else:
            answer = (
                "全局搜索未能从社区报告中找到与问题足够相关的证据。"
                "请尝试 Local Search，或确认社区摘要已由 LLM 生成。"
            )
    else:
        answer = await _reduce_answers(llm, query, useful)

    trace.emit("query.reduce", answer=truncate(answer, 4000), useful_count=len(useful))

    contexts: list[dict[str, Any]] = []
    for community in selected[:24]:
        name = community.name or community.id
        summary = (community.summary or "").strip()
        contexts.append(
            {
                "file": f"codewiki/community/{name}",
                "line": 1,
                "endLine": 1,
                "symbolName": name,
                "symbolKind": "community",
                "content": f"community: {name}\nlevel={community.level}\n"
                f"occurrence_weight={weights.get(community.id, 0):.3f}\n{summary}",
                "score": round(weights.get(community.id, 0) * 100, 2),
                "retrievalType": "community",
                "sourceType": "community_report",
            }
        )
    for item in useful:
        contexts.append(
            {
                "file": f"graphrag/global-map/{item.get('batch')}",
                "line": 1,
                "endLine": 1,
                "content": f"helpfulness={item.get('helpfulness')}\n{item.get('answer', '')}",
                "score": round(float(item.get("helpfulness") or 0) * 20, 2),
                "retrievalType": "community",
                "sourceType": "global_map",
            }
        )

    result = {
        "repo_id": repo_id,
        "query": query,
        "mode": "global",
        "answer": answer,
        "level": level,
        "dynamic_selection": dynamic_selection,
        "selected_community_ids": [c.id for c in selected],
        "pruned_community_ids": pruned_ids,
        "occurrence_weights": {c.id: weights.get(c.id, 0.0) for c in selected},
        "map_answers": map_answers,
        "contexts": contexts,
    }
    trace.emit(
        "query.context",
        context_count=len(contexts),
        contexts=summarize_contexts(contexts),
    )
    trace.emit(
        "query.done",
        status="ok",
        context_count=len(contexts),
        selected_count=len(selected),
        duration_ms=trace.elapsed_ms(),
    )
    return result


def _occurrence_weights(store, repo_id: str, communities: list[Any]) -> dict[str, float]:
    raw: dict[str, float] = {}
    for community in communities:
        node_ids = [str(n) for n in (community.node_ids or [])]
        if not node_ids:
            raw[community.id] = 0.0
            continue
        try:
            chunks = store.get_code_chunks_by_node_ids(repo_id, node_ids)
        except Exception:
            chunks = []
        # Distinct text units: prefer non-entity source files; fall back to entity docs / node count.
        units = {
            (c.file_path, c.start_line, c.end_line)
            for c in chunks
            if not str(c.file_path or "").startswith(ENTITY_PREFIX)
        }
        if not units:
            units = {(c.file_path, c.start_line, c.end_line) for c in chunks}
        if not units:
            units = set(node_ids)
        raw[community.id] = float(len(units))

    max_raw = max(raw.values()) if raw else 0.0
    if max_raw <= 0:
        return {cid: 0.0 for cid in raw}
    return {cid: value / max_raw for cid, value in raw.items()}


async def _dynamic_community_selection(
    llm,
    query: str,
    all_communities: list[Any],
    roots: list[Any],
) -> tuple[list[Any], list[str]]:
    children_by_parent: dict[str, list[Any]] = {}
    for community in all_communities:
        parent = community.parent_id
        if parent:
            children_by_parent.setdefault(str(parent), []).append(community)

    kept: list[Any] = []
    pruned: list[str] = []

    async def evaluate(community) -> bool:
        name = community.name or community.id
        summary = (community.summary or "")[:1200]
        prompt = (
            "You are filtering code-repository community reports for GraphRAG global search.\n"
            "Return JSON only: {\"relevant\": true|false, \"reason\": \"...\"}.\n"
            "Mark relevant=true if the community could help answer the question about this codebase.\n"
        )
        user = f"Question: {query}\n\nCommunity: {name}\nSummary:\n{summary}"
        try:
            # Use supported CodeWiki task type (TASK_ROUTING_DEFAULTS has no community_filter).
            result = await llm.complete(
                "cluster",
                [
                    {"role": "system", "content": prompt},
                    {"role": "user", "content": user},
                ],
                response_format="json_object",
            )
            data = _parse_json(result.content)
            return bool(data.get("relevant"))
        except Exception as ex:
            _log(f"DCS evaluate failed for {community.id}: {ex}")
            return True  # keep on failure

    # Evaluate roots in parallel (bounded).
    sem = asyncio.Semaphore(4)

    async def gated(community):
        async with sem:
            return community, await evaluate(community)

    results = await asyncio.gather(*[gated(c) for c in roots])
    for community, relevant in results:
        if relevant:
            kept.append(community)
            # Include direct children of relevant roots for richer map context.
            for child in children_by_parent.get(community.id, []):
                if child not in kept:
                    kept.append(child)
        else:
            pruned.append(community.id)
            stack = list(children_by_parent.get(community.id, []))
            while stack:
                child = stack.pop()
                pruned.append(child.id)
                stack.extend(children_by_parent.get(child.id, []))

    if not kept:
        # Never return empty — fall back to all roots.
        return list(roots), pruned
    return kept, pruned


async def _map_batch(
    llm,
    query: str,
    batch: list[Any],
    weights: dict[str, float],
    batch_index: int,
) -> dict[str, Any]:
    reports = []
    for community in batch:
        name = community.name or community.id
        summary = (community.summary or "").strip()
        reports.append(
            {
                "id": community.id,
                "name": name,
                "level": community.level,
                "occurrence_weight": weights.get(community.id, 0.0),
                "summary": summary[:2000],
            }
        )
    system = (
        "You are a GraphRAG map-step assistant for a code repository knowledge graph.\n"
        "Using ONLY the community reports, answer the user question as far as these reports allow.\n"
        "Return JSON: {\"answer\": \"...\", \"helpfulness\": <0-5 integer>}.\n"
        "helpfulness=0 if the reports are irrelevant; 5 if they fully answer the question.\n"
        "Write the answer in Chinese when the question is Chinese."
    )
    user = json.dumps({"question": query, "communities": reports}, ensure_ascii=False)
    try:
        # Map over community reports — reuse community_summary routing profile.
        result = await llm.complete(
            "community_summary",
            [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            response_format="json_object",
        )
        data = _parse_json(result.content)
        answer = str(data.get("answer") or "").strip()
        helpfulness = data.get("helpfulness")
        if helpfulness is None:
            match = HELPFULNESS_RE.search(result.content or "")
            helpfulness = float(match.group(1)) if match else 0
        return {
            "batch": batch_index,
            "answer": answer or (result.content or "").strip(),
            "helpfulness": float(helpfulness or 0),
            "community_ids": [c.id for c in batch],
        }
    except Exception as ex:
        _log(f"map batch {batch_index} failed: {ex}")
        return {
            "batch": batch_index,
            "answer": "",
            "helpfulness": 0,
            "community_ids": [c.id for c in batch],
            "error": str(ex),
        }


async def _reduce_answers(llm, query: str, map_answers: list[dict[str, Any]]) -> str:
    system = (
        "You are a GraphRAG reduce-step assistant.\n"
        "Synthesize the partial map answers into one complete Chinese answer for the user.\n"
        "Do not invent facts beyond the partial answers. Cite community themes when useful."
    )
    partials = [
        {"helpfulness": item.get("helpfulness"), "answer": item.get("answer")}
        for item in map_answers
    ]
    user = json.dumps({"question": query, "partial_answers": partials}, ensure_ascii=False)
    # Final synthesis — reuse qa routing profile (supported by CodeWiki ModelRouter).
    result = await llm.complete(
        "qa",
        [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
    )
    return (result.content or "").strip()


def _parse_json(text: str) -> dict[str, Any]:
    if not text:
        return {}
    text = text.strip()
    try:
        data = json.loads(text)
        return data if isinstance(data, dict) else {}
    except json.JSONDecodeError:
        start = text.find("{")
        end = text.rfind("}")
        if start >= 0 and end > start:
            try:
                data = json.loads(text[start : end + 1])
                return data if isinstance(data, dict) else {}
            except json.JSONDecodeError:
                return {}
        return {}


# ---------------------------------------------------------------------------
# HTTP routes
# ---------------------------------------------------------------------------


@router.post("/{repo_id}/graphrag/build-standard")
async def build_standard_endpoint(repo_id: str, payload: BuildStandardRequest | None = None):
    request = payload or BuildStandardRequest()
    try:
        from backend.app.services.async_tasks import repo_write_lock

        async with repo_write_lock(repo_id):
            return await build_standard_index(
                repo_id,
                include_embeddings=request.include_embeddings,
                max_entities=request.max_entities,
            )
    except ValueError as exc:
        raise HTTPException(status_code=404 if "not found" in str(exc).lower() else 400, detail=str(exc)) from exc
    except Exception as exc:
        _log(f"build-standard failed: {exc}")
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@router.post("/{repo_id}/graphrag/local-search")
async def local_search_endpoint(repo_id: str, payload: LocalSearchRequest):
    try:
        return await local_search(
            repo_id,
            payload.query,
            max_hops=payload.max_hops,
            top_k=payload.top_k,
        )
    except ValueError as exc:
        raise HTTPException(status_code=404 if "not found" in str(exc).lower() else 400, detail=str(exc)) from exc
    except Exception as exc:
        _log(f"local-search failed: {exc}")
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@router.post("/{repo_id}/graphrag/global-search")
async def global_search_endpoint(repo_id: str, payload: GlobalSearchRequest):
    try:
        return await global_search(
            repo_id,
            payload.query,
            level=payload.level,
            map_batch_size=payload.map_batch_size,
            dynamic_selection=payload.dynamic_selection,
            max_map_batches=payload.max_map_batches,
        )
    except ValueError as exc:
        raise HTTPException(status_code=404 if "not found" in str(exc).lower() else 400, detail=str(exc)) from exc
    except Exception as exc:
        _log(f"global-search failed: {exc}")
        raise HTTPException(status_code=500, detail=str(exc)) from exc
