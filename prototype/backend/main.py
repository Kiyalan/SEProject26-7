import os
import secrets
from datetime import datetime, timezone

STARTED_AT = datetime.now(timezone.utc).isoformat()
SERVER_PID = os.getpid()
from pathlib import Path
from typing import Annotated
from urllib.parse import urlencode

import httpx
from dotenv import load_dotenv
from fastapi import FastAPI, Header, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import RedirectResponse, StreamingResponse
from pydantic import BaseModel

from db import init_db
from issue_service import analyze_issue, get_issue_analysis
from knowledge_service import (
    build_repo_knowledge,
    compare_commits,
    get_index_settings,
    get_knowledge_overview,
    list_indexed_commits,
    save_index_settings,
)
from git_actions_service import execute_action, execute_nl
from conversation_service import (
    create_conversation,
    delete_conversation,
    get_conversation_messages,
    list_conversations,
    save_message,
    update_conversation_title,
)
from knowledge_policy import policy_overview
from llm_service import (
    build_fallback_answer,
    chat_completion_stream,
    generate_answer,
    llm_configured,
    llm_model,
    llm_base_url,
    llm_provider_label,
)
from portfolio_service import build_portfolio_overview
from rag_service import classify_question, retrieve_chunks

load_dotenv(Path(__file__).parent / ".env")

app = FastAPI(title="RepoPilot API")


@app.on_event("startup")
async def startup() -> None:
    init_db()

GITHUB_CLIENT_ID = os.getenv("GITHUB_CLIENT_ID", "")
GITHUB_CLIENT_SECRET = os.getenv("GITHUB_CLIENT_SECRET", "")
GITHUB_CALLBACK_URL = os.getenv("GITHUB_CALLBACK_URL", "http://localhost:5173/auth/callback")
FRONTEND_URL = os.getenv("FRONTEND_URL", "http://localhost:5173")

# 简易 state 存储（开发用；生产环境应使用 Redis 等）
oauth_states: set[str] = set()

app.add_middleware(
    CORSMiddleware,
    allow_origins=[FRONTEND_URL, "http://localhost:5173", "http://127.0.0.1:5173"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


def require_config() -> None:
    if not GITHUB_CLIENT_ID or not GITHUB_CLIENT_SECRET:
        raise HTTPException(
            status_code=500,
            detail="请先在 backend/.env 中配置 GITHUB_CLIENT_ID 和 GITHUB_CLIENT_SECRET",
        )


def get_token(authorization: str | None = None) -> str:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="未登录，请先使用 GitHub 授权")
    return authorization.removeprefix("Bearer ").strip()


async def github_get(path: str, token: str, params: dict | None = None) -> object:
    async with httpx.AsyncClient(timeout=30.0) as client:
        response = await client.get(
            f"https://api.github.com{path}",
            headers={
                "Authorization": f"Bearer {token}",
                "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28",
            },
            params=params,
        )
    if response.status_code == 401:
        raise HTTPException(status_code=401, detail="GitHub token 已失效，请重新登录")
    if response.status_code >= 400:
        raise HTTPException(status_code=response.status_code, detail=response.text)
    return response.json()


def format_repo(repo: dict) -> dict:
    return {
        "id": str(repo["id"]),
        "name": repo["name"],
        "fullName": repo["full_name"],
        "description": repo.get("description") or "",
        "stars": repo.get("stargazers_count", 0),
        "openIssues": repo.get("open_issues_count", 0),
        "language": repo.get("language") or "—",
        "lastSync": datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M"),
        "syncStatus": "synced",
        "htmlUrl": repo.get("html_url", ""),
        "private": repo.get("private", False),
        "defaultBranch": repo.get("default_branch", "main"),
    }


@app.get("/")
async def root() -> dict:
    return {"status": "ok", "service": "RepoPilot API"}


@app.get("/api/health")
async def health() -> dict:
    """用于确认当前命中的是最新后端进程（非旧进程占端口）。"""
    return {
        "status": "ok",
        "service": "RepoPilot API",
        "pid": SERVER_PID,
        "startedAt": STARTED_AT,
        "llmConfigured": llm_configured(),
        "llmModel": llm_model(),
        "llmProvider": llm_provider_label(),
        "llmBaseUrl": llm_base_url(),
    }


@app.get("/auth/github")
async def auth_github() -> RedirectResponse:
    require_config()
    state = secrets.token_urlsafe(16)
    oauth_states.add(state)
    params = urlencode(
        {
            "client_id": GITHUB_CLIENT_ID,
            "redirect_uri": GITHUB_CALLBACK_URL,
            "scope": "read:user repo",
            "state": state,
        }
    )
    return RedirectResponse(f"https://github.com/login/oauth/authorize?{params}")


@app.get("/auth/callback")
async def auth_callback(
    code: str | None = None,
    state: str | None = None,
    error: str | None = None,
) -> RedirectResponse:
    require_config()

    if error:
        return RedirectResponse(f"{FRONTEND_URL}/login?error={error}")

    if not code or not state or state not in oauth_states:
        return RedirectResponse(f"{FRONTEND_URL}/login?error=invalid_state")
    oauth_states.discard(state)

    async with httpx.AsyncClient(timeout=30.0) as client:
        token_response = await client.post(
            "https://github.com/login/oauth/access_token",
            headers={"Accept": "application/json"},
            json={
                "client_id": GITHUB_CLIENT_ID,
                "client_secret": GITHUB_CLIENT_SECRET,
                "code": code,
                "redirect_uri": GITHUB_CALLBACK_URL,
            },
        )
        token_data = token_response.json()

    access_token = token_data.get("access_token")
    if not access_token:
        error_desc = token_data.get("error_description", "token_exchange_failed")
        return RedirectResponse(f"{FRONTEND_URL}/login?error={error_desc}")

    user = await github_get("/user", access_token)
    username = user.get("login", "")

    return RedirectResponse(
        f"{FRONTEND_URL}/oauth/success?access_token={access_token}&username={username}"
    )


@app.get("/api/me")
async def get_me(authorization: Annotated[str | None, Header()] = None) -> dict:
    token = get_token(authorization)
    user = await github_get("/user", token)
    return {
        "login": user.get("login"),
        "name": user.get("name"),
        "avatarUrl": user.get("avatar_url"),
    }


@app.get("/api/repos")
async def list_repos(
    authorization: Annotated[str | None, Header()] = None,
    page: int = Query(1, ge=1),
    per_page: int = Query(30, ge=1, le=100),
) -> dict:
    token = get_token(authorization)
    repos = await github_get(
        "/user/repos",
        token,
        {
            "visibility": "all",
            "affiliation": "owner,collaborator,organization_member",
            "sort": "updated",
            "per_page": per_page,
            "page": page,
        },
    )
    if not isinstance(repos, list):
        raise HTTPException(status_code=500, detail="GitHub 返回格式异常")
    return {
        "items": [format_repo(repo) for repo in repos],
        "page": page,
        "perPage": per_page,
    }


@app.get("/api/repos/{repo_id}")
async def get_repo(
    repo_id: int,
    authorization: Annotated[str | None, Header()] = None,
) -> dict:
    token = get_token(authorization)
    repo = await github_get(f"/repositories/{repo_id}", token)
    if not isinstance(repo, dict):
        raise HTTPException(status_code=404, detail="仓库不存在")
    return format_repo(repo)


def format_issue(issue: dict, repo_id: int | str) -> dict:
    return {
        "id": str(issue["id"]),
        "repoId": str(repo_id),
        "number": issue["number"],
        "title": issue["title"],
        "body": issue.get("body") or "",
        "state": issue.get("state", "open"),
        "author": issue.get("user", {}).get("login", ""),
        "createdAt": issue.get("created_at", "")[:10],
        "updatedAt": issue.get("updated_at", "")[:10],
        "labels": [label["name"] for label in issue.get("labels", [])],
        "htmlUrl": issue.get("html_url", ""),
        "comments": issue.get("comments", 0),
    }


def filter_github_issues(issues: list, repo_id: int | str) -> list[dict]:
    items = []
    for issue in issues:
        if not isinstance(issue, dict):
            continue
        if "pull_request" in issue:
            continue
        items.append(format_issue(issue, repo_id))
    return items


@app.get("/api/repos/{repo_id}/issues")
async def list_repo_issues(
    repo_id: int,
    authorization: Annotated[str | None, Header()] = None,
    state: str = Query("all"),
    per_page: int = Query(30, ge=1, le=100),
    page: int = Query(1, ge=1),
) -> dict:
    token = get_token(authorization)
    repo = await github_get(f"/repositories/{repo_id}", token)
    if not isinstance(repo, dict):
        raise HTTPException(status_code=404, detail="仓库不存在")

    full_name = repo["full_name"]
    issues = await github_get(
        f"/repos/{full_name}/issues",
        token,
        {
            "state": state if state in ("open", "closed", "all") else "all",
            "per_page": per_page,
            "page": page,
            "sort": "updated",
            "direction": "desc",
        },
    )
    if not isinstance(issues, list):
        raise HTTPException(status_code=500, detail="GitHub 返回格式异常")

    items = filter_github_issues(issues, repo_id)
    return {
        "items": items,
        "total": len(items),
        "repoFullName": full_name,
        "openIssuesCount": repo.get("open_issues_count", 0),
        "state": state,
        "page": page,
        "perPage": per_page,
    }


@app.get("/api/repos/{repo_id}/issues/{issue_number}")
async def get_repo_issue(
    repo_id: int,
    issue_number: int,
    authorization: Annotated[str | None, Header()] = None,
) -> dict:
    token = get_token(authorization)
    repo = await github_get(f"/repositories/{repo_id}", token)
    if not isinstance(repo, dict):
        raise HTTPException(status_code=404, detail="仓库不存在")

    full_name = repo["full_name"]
    issue = await github_get(f"/repos/{full_name}/issues/{issue_number}", token)
    if not isinstance(issue, dict):
        raise HTTPException(status_code=404, detail="Issue 不存在")
    if "pull_request" in issue:
        raise HTTPException(status_code=400, detail="该编号对应 Pull Request，不是 Issue")

    return format_issue(issue, repo_id)


class ChatRequest(BaseModel):
    repoId: str
    message: str
    commit: str | None = None
    conversationId: str | None = None


class IssueAnalyzeRequest(BaseModel):
    repoId: str
    issue: dict
    useLlm: bool = False


class GitActionRequest(BaseModel):
    action: str
    params: dict = {}


class NlCommandRequest(BaseModel):
    command: str


class KnowledgeBuildRequest(BaseModel):
    indexEachCommit: bool = False
    maxCommits: int = 30
    commitShas: list[str] | None = None


class KnowledgeSettingsRequest(BaseModel):
    indexEachCommit: bool | None = None
    maxCommits: int | None = None
    activeCommitSha: str | None = None


@app.post("/api/repos/{repo_id}/knowledge/build")
async def build_knowledge(
    repo_id: int,
    body: KnowledgeBuildRequest | None = None,
    authorization: Annotated[str | None, Header()] = None,
) -> dict:
    token = get_token(authorization)
    opts = body or KnowledgeBuildRequest()
    try:
        return await build_repo_knowledge(
            str(repo_id),
            token,
            index_each_commit=opts.indexEachCommit,
            max_commits=opts.maxCommits,
            commit_shas=opts.commitShas,
        )
    except RuntimeError as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"构建知识库失败: {exc}") from exc


@app.get("/api/repos/{repo_id}/knowledge")
async def get_knowledge(
    repo_id: int,
    commit: str | None = Query(None),
    authorization: Annotated[str | None, Header()] = None,
) -> dict:
    get_token(authorization)
    try:
        return get_knowledge_overview(str(repo_id), commit)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"加载知识库失败: {exc}") from exc


@app.get("/api/repos/{repo_id}/knowledge/policy")
async def get_knowledge_policy(
    authorization: Annotated[str | None, Header()] = None,
) -> dict:
    get_token(authorization)
    return policy_overview()


@app.get("/api/repos/{repo_id}/knowledge/commits")
async def get_knowledge_commits(
    repo_id: int,
    authorization: Annotated[str | None, Header()] = None,
) -> dict:
    get_token(authorization)
    return {"items": list_indexed_commits(str(repo_id))}


@app.get("/api/repos/{repo_id}/knowledge/compare")
async def compare_knowledge_commits(
    repo_id: int,
    base: str = Query(...),
    head: str = Query(...),
    authorization: Annotated[str | None, Header()] = None,
) -> dict:
    get_token(authorization)
    return compare_commits(str(repo_id), base, head)


@app.get("/api/repos/{repo_id}/knowledge/settings")
async def get_knowledge_settings(
    repo_id: int,
    authorization: Annotated[str | None, Header()] = None,
) -> dict:
    get_token(authorization)
    return get_index_settings(str(repo_id))


@app.put("/api/repos/{repo_id}/knowledge/settings")
async def update_knowledge_settings(
    repo_id: int,
    body: KnowledgeSettingsRequest,
    authorization: Annotated[str | None, Header()] = None,
) -> dict:
    get_token(authorization)
    current = get_index_settings(str(repo_id))
    updated = {
        "indexEachCommit": body.indexEachCommit if body.indexEachCommit is not None else current["indexEachCommit"],
        "maxCommits": body.maxCommits if body.maxCommits is not None else current["maxCommits"],
        "activeCommitSha": body.activeCommitSha if body.activeCommitSha is not None else current["activeCommitSha"],
    }
    return save_index_settings(str(repo_id), updated)


@app.post("/api/chat")
async def chat(
    body: ChatRequest,
    authorization: Annotated[str | None, Header()] = None,
):
    """流式问答端点（SSE），支持对话持久化。

    - conversationId 可选：不传则自动创建新对话
    - 用户消息和助手回复自动存入 conversation_messages
    - 首条消息自动设为对话标题
    """
    get_token(authorization)

    # 自动创建/复用对话
    conv_id = body.conversationId
    if not conv_id:
        conv = create_conversation(body.repoId)
        conv_id = conv["id"]
    else:
        # 验证对话是否存在
        from conversation_service import _conversation_exists
        if not _conversation_exists(conv_id):
            conv = create_conversation(body.repoId)
            conv_id = conv["id"]

    question_type = classify_question(body.message)
    contexts = retrieve_chunks(body.repoId, body.message, commit_sha=body.commit)
    citations = [{"file": item["file"], "line": item.get("line", 1)} for item in contexts[:3]]

    # 保存用户消息
    save_message(conv_id, "user", body.message)

    if not llm_configured() or not contexts:
        answer = build_fallback_answer(body.message, question_type, contexts)
        save_message(conv_id, "assistant", answer, question_type, citations)
        return {
            "answer": answer,
            "questionType": question_type,
            "citations": citations,
            "llmEnabled": False,
            "conversationId": conv_id,
        }

    context_text = "\n\n".join(
        f"[{idx + 1}] 文件: {item['file']}:{item.get('line', 1)}\n{item['content']}"
        for idx, item in enumerate(contexts)
    )
    system_prompt = (
        "你是开源仓库维护助手 RepoPilot。只能根据给定上下文回答，"
        "无法确定时明确说明。回答使用中文，并引用相关文件路径。"
    )
    user_prompt = (
        f"问题类型: {question_type}\n"
        f"用户问题: {body.message}\n\n"
        f"上下文:\n{context_text}\n\n"
        "请给出简洁、可执行的回答。"
    )

    async def sse_stream():
        import json as _json
        header = {
            "type": "header",
            "questionType": question_type,
            "citations": citations,
            "conversationId": conv_id,
        }
        yield f"data: {_json.dumps(header, ensure_ascii=False)}\n\n"

        full_answer: list[str] = []
        try:
            async for chunk in chat_completion_stream(system_prompt, user_prompt):
                full_answer.append(chunk)
                payload = {"type": "content", "content": chunk}
                yield f"data: {_json.dumps(payload, ensure_ascii=False)}\n\n"
        except RuntimeError as exc:
            full_answer.append(f"\n\n（{exc}）")
            payload = {"type": "error", "content": str(exc)}
            yield f"data: {_json.dumps(payload, ensure_ascii=False)}\n\n"

        # 保存助手回复
        save_message(conv_id, "assistant", "".join(full_answer), question_type, citations)

        yield "data: [DONE]\n\n"

    return StreamingResponse(
        sse_stream(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


# ── 对话历史 API ──

@app.get("/api/conversations")
async def list_conversations_endpoint(
    repoId: str = Query(...),
    authorization: Annotated[str | None, Header()] = None,
) -> dict:
    get_token(authorization)
    return {"items": list_conversations(repoId)}


@app.post("/api/conversations")
async def create_conversation_endpoint(
    authorization: Annotated[str | None, Header()] = None,
    repoId: str = Query(""),
    title: str = Query(""),
) -> dict:
    get_token(authorization)
    return create_conversation(repoId, title)


@app.get("/api/conversations/{conversation_id}/messages")
async def get_messages_endpoint(
    conversation_id: str,
    authorization: Annotated[str | None, Header()] = None,
) -> dict:
    get_token(authorization)
    return {"items": get_conversation_messages(conversation_id)}


@app.put("/api/conversations/{conversation_id}")
async def update_conversation_endpoint(
    conversation_id: str,
    authorization: Annotated[str | None, Header()] = None,
    title: str = Query(""),
) -> dict:
    get_token(authorization)
    if title:
        update_conversation_title(conversation_id, title)
    return {"ok": True}


@app.delete("/api/conversations/{conversation_id}")
async def delete_conversation_endpoint(
    conversation_id: str,
    authorization: Annotated[str | None, Header()] = None,
) -> dict:
    get_token(authorization)
    delete_conversation(conversation_id)
    return {"ok": True}


class AiChatRequest(BaseModel):
    repoId: str
    message: str

# 保留旧非流式端点兼容性（可选）
@app.post("/api/chat/sync")
async def chat_sync(
    body: AiChatRequest,
    authorization: Annotated[str | None, Header()] = None,
) -> dict:
    get_token(authorization)
    question_type = classify_question(body.message)
    contexts = retrieve_chunks(body.repoId, body.message)
    answer = await generate_answer(body.message, question_type, contexts)
    citations = [{"file": item["file"], "line": item.get("line", 1)} for item in contexts[:3]]
    return {
        "answer": answer,
        "questionType": question_type,
        "citations": citations,
        "llmEnabled": llm_configured(),
    }


@app.post("/api/issues/analyze")
async def analyze_issue_endpoint(
    body: IssueAnalyzeRequest,
    authorization: Annotated[str | None, Header()] = None,
) -> dict:
    get_token(authorization)
    return await analyze_issue(body.repoId, body.issue, use_llm=body.useLlm)


@app.get("/api/issues/{issue_id}/analysis")
async def get_issue_analysis_endpoint(
    issue_id: str,
    authorization: Annotated[str | None, Header()] = None,
) -> dict:
    get_token(authorization)
    result = get_issue_analysis(issue_id)
    if not result:
        raise HTTPException(status_code=404, detail="该 Issue 尚未分析")
    return result


@app.get("/api/config/llm")
async def get_llm_config() -> dict:
    return {
        "configured": llm_configured(),
        "model": llm_model(),
        "baseUrl": llm_base_url(),
        "provider": llm_provider_label(),
    }


@app.get("/api/portfolio/overview")
async def portfolio_overview(
    authorization: Annotated[str | None, Header()] = None,
    max_repos: int = Query(50, ge=1, le=100),
) -> dict:
    token = get_token(authorization)
    try:
        return await build_portfolio_overview(token, max_repos)
    except RuntimeError as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.post("/api/repos/{repo_id}/actions")
async def run_git_action(
    repo_id: int,
    body: GitActionRequest,
    authorization: Annotated[str | None, Header()] = None,
) -> dict:
    token = get_token(authorization)
    try:
        return await execute_action(str(repo_id), token, body.action, body.params)
    except RuntimeError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post("/api/repos/{repo_id}/actions/nl")
async def run_nl_command(
    repo_id: int,
    body: NlCommandRequest,
    authorization: Annotated[str | None, Header()] = None,
) -> dict:
    token = get_token(authorization)
    return await execute_nl(str(repo_id), token, body.command)
