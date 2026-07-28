package com.repopilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.client.GitHubClient;
import com.repopilot.entity.IssueAnalysis;
import com.repopilot.repository.IssueAnalysisRepository;
import com.repopilot.util.JsonUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class IssueService {

    private static final int BATCH_THRESHOLD = 5;
    private static final Map<String, String> TYPE_LABELS = Map.of(
            "usage_question", "使用问题",
            "duplicate", "重复问题",
            "insufficient_info", "信息不足",
            "bug_fix", "缺陷修复",
            "feature_request", "功能改进",
            "other", "其他"
    );

    private final IssueAnalysisRepository analysisRepository;
    private final KnowledgeService knowledgeService;
    private final GitHubClient github;
    private final ProgressService progress;
    private final LlmService llmService;
    private final NotificationService notificationService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, Deque<Map<String, Object>>> pendingByRepo = new ConcurrentHashMap<>();

    public IssueService(IssueAnalysisRepository analysisRepository, KnowledgeService knowledgeService,
                        GitHubClient github, ProgressService progress, LlmService llmService,
                        NotificationService notificationService) {
        this.analysisRepository = analysisRepository;
        this.knowledgeService = knowledgeService;
        this.github = github;
        this.progress = progress;
        this.llmService = llmService;
        this.notificationService = notificationService;
    }

    public void onIssuesLoaded(String repoId, List<Map<String, Object>> issues, String token, String ownerLogin) {
        Deque<Map<String, Object>> queue = pendingByRepo.computeIfAbsent(repoId, k -> new ArrayDeque<>());
        for (Map<String, Object> issue : issues) {
            String issueId = Objects.toString(issue.get("id"), "");
            if (issueId.isBlank() || isAnalyzed(issueId) || containsIssue(queue, issueId)) {
                continue;
            }
            queue.addLast(new LinkedHashMap<>(issue));
        }
        if (queue.size() >= BATCH_THRESHOLD) {
            flushBatch(repoId, token, ownerLogin);
        }
    }

    public Map<String, Object> analyze(String repoId, Map<String, Object> issue, String token, String ownerLogin) {
        return analyze(repoId, issue, token, false, ownerLogin);
    }

    public Map<String, Object> analyze(String repoId, Map<String, Object> issue, String token, boolean force, String ownerLogin) {
        Map<String, Object> enriched = enrichIssue(repoId, issue, token);
        String issueId = Objects.toString(enriched.get("id"), "");
        if (!force) {
            Map<String, Object> cached = issueId.isBlank() ? null : getAnalysis(issueId);
            if (cached != null) {
                return cached;
            }
        }

        Map<String, Object> result = classifyAndSave(repoId, enriched, token, ownerLogin);
        if (!force) {
            enqueue(repoId, enriched);
            if (pendingByRepo.getOrDefault(repoId, new ArrayDeque<>()).size() >= BATCH_THRESHOLD) {
                flushBatch(repoId, token, ownerLogin);
            }
        }
        return result;
    }

    private void enqueue(String repoId, Map<String, Object> issue) {
        String issueId = Objects.toString(issue.get("id"), "");
        if (issueId.isBlank() || isAnalyzed(issueId)) {
            return;
        }
        Deque<Map<String, Object>> queue = pendingByRepo.computeIfAbsent(repoId, k -> new ArrayDeque<>());
        if (!containsIssue(queue, issueId)) {
            queue.addLast(new LinkedHashMap<>(issue));
        }
    }

    private void flushBatch(String repoId, String token, String ownerLogin) {
        Deque<Map<String, Object>> queue = pendingByRepo.get(repoId);
        if (queue == null || queue.size() < BATCH_THRESHOLD) {
            return;
        }
        List<Map<String, Object>> batch = new ArrayList<>();
        while (!queue.isEmpty() && batch.size() < BATCH_THRESHOLD) {
            batch.add(queue.pollFirst());
        }
        if (batch.isEmpty()) {
            return;
        }
        String progressKey = "issues:" + repoId;
        progress.start(progressKey, batch.size(), "自动批量分析 Issue");
        executor.submit(() -> {
            int done = 0;
            for (Map<String, Object> issue : batch) {
                String issueId = Objects.toString(issue.get("id"), "");
                if (isAnalyzed(issueId)) {
                    progress.step(progressKey, "跳过已分析 Issue");
                    continue;
                }
                try {
                    classifyAndSave(repoId, enrichIssue(repoId, issue, token), token, ownerLogin);
                    done++;
                    progress.step(progressKey, "已分析 " + done + "/" + batch.size());
                } catch (Exception ex) {
                    progress.fail(progressKey, "分析失败: " + ex.getMessage());
                    return;
                }
            }
            progress.finish(progressKey, "批量分析完成");
        });
    }

    private boolean containsIssue(Deque<Map<String, Object>> queue, String issueId) {
        return queue.stream().anyMatch(item -> issueId.equals(Objects.toString(item.get("id"), "")));
    }

    private Map<String, Object> enrichIssue(String repoId, Map<String, Object> issue, String token) {
        Map<String, Object> merged = new LinkedHashMap<>(issue);
        int number = toInt(merged.get("number"));
        if (number <= 0 || token == null || token.isBlank()) {
            return normalizeIssue(merged);
        }
        try {
            JsonNode repo = github.get("/repositories/" + repoId, token);
            String fullName = repo.path("full_name").asText();
            JsonNode raw = github.get("/repos/" + fullName + "/issues/" + number, token);
            merged.putAll(github.formatIssue(raw, repoId));
        } catch (Exception ignored) {
        }
        return normalizeIssue(merged);
    }

    private Map<String, Object> normalizeIssue(Map<String, Object> issue) {
        Map<String, Object> normalized = new LinkedHashMap<>(issue);
        normalized.put("labels", parseLabels(issue.get("labels")));
        normalized.putIfAbsent("milestone", Objects.toString(issue.get("milestone"), ""));
        normalized.putIfAbsent("project", Objects.toString(issue.get("project"), ""));
        return normalized;
    }

    @Transactional
    private Map<String, Object> classifyAndSave(String repoId, Map<String, Object> issue, String token, String ownerLogin) {
        String title = Objects.toString(issue.get("title"), "");
        String body = Objects.toString(issue.get("body"), "");
        List<String> labels = parseLabels(issue.get("labels"));
        String milestone = Objects.toString(issue.get("milestone"), "");
        String project = Objects.toString(issue.get("project"), "");

        Classification classification = classify(title, body, labels, milestone, project);
        List<Map<String, Object>> contexts;
        try {
            contexts = knowledgeService.retrieveChunks(repoId, ownerLogin, title + "\n" + body, null, 4);
        } catch (Exception ignored) {
            contexts = List.of();
        }
        List<Map<String, Object>> relatedFiles = contexts.stream()
                .map(c -> Map.<String, Object>of(
                        "file", Objects.toString(c.get("file"), ""),
                        "line", c.get("line") instanceof Number n ? n.intValue() : 1))
                .toList();

        String summary = buildSummary(classification, title, labels, milestone, relatedFiles);
        String reply = buildReply(classification.type(), labels, relatedFiles);
        boolean llmEnhanced = false;

        // LLM 增强分类与回复
        if (llmService.configured() && !contexts.isEmpty()) {
            try {
                String llmReply = llmGenerateReply(title, body, labels, classification.type(), relatedFiles, contexts);
                if (llmReply != null && !llmReply.isBlank()) {
                    reply = llmReply;
                    llmEnhanced = true;
                }
            } catch (Exception ignored) {
                // LLM 不可用时回退到规则生成
            }
        }

        String analyzedAt = LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("issueId", Objects.toString(issue.get("id"), ""));
        result.put("repoId", repoId);
        result.put("number", issue.get("number"));
        result.put("title", title);
        result.put("type", classification.type());
        result.put("typeLabel", TYPE_LABELS.getOrDefault(classification.type(), "其他"));
        result.put("confidence", classification.confidence());
        result.put("summary", summary);
        result.put("suggestedReply", reply);
        result.put("reason", classification.reason());
        result.put("relatedFiles", relatedFiles);
        result.put("analyzedAt", analyzedAt);
        result.put("needsCodeChange", "bug_fix".equals(classification.type()));
        result.put("llmEnhanced", llmEnhanced);

        upsertAnalysis(result, labels, milestone, project);

        // 自动回复到 GitHub Issue
        int issueNumber = issue.get("number") instanceof Number n ? n.intValue() : 0;
        if (issueNumber > 0) {
            try {
                postIssueComment(repoId, issueNumber, reply, token);
            } catch (Exception ignored) {
                // GitHub 回复失败不影响主流程
            }
        }

        // 发送邮件通知
        try {
            String emailSubject = "[Issue #" + issueNumber + "] " + title + " - " + TYPE_LABELS.getOrDefault(classification.type(), "其他");
            String emailBody = "仓库: " + repoId + "\n"
                    + "Issue: #" + issueNumber + " " + title + "\n"
                    + "分类: " + TYPE_LABELS.getOrDefault(classification.type(), "其他") + " (置信度: " + String.format("%.0f%%", classification.confidence() * 100) + ")\n"
                    + "摘要: " + summary + "\n\n"
                    + "自动回复:\n" + reply + "\n\n"
                    + (llmEnhanced ? "（由 LLM 增强生成）\n\n" : "")
                    + "—— RepoPilot";
            notificationService.notifyIfEnabled(ownerLogin, emailSubject, emailBody);
        } catch (Exception ignored) {
            // 邮件通知失败不影响主流程
        }

        return result;
    }

    private void postIssueComment(String repoId, int issueNumber, String comment, String token) {
        try {
            JsonNode repo = github.get("/repositories/" + repoId, token);
            String fullName = repo.path("full_name").asText();
            if (fullName.isBlank()) return;
            github.post("/repos/" + fullName + "/issues/" + issueNumber + "/comments", token,
                    Map.of("body", "🤖 RepoPilot 自动分析回复：\n\n" + comment));
        } catch (Exception ignored) {
        }
    }

    private String llmGenerateReply(String title, String body, List<String> labels, String type,
                                     List<Map<String, Object>> relatedFiles,
                                     List<Map<String, Object>> contexts) {
        String labelText = labels.isEmpty() ? "无" : String.join(", ", labels);
        String fileInfo = relatedFiles.isEmpty() ? "未命中相关文件"
                : relatedFiles.stream().limit(5).map(f -> f.get("file").toString()).reduce((a, b) -> a + ", " + b).orElse("");
        String prompt = "你是一个开源项目维护者。请为以下 GitHub Issue 生成一条友好、专业的自动回复（不超过 200 字）。\n\n"
                + "Issue 标题: " + title + "\n"
                + "Issue 描述: " + (body.length() > 500 ? body.substring(0, 500) + "..." : body) + "\n"
                + "自动分类: " + TYPE_LABELS.getOrDefault(type, "其他") + "\n"
                + "标签: " + labelText + "\n"
                + "相关文件: " + fileInfo + "\n\n"
                + "回复要求：\n"
                + "- 语气友好、专业\n"
                + "- 针对分类类型给出针对性回复\n"
                + "- 如果是 bug_fix 类型，询问复现步骤\n"
                + "- 如果是 usage_question 类型，引导查看文档\n"
                + "- 如果是 feature_request 类型，感谢建议并说明会评估\n"
                + "- 如果是 duplicate 类型，请用户关注原 Issue\n"
                + "- 如果是 insufficient_info 类型，请用户补充信息\n"
                + "- 结尾不需要署名\n\n"
                + "回复：";

        try {
            return llmService.generateAnswer(prompt, "issue_reply", contexts);
        } catch (Exception e) {
            return null;
        }
    }

    private void upsertAnalysis(Map<String, Object> result, List<String> labels, String milestone, String project) {
        String issueId = Objects.toString(result.get("issueId"), "");
        IssueAnalysis entity = analysisRepository.findById(issueId).orElseGet(IssueAnalysis::new);
        entity.setIssueId(issueId);
        entity.setRepoId(Objects.toString(result.get("repoId"), ""));
        entity.setIssueNumber(result.get("number") instanceof Number n ? n.intValue() : null);
        entity.setIssueTitle(Objects.toString(result.get("title"), ""));
        entity.setIssueType(Objects.toString(result.get("type"), "other"));
        entity.setConfidence(result.get("confidence") instanceof Number n ? n.doubleValue() : 0.0);
        entity.setSummary(Objects.toString(result.get("summary"), ""));
        entity.setSuggestedReply(Objects.toString(result.get("suggestedReply"), ""));
        entity.setReason(Objects.toString(result.get("reason"), ""));
        entity.setRelatedFiles(JsonUtils.toJson(result.get("relatedFiles")));
        entity.setAnalyzedAt(Objects.toString(result.get("analyzedAt"), ""));
        entity.setLlmEnhanced(Boolean.TRUE.equals(result.get("llmEnhanced")));
        entity.setIssueLabels(JsonUtils.toJson(labels));
        entity.setIssueMilestone(milestone);
        entity.setIssueProject(project);
        analysisRepository.save(entity);
    }

    public boolean isAnalyzed(String issueId) {
        return analysisRepository.existsById(issueId);
    }

    public Map<String, Object> getAnalysis(String issueId) {
        return analysisRepository.findById(issueId).map(this::toAnalysisMap).orElse(null);
    }

    private Map<String, Object> toAnalysisMap(IssueAnalysis entity) {
        String issueType = entity.getIssueType();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("issueId", entity.getIssueId());
        result.put("repoId", entity.getRepoId());
        result.put("number", entity.getIssueNumber());
        result.put("title", entity.getIssueTitle());
        result.put("type", issueType);
        result.put("typeLabel", TYPE_LABELS.getOrDefault(issueType, "其他"));
        result.put("confidence", entity.getConfidence());
        result.put("summary", entity.getSummary());
        result.put("suggestedReply", entity.getSuggestedReply());
        result.put("reason", entity.getReason());
        result.put("relatedFiles", JsonUtils.parseMapList(entity.getRelatedFiles()));
        result.put("analyzedAt", entity.getAnalyzedAt());
        result.put("needsCodeChange", "bug_fix".equals(issueType));
        result.put("llmEnhanced", Boolean.TRUE.equals(entity.getLlmEnhanced()));
        return result;
    }

    private Classification classify(String title, String body, List<String> labels, String milestone, String project) {
        String labelText = String.join(" ", labels).toLowerCase();
        String metaText = (milestone + " " + project).toLowerCase();
        String text = (title + " " + body + " " + labelText + " " + metaText).toLowerCase();

        if (hasLabel(labels, "duplicate", "重复") || containsAny(text, "duplicate", "duplicated", "重复", "same as")) {
            return new Classification("duplicate", 0.92, "标签或内容表明该 Issue 可能重复。");
        }
        if (hasLabel(labels, "bug", "缺陷", "error") || containsAny(text, "bug", "error", "crash", "fail", "exception", "报错", "失败", "崩溃")) {
            return new Classification("bug_fix", 0.9, "标签或内容表明这是缺陷修复类 Issue。");
        }
        if (hasLabel(labels, "enhancement", "feature", "feature request", "改进", "功能")
                || containsAny(text, "feature", "enhancement", "proposal", "希望", "建议", "support", "add ")) {
            return new Classification("feature_request", 0.86, "标签、里程碑或内容表明这是功能改进请求。");
        }
        if (hasLabel(labels, "question", "help wanted", "usage", "咨询")
                || title.contains("?") || containsAny(text, "how", "what", "why", "怎么", "如何", "请问", "用法")) {
            return new Classification("usage_question", 0.84, "标签或内容表明这是使用咨询。");
        }
        if (hasLabel(labels, "invalid", "wontfix", "needs info", "信息不足")
                || body.trim().length() < 80 || containsAny(text, "not enough", "more info", "复现", "日志")) {
            return new Classification("insufficient_info", 0.8, "标签或正文信息不足，需要补充复现与日志。");
        }
        if (!milestone.isBlank() || !project.isBlank()) {
            return new Classification("feature_request", 0.72, "已关联里程碑/项目，更偏向计划内改进。");
        }
        return new Classification("other", 0.55, "未命中明显规则，暂归为其他。");
    }

    private boolean hasLabel(List<String> labels, String... keywords) {
        for (String label : labels) {
            String lower = label.toLowerCase();
            for (String keyword : keywords) {
                if (lower.contains(keyword.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }

    private String buildSummary(Classification classification, String title, List<String> labels, String milestone, List<Map<String, Object>> relatedFiles) {
        String label = TYPE_LABELS.getOrDefault(classification.type(), "其他");
        String labelPart = labels.isEmpty() ? "" : "标签：" + String.join("、", labels) + "。";
        String milestonePart = milestone.isBlank() ? "" : "里程碑：" + milestone + "。";
        if (!relatedFiles.isEmpty()) {
            String files = relatedFiles.stream().limit(3).map(f -> "`" + f.get("file") + "`").reduce((a, b) -> a + "、" + b).orElse("");
            return label + "：" + title + "。" + labelPart + milestonePart + "相关文件：" + files + "。";
        }
        return label + "：" + title + "。" + labelPart + milestonePart + "知识库未命中明确相关文件。";
    }

    private String buildReply(String issueType, List<String> labels, List<Map<String, Object>> relatedFiles) {
        String labelHint = labels.isEmpty() ? "" : "（标签：" + String.join(", ", labels) + "）";
        return switch (issueType) {
            case "duplicate" -> "感谢反馈" + labelHint + "。该 Issue 可能重复，我们会核对后合并跟踪。";
            case "insufficient_info" -> "感谢反馈。请补充复现步骤、期望/实际结果、版本信息与完整日志。";
            case "usage_question" -> "感谢提问。我们会结合文档与示例回复。"
                    + (relatedFiles.isEmpty() ? "" : " 可参考 `" + relatedFiles.getFirst().get("file") + "`。");
            case "bug_fix" -> "感谢报告。我们会根据标签与复现信息定位问题。"
                    + (relatedFiles.isEmpty() ? "" : " 初步相关文件：`" + relatedFiles.getFirst().get("file") + "`。");
            case "feature_request" -> "感谢建议。该需求会结合里程碑与项目规划评估优先级。";
            default -> "感谢反馈。我们会结合标签与项目上下文继续跟进。";
        };
    }

    @SuppressWarnings("unchecked")
    private List<String> parseLabels(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            List<String> labels = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof String s) {
                    labels.add(s);
                } else if (item instanceof Map<?, ?> map) {
                    labels.add(Objects.toString(map.get("name"), ""));
                }
            }
            return labels.stream().filter(s -> !s.isBlank()).distinct().toList();
        }
        return List.of();
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(Objects.toString(value, "0"));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private record Classification(String type, double confidence, String reason) {}
}
