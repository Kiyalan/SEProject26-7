package com.repopilot.issue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.github.GitHubService;
import com.repopilot.knowledge.KnowledgeService;
import com.repopilot.support.ProgressService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

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

    private final JdbcTemplate jdbc;
    private final KnowledgeService knowledgeService;
    private final GitHubService github;
    private final ProgressService progress;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, Deque<Map<String, Object>>> pendingByRepo = new ConcurrentHashMap<>();

    public IssueService(JdbcTemplate jdbc, KnowledgeService knowledgeService, GitHubService github, ProgressService progress) {
        this.jdbc = jdbc;
        this.knowledgeService = knowledgeService;
        this.github = github;
        this.progress = progress;
    }

    public void onIssuesLoaded(String repoId, List<Map<String, Object>> issues, String token) {
        Deque<Map<String, Object>> queue = pendingByRepo.computeIfAbsent(repoId, k -> new ArrayDeque<>());
        for (Map<String, Object> issue : issues) {
            String issueId = Objects.toString(issue.get("id"), "");
            if (issueId.isBlank() || isAnalyzed(issueId) || containsIssue(queue, issueId)) {
                continue;
            }
            queue.addLast(new LinkedHashMap<>(issue));
        }
        if (queue.size() >= BATCH_THRESHOLD) {
            flushBatch(repoId, token);
        }
    }

    public Map<String, Object> analyze(String repoId, Map<String, Object> issue, String token) {
        Map<String, Object> enriched = enrichIssue(repoId, issue, token);
        String issueId = Objects.toString(enriched.get("id"), "");
        Map<String, Object> cached = issueId.isBlank() ? null : getAnalysis(issueId);
        if (cached != null) {
            return cached;
        }

        Map<String, Object> result = classifyAndSave(repoId, enriched);
        enqueue(repoId, enriched);
        if (pendingByRepo.getOrDefault(repoId, new ArrayDeque<>()).size() >= BATCH_THRESHOLD) {
            flushBatch(repoId, token);
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

    private void flushBatch(String repoId, String token) {
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
                    classifyAndSave(repoId, enrichIssue(repoId, issue, token));
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

    private Map<String, Object> classifyAndSave(String repoId, Map<String, Object> issue) {
        String title = Objects.toString(issue.get("title"), "");
        String body = Objects.toString(issue.get("body"), "");
        List<String> labels = parseLabels(issue.get("labels"));
        String milestone = Objects.toString(issue.get("milestone"), "");
        String project = Objects.toString(issue.get("project"), "");

        Classification classification = classify(title, body, labels, milestone, project);
        List<Map<String, Object>> contexts = knowledgeService.retrieveChunks(repoId, title + "\n" + body, null, 4);
        List<Map<String, Object>> relatedFiles = contexts.stream()
                .map(c -> Map.<String, Object>of("file", c.get("file"), "line", c.get("line")))
                .toList();

        String summary = buildSummary(classification, title, labels, milestone, relatedFiles);
        String reply = buildReply(classification.type(), labels, relatedFiles);
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
        result.put("llmEnhanced", false);

        upsertAnalysis(result, labels, milestone, project);
        return result;
    }

    private void upsertAnalysis(Map<String, Object> result, List<String> labels, String milestone, String project) {
        Object issueId = result.get("issueId");
        int updated = jdbc.update(
                """
                UPDATE issue_analysis SET repo_id=?, issue_number=?, issue_title=?, issue_type=?,
                confidence=?, summary=?, suggested_reply=?, reason=?, related_files=?, analyzed_at=?,
                llm_enhanced=?, issue_labels=?, issue_milestone=?, issue_project=? WHERE issue_id=?
                """,
                result.get("repoId"), result.get("number"), result.get("title"), result.get("type"),
                result.get("confidence"), result.get("summary"), result.get("suggestedReply"), result.get("reason"),
                toJson(result.get("relatedFiles")), result.get("analyzedAt"), false,
                toJson(labels), milestone, project, issueId
        );
        if (updated == 0) {
            jdbc.update(
                    """
                    INSERT INTO issue_analysis (issue_id, repo_id, issue_number, issue_title, issue_type,
                    confidence, summary, suggested_reply, reason, related_files, analyzed_at, llm_enhanced,
                    issue_labels, issue_milestone, issue_project)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    issueId, result.get("repoId"), result.get("number"), result.get("title"), result.get("type"),
                    result.get("confidence"), result.get("summary"), result.get("suggestedReply"), result.get("reason"),
                    toJson(result.get("relatedFiles")), result.get("analyzedAt"), false,
                    toJson(labels), milestone, project
            );
        }
    }

    public boolean isAnalyzed(String issueId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM issue_analysis WHERE issue_id=?", Integer.class, issueId);
        return count != null && count > 0;
    }

    public Map<String, Object> getAnalysis(String issueId) {
        return jdbc.query(
                "SELECT * FROM issue_analysis WHERE issue_id=?",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    String issueType = rs.getString("issue_type");
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("issueId", issueId);
                    result.put("repoId", rs.getString("repo_id"));
                    result.put("number", rs.getInt("issue_number"));
                    result.put("title", rs.getString("issue_title"));
                    result.put("type", issueType);
                    result.put("typeLabel", TYPE_LABELS.getOrDefault(issueType, "其他"));
                    result.put("confidence", rs.getDouble("confidence"));
                    result.put("summary", rs.getString("summary"));
                    result.put("suggestedReply", rs.getString("suggested_reply"));
                    result.put("reason", rs.getString("reason"));
                    result.put("relatedFiles", readJsonList(rs.getString("related_files")));
                    result.put("analyzedAt", rs.getString("analyzed_at"));
                    result.put("needsCodeChange", "bug_fix".equals(issueType));
                    result.put("llmEnhanced", rs.getBoolean("llm_enhanced"));
                    return result;
                },
                issueId
        );
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

    private List<Map<String, Object>> readJsonList(String json) {
        try {
            return mapper.readValue(json == null ? "[]" : json, new TypeReference<>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private record Classification(String type, double confidence, String reason) {}
}
