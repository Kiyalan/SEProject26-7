"""
Issue 智能分析服务。

LLM 调用策略：
  - 分类（classify_issue）：纯规则匹配，不调用 LLM
  - 摘要/回复（summary/suggested_reply）：优先规则模板生成；仅当
    use_llm=True 且 LLM 已配置时才调用 LLM 增强
  - 此策略确保基础质量稳定、响应快速、费用可控
"""

import json
from datetime import datetime, timezone

from db import get_connection, init_db
from llm_service import analyze_issue_with_llm
from rag_service import retrieve_chunks

ISSUE_TYPE_META = {
    "usage_question": {"label": "使用问题", "color": "blue"},
    "duplicate": {"label": "重复问题", "color": "default"},
    "insufficient_info": {"label": "信息不足", "color": "orange"},
    "bug_fix": {"label": "缺陷修复", "color": "red"},
    "feature_request": {"label": "功能改进", "color": "green"},
    "other": {"label": "其他", "color": "default"},
}


def _lower_text(title: str, body: str, labels: list[str]) -> str:
    return " ".join([title, body, " ".join(labels)]).lower()


def classify_issue(title: str, body: str, labels: list[str]) -> tuple[str, float, str]:
    text = _lower_text(title, body, labels)
    if any(word in text for word in ["duplicate", "duplicated", "重复", "same as"]):
        return "duplicate", 0.88, "标题、正文或标签中出现重复问题特征。"
    if any(word in text for word in ["feature", "enhancement", "proposal", "希望", "建议", "support", "add "]):
        return "feature_request", 0.78, "Issue 描述更像功能建议或增强请求。"
    if any(word in text for word in ["bug", "error", "crash", "fail", "exception", "报错", "失败", "崩溃"]):
        return "bug_fix", 0.82, "Issue 包含错误、失败或异常等缺陷信号。"
    if len(body.strip()) < 80 or any(word in text for word in ["not enough", "more info", "复现", "日志"]):
        return "insufficient_info", 0.75, "正文信息较少，缺少版本、日志或复现步骤。"
    if "?" in title or any(word in text for word in ["how", "what", "why", "怎么", "如何", "请问", "用法"]):
        return "usage_question", 0.8, "Issue 主要是在询问使用方式或概念。"
    return "other", 0.55, "未命中明显分类规则，暂归为其他。"


def build_summary(issue_type: str, title: str, related_files: list[dict]) -> str:
    label = ISSUE_TYPE_META.get(issue_type, ISSUE_TYPE_META["other"])["label"]
    if related_files:
        files = "、".join(f"`{item['file']}`" for item in related_files[:3])
        return f"{label}：{title}。知识库检索到可能相关文件：{files}。"
    return f"{label}：{title}。当前知识库未检索到明确相关文件。"


def build_reply(issue_type: str, related_files: list[dict]) -> str:
    if issue_type == "duplicate":
        return "感谢反馈。这个问题可能与已有 Issue 重复，我们会确认相似记录后合并跟踪。"
    if issue_type == "insufficient_info":
        return "感谢反馈。为了进一步定位问题，请补充：复现步骤、期望结果、实际结果、版本信息以及完整错误日志。"
    if issue_type == "usage_question":
        citation = f" 可先参考 `{related_files[0]['file']}`。" if related_files else ""
        return f"感谢提问。这个问题更偏使用咨询，我们会根据文档和代码示例补充说明。{citation}"
    if issue_type == "bug_fix":
        citation = f" 初步相关文件：`{related_files[0]['file']}`。" if related_files else ""
        return f"感谢报告。这个问题看起来可能需要代码修复，我们会根据复现信息进一步定位。{citation}"
    if issue_type == "feature_request":
        return "感谢建议。这个需求属于功能增强方向，我们会结合项目范围、维护成本和社区反馈评估优先级。"
    return "感谢反馈。我们会进一步查看该 Issue 的上下文后决定处理方式。"


async def analyze_issue(repo_id: str, issue: dict, *, use_llm: bool = False) -> dict:
    """分析一个 Issue 并返回完整结果。

    默认（use_llm=False）：纯规则分类 + 模板回复，不调用 LLM，避免费用。
    当 use_llm=True 且 LLM 已配置：规则预分类后交给 LLM 增强摘要和回复质量。
    """
    init_db()
    title = issue.get("title", "")
    body = issue.get("body", "")
    labels = issue.get("labels", [])
    issue_type, confidence, reason = classify_issue(title, body, labels)
    contexts = retrieve_chunks(repo_id, f"{title}\n{body}", limit=4)
    related_files = [{"file": item["file"], "line": item.get("line", 1)} for item in contexts]

    # 仅当显式要求且 LLM 已配置时才调用 LLM 增强
    llm_enhanced = False
    if use_llm:
        llm_result = await analyze_issue_with_llm(
            title, body, labels, issue_type, reason, related_files
        )
        if llm_result:
            issue_type = llm_result["type"]
            confidence = llm_result["confidence"]
            reason = llm_result["reason"]
            summary = llm_result["summary"] or build_summary(issue_type, title, related_files)
            suggested_reply = llm_result["suggestedReply"] or build_reply(issue_type, related_files)
            llm_enhanced = True
        else:
            summary = build_summary(issue_type, title, related_files)
            suggested_reply = build_reply(issue_type, related_files)
    else:
        summary = build_summary(issue_type, title, related_files)
        suggested_reply = build_reply(issue_type, related_files)

    analyzed_at = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M")

    result = {
        "issueId": str(issue.get("id", "")),
        "repoId": repo_id,
        "number": issue.get("number"),
        "title": title,
        "type": issue_type,
        "typeLabel": ISSUE_TYPE_META[issue_type]["label"],
        "confidence": confidence,
        "summary": summary,
        "suggestedReply": suggested_reply,
        "reason": reason,
        "relatedFiles": related_files,
        "analyzedAt": analyzed_at,
        "needsCodeChange": issue_type == "bug_fix",
        "llmEnhanced": llm_enhanced,
    }

    with get_connection() as conn:
        conn.execute(
            """
            INSERT OR REPLACE INTO issue_analysis (
                issue_id, repo_id, issue_number, issue_title, issue_type,
                confidence, summary, suggested_reply, reason, related_files, analyzed_at,
                llm_enhanced
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                result["issueId"],
                repo_id,
                result["number"],
                title,
                issue_type,
                confidence,
                summary,
                suggested_reply,
                reason,
                json.dumps(related_files, ensure_ascii=False),
                analyzed_at,
                1 if llm_enhanced else 0,
            ),
        )

    return result


def get_issue_analysis(issue_id: str) -> dict | None:
    init_db()
    with get_connection() as conn:
        row = conn.execute("SELECT * FROM issue_analysis WHERE issue_id = ?", (issue_id,)).fetchone()
    if not row:
        return None
    related_files = json.loads(row["related_files"] or "[]")
    issue_type = row["issue_type"]
    return {
        "issueId": issue_id,
        "repoId": row["repo_id"],
        "number": row["issue_number"],
        "title": row["issue_title"],
        "type": issue_type,
        "typeLabel": ISSUE_TYPE_META.get(issue_type, ISSUE_TYPE_META["other"])["label"],
        "confidence": row["confidence"],
        "summary": row["summary"],
        "suggestedReply": row["suggested_reply"],
        "reason": row["reason"],
        "relatedFiles": related_files,
        "analyzedAt": row["analyzed_at"],
        "needsCodeChange": issue_type == "bug_fix",
        "llmEnhanced": bool(row["llm_enhanced"]) if "llm_enhanced" in row.keys() else False,
    }
