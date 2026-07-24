"""
LLM 调用服务。

LLM 调用策略：
  - 问题分类（What/Where/How）：纯正则匹配（rag_service.classify_question），不调用 LLM
  - Issue 分类（类型/置信度）：纯规则匹配（issue_service.classify_issue），不调用 LLM
  - Issue 摘要/回复：规则模板生成，仅显式 use_llm=True 时增强
  - 问答内容生成：核心 LLM 场景，支持流式输出
  - 非流式调用仅用于一次性返回（Issue 分析增强等）
"""

import json
import os
import re
from pathlib import Path
from collections.abc import AsyncGenerator

import httpx
from dotenv import load_dotenv

_ENV_PATH = Path(__file__).parent / ".env"


def refresh_env() -> None:
    """每次读取前重载 .env（修改密钥后无需杀进程，开发环境更可靠）。"""
    load_dotenv(_ENV_PATH, override=True)


def llm_configured() -> bool:
    refresh_env()
    return bool(os.getenv("LLM_API_KEY", "").strip())


def llm_model() -> str:
    refresh_env()
    return os.getenv("LLM_MODEL", "tencent/hy3:free")


def llm_base_url() -> str:
    refresh_env()
    return os.getenv("LLM_BASE_URL", "https://openrouter.ai/api/v1").rstrip("/")


def llm_provider_label() -> str:
    base = llm_base_url().lower()
    if "openrouter" in base:
        return "OpenRouter"
    if "openai" in base:
        return "OpenAI"
    return "custom"


def _extra_headers() -> dict[str, str]:
    refresh_env()
    headers: dict[str, str] = {}
    referer = os.getenv("LLM_HTTP_REFERER", "http://localhost:5173")
    title = os.getenv("LLM_APP_TITLE", "RepoPilot")
    if referer:
        headers["HTTP-Referer"] = referer
    if title:
        headers["X-Title"] = title
    return headers


async def chat_completion(
    system_prompt: str,
    user_prompt: str,
    *,
    temperature: float = 0.2,
    max_tokens: int = 1200,
) -> str:
    refresh_env()
    api_key = os.getenv("LLM_API_KEY", "").strip()
    if not api_key:
        raise RuntimeError("LLM 未配置")

    async with httpx.AsyncClient(timeout=90.0) as client:
        response = await client.post(
            f"{llm_base_url()}/chat/completions",
            headers={
                "Authorization": f"Bearer {api_key}",
                "Content-Type": "application/json",
                **_extra_headers(),
            },
            json={
                "model": llm_model(),
                "messages": [
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": user_prompt},
                ],
                "temperature": temperature,
                "max_tokens": max_tokens,
            },
        )

    if response.status_code >= 400:
        detail = response.text
        try:
            detail = response.json().get("error", {}).get("message", detail)
        except Exception:
            pass
        raise RuntimeError(f"LLM 调用失败 ({response.status_code}): {detail}")

    data = response.json()
    return data["choices"][0]["message"]["content"].strip()


async def chat_completion_stream(
    system_prompt: str,
    user_prompt: str,
    *,
    temperature: float = 0.2,
    max_tokens: int = 1200,
) -> AsyncGenerator[str, None]:
    """流式调用 LLM Chat Completion，逐 token yield 文本。

    使用 OpenAI 兼容的 stream: true 模式，
    通过 httpx 流式读取 SSE 分块，yield 每个 delta 的 content。
    """
    refresh_env()
    api_key = os.getenv("LLM_API_KEY", "").strip()
    if not api_key:
        raise RuntimeError("LLM 未配置")

    async with httpx.AsyncClient(timeout=120.0) as client:
        async with client.stream(
            "POST",
            f"{llm_base_url()}/chat/completions",
            headers={
                "Authorization": f"Bearer {api_key}",
                "Content-Type": "application/json",
                **_extra_headers(),
            },
            json={
                "model": llm_model(),
                "messages": [
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": user_prompt},
                ],
                "temperature": temperature,
                "max_tokens": max_tokens,
                "stream": True,
            },
        ) as response:
            if response.status_code >= 400:
                body = await response.aread()
                detail = body.decode("utf-8", errors="ignore")
                try:
                    detail = json.loads(detail).get("error", {}).get("message", detail)
                except Exception:
                    pass
                raise RuntimeError(f"LLM 调用失败 ({response.status_code}): {detail}")

            async for line in response.aiter_lines():
                line = line.strip()
                if not line:
                    continue
                if not line.startswith("data: "):
                    continue
                data_str = line[6:]
                if data_str == "[DONE]":
                    return
                try:
                    data = json.loads(data_str)
                    choices = data.get("choices", [])
                    if choices:
                        delta = choices[0].get("delta", {})
                        content = delta.get("content", "")
                        if content:
                            yield content
                except json.JSONDecodeError:
                    continue


async def generate_answer(question: str, question_type: str, contexts: list[dict]) -> str:
    if not contexts:
        return "知识库中未找到相关内容。请先在「知识库」页面为该仓库执行索引构建。"

    if not llm_configured():
        return build_fallback_answer(question, question_type, contexts)

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
        f"用户问题: {question}\n\n"
        f"上下文:\n{context_text}\n\n"
        "请给出简洁、可执行的回答。"
    )

    try:
        return await chat_completion(system_prompt, user_prompt)
    except RuntimeError as exc:
        return build_fallback_answer(question, question_type, contexts) + f"\n\n（{exc}）"


async def analyze_issue_with_llm(
    title: str,
    body: str,
    labels: list[str],
    rule_type: str,
    rule_reason: str,
    related_files: list[dict],
) -> dict | None:
    if not llm_configured():
        return None

    files_text = "\n".join(
        f"- {item['file']}:{item.get('line', 1)}" for item in related_files[:5]
    ) or "（知识库未命中相关文件）"

    system_prompt = (
        "你是开源项目 Issue 维护助手。根据 Issue 内容与仓库上下文，输出 JSON，不要 markdown 代码块。"
        "字段：summary（中文摘要，1-2句）、suggestedReply（给提交者的中文回复，礼貌专业）、"
        "type（usage_question|duplicate|insufficient_info|bug_fix|feature_request|other）、"
        "confidence（0-1 数字）、reason（分类依据，中文）。"
    )
    user_prompt = (
        f"标题: {title}\n标签: {', '.join(labels) or '无'}\n正文:\n{body or '（无正文）'}\n\n"
        f"规则预分类: {rule_type}（{rule_reason}）\n"
        f"知识库相关文件:\n{files_text}\n\n"
        "请输出 JSON。"
    )

    try:
        raw = await chat_completion(system_prompt, user_prompt, temperature=0.1, max_tokens=800)
        raw = re.sub(r"^```json\s*|\s*```$", "", raw.strip())
        data = json.loads(raw)
        issue_type = data.get("type", rule_type)
        if issue_type not in {
            "usage_question",
            "duplicate",
            "insufficient_info",
            "bug_fix",
            "feature_request",
            "other",
        }:
            issue_type = rule_type
        return {
            "type": issue_type,
            "confidence": float(data.get("confidence", 0.75)),
            "summary": str(data.get("summary", "")).strip(),
            "suggestedReply": str(data.get("suggestedReply", "")).strip(),
            "reason": str(data.get("reason", rule_reason)).strip(),
        }
    except Exception:
        return None


def build_fallback_answer(question: str, question_type: str, contexts: list[dict]) -> str:
    lead = {
        "what": "根据仓库索引，这个项目主要包含以下内容：",
        "where": "根据路径检索，以下位置可能与问题相关：",
        "how": "结合仓库中的代码与文档，可参考以下片段：",
    }[question_type]

    lines = [lead]
    for item in contexts[:3]:
        preview = item["content"].strip().replace("\n", " ")[:180]
        lines.append(f"- `{item['file']}`（约第 {item.get('line', 1)} 行）：{preview}")

    lines.append("\n如需 LLM 增强回答，请在 backend/.env 配置 OpenRouter 的 LLM_API_KEY 并刷新设置页。")
    return "\n".join(lines)
