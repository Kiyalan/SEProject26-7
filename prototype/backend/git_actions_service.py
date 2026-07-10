import os
import re
from datetime import datetime, timezone

import httpx


async def github_request(
    method: str,
    path: str,
    token: str,
    *,
    json_body: dict | None = None,
    params: dict | None = None,
) -> object:
    async with httpx.AsyncClient(timeout=60.0) as client:
        response = await client.request(
            method,
            f"https://api.github.com{path}",
            headers={
                "Authorization": f"Bearer {token}",
                "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28",
            },
            json=json_body,
            params=params,
        )
    if response.status_code >= 400:
        raise RuntimeError(response.json().get("message", response.text) if response.headers.get("content-type", "").startswith("application/json") else response.text)
    if response.status_code == 204:
        return {}
    return response.json()


async def get_repo_info(repo_id: str, token: str) -> dict:
    data = await github_request("GET", f"/repositories/{repo_id}", token)
    if not isinstance(data, dict):
        raise RuntimeError("仓库不存在")
    return data


async def create_branch(repo_id: str, token: str, branch: str, from_branch: str | None = None) -> dict:
    repo = await get_repo_info(repo_id, token)
    full_name = repo["full_name"]
    base = from_branch or repo.get("default_branch", "main")

    ref = await github_request("GET", f"/repos/{full_name}/git/ref/heads/{base}", token)
    if not isinstance(ref, dict):
        raise RuntimeError("无法获取基准分支")

    sha = ref["object"]["sha"]
    result = await github_request(
        "POST",
        f"/repos/{full_name}/git/refs",
        token,
        json_body={"ref": f"refs/heads/{branch}", "sha": sha},
    )
    return {"action": "create_branch", "branch": branch, "from": base, "result": result}


async def commit_file(
    repo_id: str,
    token: str,
    path: str,
    content: str,
    message: str,
    branch: str | None = None,
) -> dict:
    repo = await get_repo_info(repo_id, token)
    full_name = repo["full_name"]
    target_branch = branch or repo.get("default_branch", "main")

    import base64

    encoded = base64.b64encode(content.encode("utf-8")).decode("ascii")

    existing = None
    try:
        existing = await github_request(
            "GET",
            f"/repos/{full_name}/contents/{path}",
            token,
            params={"ref": target_branch},
        )
    except RuntimeError:
        pass

    body: dict = {
        "message": message,
        "content": encoded,
        "branch": target_branch,
    }
    if isinstance(existing, dict) and existing.get("sha"):
        body["sha"] = existing["sha"]

    result = await github_request("PUT", f"/repos/{full_name}/contents/{path}", token, json_body=body)
    return {
        "action": "commit_file",
        "path": path,
        "branch": target_branch,
        "commit": result.get("commit", {}).get("sha") if isinstance(result, dict) else None,
    }


async def create_pull_request(
    repo_id: str,
    token: str,
    title: str,
    head: str,
    base: str | None = None,
    body: str = "",
) -> dict:
    repo = await get_repo_info(repo_id, token)
    full_name = repo["full_name"]
    target_base = base or repo.get("default_branch", "main")

    result = await github_request(
        "POST",
        f"/repos/{full_name}/pulls",
        token,
        json_body={"title": title, "head": head, "base": target_base, "body": body},
    )
    return {
        "action": "create_pr",
        "title": title,
        "url": result.get("html_url") if isinstance(result, dict) else None,
        "number": result.get("number") if isinstance(result, dict) else None,
    }


def parse_nl_command(command: str) -> tuple[str, dict]:
    text = command.strip().lower()

    if any(k in text for k in ("同步", "索引", "pull", "sync", "重建", "刷新知识")):
        return "sync_knowledge", {}

    branch_match = re.search(r"(?:创建|新建|create)\s*(?:分支|branch)\s*[`'\"]?([\w./-]+)", command, re.I)
    if branch_match or ("分支" in text and "创建" in text):
        name = branch_match.group(1) if branch_match else re.sub(r".*分支\s*", "", command).strip()
        return "create_branch", {"branch": name or f"repopilot-{int(datetime.now(timezone.utc).timestamp())}"}

    if any(k in text for k in ("提交", "commit", "push", "推送")):
        path_match = re.search(r"([\w./-]+\.\w+)", command)
        path = path_match.group(1) if path_match else "README.md"
        content_match = re.search(r"[:：]\s*(.+)$", command, re.S)
        content = content_match.group(1).strip() if content_match else f"Updated via RepoPilot at {datetime.now(timezone.utc).isoformat()}"
        msg_match = re.search(r"(?:说明|message)[:：]\s*(.+?)(?:\n|$)", command, re.I)
        message = msg_match.group(1).strip() if msg_match else f"Update {path} via RepoPilot"
        return "commit_file", {"path": path, "content": content, "message": message}

    pr_match = re.search(r"(?:pr|pull request|合并请求)", text)
    if pr_match or ("创建" in text and "pr" in text):
        title = re.sub(r".*(?:pr|pull request)[:：\s]*", "", command, flags=re.I).strip() or "RepoPilot automated PR"
        branch_match = re.search(r"(?:从|from|分支)\s*[`'\"]?([\w./-]+)", command, re.I)
        return "create_pr", {
            "title": title,
            "head": branch_match.group(1) if branch_match else "",
            "body": "Created via RepoPilot natural language command.",
        }

    return "unknown", {}


async def execute_action(repo_id: str, token: str, action: str, params: dict) -> dict:
    if action == "sync_knowledge":
        from knowledge_service import build_repo_knowledge

        return await build_repo_knowledge(repo_id, token)

    if action == "create_branch":
        branch = params.get("branch", "")
        if not branch:
            raise RuntimeError("请提供分支名")
        return await create_branch(repo_id, token, branch, params.get("from"))

    if action == "commit_file":
        path = params.get("path", "")
        content = params.get("content", "")
        if not path:
            raise RuntimeError("请提供文件路径")
        return await commit_file(
            repo_id,
            token,
            path,
            content,
            params.get("message", f"Update {path}"),
            params.get("branch"),
        )

    if action == "create_pr":
        head = params.get("head", "")
        if not head:
            raise RuntimeError("请提供源分支名")
        return await create_pull_request(
            repo_id,
            token,
            params.get("title", "RepoPilot PR"),
            head,
            params.get("base"),
            params.get("body", ""),
        )

    raise RuntimeError(f"未知操作: {action}")


async def execute_nl(repo_id: str, token: str, command: str) -> dict:
    action, params = parse_nl_command(command)
    if action == "unknown":
        return {
            "success": False,
            "message": "无法理解该命令。试试：「同步知识库」「创建分支 feature/demo」「提交 README.md：更新说明」「创建 PR：修复登录问题」",
        }

    try:
        result = await execute_action(repo_id, token, action, params)
        labels = {
            "sync_knowledge": "知识库已同步重建",
            "create_branch": f"分支 {params.get('branch', '')} 已创建",
            "commit_file": f"已提交 {params.get('path', '')}",
            "create_pr": f"PR 已创建" + (f"：{result.get('url', '')}" if result.get("url") else ""),
        }
        return {"success": True, "action": action, "message": labels.get(action, "操作完成"), "result": result}
    except RuntimeError as exc:
        return {"success": False, "message": str(exc)}
