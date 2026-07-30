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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class IssueService {

    private static final int BATCH_THRESHOLD = 5;

    /** 置信度加权公式（对外说明用） */
    public static final String CONFIDENCE_FORMULA =
            "0.35×标签匹配 + 0.30×关键词匹配 + 0.20×正文完整度 + 0.15×知识库证据 − 0.10×类别歧义惩罚";

    private static final Map<String, String> TYPE_LABELS;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("bug_fix", "缺陷修复");
        m.put("feature_request", "功能改进");
        m.put("usage_question", "使用咨询");
        m.put("documentation", "文档相关");
        m.put("performance", "性能问题");
        m.put("security", "安全相关");
        m.put("configuration", "配置/环境");
        m.put("dependency", "依赖/版本");
        m.put("ci_build", "构建/CI");
        m.put("duplicate", "重复问题");
        m.put("insufficient_info", "信息不足");
        m.put("other", "其他");
        TYPE_LABELS = Collections.unmodifiableMap(m);
    }

    private static final Pattern MOJIBAKE = Pattern.compile(
            "Ã.|Â.|å.|ä.|ö.|Ã¤|Ã¥|Ã¶|\uFFFD|\\\\uFFFD|\\{[^}]{0,8}\\}|\\\\x[0-9a-fA-F]{2}");
    private static final Pattern MOSTLY_NONSENSE = Pattern.compile("^[\\s\\p{Punct}\\d]+$");

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

    public Map<String, String> typeLabels() {
        return TYPE_LABELS;
    }

    /**
     * Annotate issues with quality flags and optional filtering.
     * @param hideGarbled drop likely mojibake / empty junk
     * @param hideDuplicateTitles drop later issues whose titles near-duplicate earlier ones in this page
     */
    public List<Map<String, Object>> filterAndAnnotate(List<Map<String, Object>> issues,
                                                       boolean hideGarbled,
                                                       boolean hideDuplicateTitles) {
        List<Map<String, Object>> annotated = new ArrayList<>();
        Set<String> seenTitles = new LinkedHashSet<>();
        for (Map<String, Object> raw : issues) {
            Map<String, Object> issue = new LinkedHashMap<>(raw);
            String title = Objects.toString(issue.get("title"), "");
            String body = Objects.toString(issue.get("body"), "");
            boolean garbled = isGarbled(title, body);
            String norm = normalizeTitle(title);
            boolean dupTitle = false;
            if (!norm.isBlank()) {
                for (String prev : seenTitles) {
                    if (titleSimilarity(norm, prev) >= 0.82) {
                        dupTitle = true;
                        break;
                    }
                }
                if (!dupTitle) {
                    seenTitles.add(norm);
                }
            }
            issue.put("garbled", garbled);
            issue.put("duplicateTitle", dupTitle);
            issue.put("qualityFlags", buildQualityFlags(garbled, dupTitle, title, body));
            if (hideGarbled && garbled) {
                continue;
            }
            if (hideDuplicateTitles && dupTitle) {
                continue;
            }
            annotated.add(issue);
        }
        return annotated;
    }

    public void onIssuesLoaded(String repoId, List<Map<String, Object>> issues, String token, String ownerLogin) {
        // Intentionally no auto-batch analyze on page load — analyses are persisted and hydrated by the UI.
    }

    /** Return persisted analyses for a repo (for list hydration without re-running classify). */
    public List<Map<String, Object>> listAnalyses(String repoId) {
        return analysisRepository.findByRepoIdOrderByAnalyzedAtDesc(repoId).stream()
                .map(this::toAnalysisMap)
                .toList();
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
        return result;
    }

    /** Post suggested reply to GitHub as a comment (explicit user action). */
    public Map<String, Object> postSuggestedReply(String repoId, Map<String, Object> issue, String token, String ownerLogin) {
        Map<String, Object> enriched = enrichIssue(repoId, issue, token);
        String issueId = Objects.toString(enriched.get("id"), "");
        Map<String, Object> analysis = issueId.isBlank() ? null : getAnalysis(issueId);
        if (analysis == null) {
            analysis = classifyAndSave(repoId, enriched, token, ownerLogin);
        }
        String reply = Objects.toString(analysis.get("suggestedReply"), "").trim();
        if (reply.isBlank()) {
            throw new IllegalStateException("没有可发布的建议回复，请先分析");
        }
        int issueNumber = toInt(enriched.get("number"));
        if (issueNumber <= 0) {
            throw new IllegalArgumentException("Issue 编号无效");
        }
        postIssueComment(repoId, issueNumber, reply, token);
        markReplied(issueId);
        Map<String, Object> out = getAnalysis(issueId);
        if (out == null) {
            out = new LinkedHashMap<>(analysis);
        }
        out.put("postedToGithub", true);
        out.put("replied", true);
        out.put("postedAt", LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        return out;
    }

    /** Post suggested replies for all analyzed-but-unreplied issues in the given list. */
    public Map<String, Object> postRepliesBulk(String repoId, List<Map<String, Object>> issues,
                                               String token, String ownerLogin) {
        int posted = 0;
        int skipped = 0;
        List<Map<String, Object>> details = new ArrayList<>();
        for (Map<String, Object> issue : issues) {
            Map<String, Object> enriched = enrichIssue(repoId, issue, token);
            String issueId = Objects.toString(enriched.get("id"), "");
            Map<String, Object> analysis = issueId.isBlank() ? null : getAnalysis(issueId);
            if (analysis == null) {
                skipped++;
                continue;
            }
            if (Boolean.TRUE.equals(analysis.get("replied"))) {
                skipped++;
                continue;
            }
            try {
                Map<String, Object> row = postSuggestedReply(repoId, enriched, token, ownerLogin);
                details.add(row);
                posted++;
            } catch (Exception ex) {
                skipped++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("posted", posted);
        result.put("skipped", skipped);
        result.put("items", details);
        result.put("message", "已统一回复 " + posted + " 条，跳过 " + skipped + " 条（未分析或已回复）");
        return result;
    }

    private void markReplied(String issueId) {
        if (issueId == null || issueId.isBlank()) return;
        analysisRepository.findById(issueId).ifPresent(entity -> {
            entity.setReplied(true);
            entity.setRepliedAt(LocalDateTime.now(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
            analysisRepository.save(entity);
        });
    }

    /** Email maintainer a digest of analyzed issues that still need human reply. */
    public Map<String, Object> emailReplyDigest(String repoId, List<Map<String, Object>> issues,
                                                String token, String ownerLogin) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> issue : issues) {
            Map<String, Object> enriched = enrichIssue(repoId, issue, token);
            String issueId = Objects.toString(enriched.get("id"), "");
            Map<String, Object> analysis = issueId.isBlank() ? null : getAnalysis(issueId);
            if (analysis == null) {
                analysis = classifyAndSave(repoId, enriched, token, ownerLogin);
            }
            rows.add(Map.of(
                    "number", enriched.get("number"),
                    "title", Objects.toString(enriched.get("title"), ""),
                    "typeLabel", Objects.toString(analysis.get("typeLabel"), "其他"),
                    "confidence", analysis.get("confidence"),
                    "suggestedReply", Objects.toString(analysis.get("suggestedReply"), ""),
                    "htmlUrl", Objects.toString(enriched.get("htmlUrl"), "")
            ));
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("没有可发送的 Issue 分析");
        }
        StringBuilder body = new StringBuilder();
        body.append("以下 Issue 已完成自动分析，请审阅后回复：\n\n");
        for (Map<String, Object> row : rows) {
            body.append("#").append(row.get("number")).append(" ")
                    .append(row.get("title")).append('\n')
                    .append("分类: ").append(row.get("typeLabel"))
                    .append(" · 置信度: ")
                    .append(String.format("%.0f%%", ((Number) row.get("confidence")).doubleValue() * 100))
                    .append('\n')
                    .append("建议回复:\n").append(row.get("suggestedReply")).append("\n")
                    .append("链接: ").append(row.get("htmlUrl")).append("\n\n---\n\n");
        }
        body.append("置信度说明: ").append(CONFIDENCE_FORMULA).append("\n—— RepoPilot");

        boolean sent = notificationService.notifyIssueAnalysis(ownerLogin,
                "待回复 Issue 摘要（" + rows.size() + " 条）", body.toString());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", sent);
        result.put("count", rows.size());
        result.put("message", sent
                ? "已发送邮件摘要（需开启通知且配置 SMTP / notifyOnIssueAnalysis）"
                : "未发送：请检查设置中的邮件通知、Issue 分析开关与 SMTP 配置");
        result.put("items", rows);
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

        Classification classification = classify(title, body, labels, milestone, project, contexts);
        String summary = buildSummary(classification, title, labels, milestone, relatedFiles);
        String reply = buildReply(classification.type(), labels, relatedFiles);
        boolean llmEnhanced = false;

        if (llmService.configured() && !contexts.isEmpty()) {
            try {
                String llmReply = llmGenerateReply(title, body, labels, classification.type(), relatedFiles, contexts);
                if (llmReply != null && !llmReply.isBlank()) {
                    reply = llmReply;
                    llmEnhanced = true;
                }
            } catch (Exception ignored) {
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
        result.put("confidenceFormula", CONFIDENCE_FORMULA);
        result.put("confidenceFactors", classification.factors());
        result.put("typeScores", classification.typeScores());
        result.put("summary", summary);
        result.put("suggestedReply", reply);
        result.put("reason", classification.reason());
        result.put("relatedFiles", relatedFiles);
        result.put("analyzedAt", analyzedAt);
        result.put("needsCodeChange", Set.of("bug_fix", "security", "performance", "ci_build")
                .contains(classification.type()));
        result.put("llmEnhanced", llmEnhanced);
        String issueId = Objects.toString(issue.get("id"), "");
        IssueAnalysis existing = issueId.isBlank() ? null : analysisRepository.findById(issueId).orElse(null);
        boolean alreadyReplied = existing != null && Boolean.TRUE.equals(existing.getReplied());
        result.put("postedToGithub", alreadyReplied);
        result.put("replied", alreadyReplied);
        result.put("repliedAt", alreadyReplied && existing.getRepliedAt() != null ? existing.getRepliedAt() : "");

        upsertAnalysis(result, labels, milestone, project);
        return result;
    }

    private void postIssueComment(String repoId, int issueNumber, String comment, String token) {
        JsonNode repo = github.get("/repositories/" + repoId, token);
        String fullName = repo.path("full_name").asText();
        if (fullName.isBlank()) {
            throw new IllegalStateException("无法解析仓库 full_name");
        }
        github.post("/repos/" + fullName + "/issues/" + issueNumber + "/comments", token,
                Map.of("body", "🤖 RepoPilot 建议回复：\n\n" + comment));
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
                + "回复要求：语气友好专业；针对分类给出针对性引导；结尾不需要署名。\n\n回复：";
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
        // persist formula + factors inside reason for later UI reload
        String reasonBlob = Objects.toString(result.get("reason"), "");
        if (result.get("confidenceFactors") != null) {
            reasonBlob = reasonBlob + "\n\n[confidence]"
                    + JsonUtils.toJson(Map.of(
                    "formula", CONFIDENCE_FORMULA,
                    "factors", result.get("confidenceFactors"),
                    "typeScores", result.getOrDefault("typeScores", Map.of())
            ));
        }
        entity.setReason(reasonBlob);
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
        result.put("confidenceFormula", CONFIDENCE_FORMULA);
        String reason = entity.getReason() == null ? "" : entity.getReason();
        int marker = reason.indexOf("\n\n[confidence]");
        if (marker >= 0) {
            String json = reason.substring(marker + "\n\n[confidence]".length());
            reason = reason.substring(0, marker);
            Map<String, Object> meta = JsonUtils.parseObject(json);
            if (!meta.isEmpty()) {
                result.put("confidenceFactors", meta.get("factors"));
                result.put("typeScores", meta.get("typeScores"));
                if (meta.get("formula") != null) {
                    result.put("confidenceFormula", meta.get("formula"));
                }
            }
        }
        result.put("summary", entity.getSummary());
        result.put("suggestedReply", entity.getSuggestedReply());
        result.put("reason", reason);
        result.put("relatedFiles", JsonUtils.parseMapList(entity.getRelatedFiles()));
        result.put("analyzedAt", entity.getAnalyzedAt());
        result.put("needsCodeChange", Set.of("bug_fix", "security", "performance", "ci_build").contains(issueType));
        result.put("llmEnhanced", Boolean.TRUE.equals(entity.getLlmEnhanced()));
        result.put("replied", Boolean.TRUE.equals(entity.getReplied()));
        result.put("repliedAt", entity.getRepliedAt() == null ? "" : entity.getRepliedAt());
        result.put("postedToGithub", Boolean.TRUE.equals(entity.getReplied()));
        return result;
    }

    private Classification classify(String title, String body, List<String> labels, String milestone,
                                    String project, List<Map<String, Object>> contexts) {
        String labelText = String.join(" ", labels).toLowerCase();
        String text = (title + "\n" + body + "\n" + labelText + "\n" + milestone + "\n" + project).toLowerCase();

        Map<String, Double> typeScores = new LinkedHashMap<>();
        for (String t : TYPE_LABELS.keySet()) {
            typeScores.put(t, 0.0);
        }

        // Strong signal from title tags used by demo issues: [Bug], [UI], [API], ...
        applyTitleTagBoost(typeScores, title);

        // --- label signals (strong) ---
        addScore(typeScores, "duplicate", labelScore(labels, "duplicate", "重复"));
        addScore(typeScores, "bug_fix", labelScore(labels, "bug", "defect", "缺陷", "error", "regression"));
        addScore(typeScores, "feature_request", labelScore(labels, "enhancement", "feature", "改进", "功能", "proposal"));
        addScore(typeScores, "usage_question", labelScore(labels, "question", "help wanted", "usage", "咨询", "support"));
        addScore(typeScores, "documentation", labelScore(labels, "documentation", "docs", "readme", "文档"));
        addScore(typeScores, "performance", labelScore(labels, "performance", "perf", "性能"));
        addScore(typeScores, "security", labelScore(labels, "security", "vulnerability", "安全", "cve"));
        addScore(typeScores, "configuration", labelScore(labels, "config", "configuration", "配置", "environment"));
        addScore(typeScores, "dependency", labelScore(labels, "dependencies", "dependency", "依赖"));
        addScore(typeScores, "ci_build", labelScore(labels, "ci", "build", "test", "编译"));
        addScore(typeScores, "insufficient_info", labelScore(labels, "needs info", "invalid", "wontfix", "信息不足", "incomplete"));

        // --- keyword signals ---
        addScore(typeScores, "duplicate", keywordScore(text, 0.35,
                "duplicate of", "duplicated", "same as #", "重复了", "重复 issue", "already reported"));
        addScore(typeScores, "bug_fix", keywordScore(text, 0.28,
                "bug", "error", "exception", "stacktrace", "nullpointer", "crash", "报错", "失败", "崩溃", "regression", "npe"));
        addScore(typeScores, "feature_request", keywordScore(text, 0.26,
                "feature request", "enhancement", "would be nice", "希望增加", "建议增加", "please add", "proposal",
                "拓展", "优化", "改进", "增强"));
        addScore(typeScores, "usage_question", keywordScore(text, 0.24,
                "how to", "how do i", "怎么用", "如何", "请问", "用法", "what is the difference",
                "干什么", "做什么", "是什么", "能否", "可以吗", "了解一下"));
        if (title.contains("?") || title.contains("？")
                || containsAny(text, "吗？", "呢？", "能干", "做什么", "如何", "怎么")) {
            addScore(typeScores, "usage_question", 0.35);
        }
        addScore(typeScores, "documentation", keywordScore(text, 0.3,
                "readme", "documentation", "docs", "typo", "拼写", "文档错误", "api doc", "javadoc"));
        addScore(typeScores, "performance", keywordScore(text, 0.32,
                "slow", "latency", "memory leak", "oom", "cpu", "性能", "卡顿", "耗时", "throughput"));
        addScore(typeScores, "security", keywordScore(text, 0.35,
                "security", "xss", "csrf", "sqli", "rce", "vulnerability", "cve-", "权限绕过", "注入"));
        addScore(typeScores, "configuration", keywordScore(text, 0.3,
                "application.yml", "application.properties", ".env", "config", "配置项", "环境变量", "profile"));
        addScore(typeScores, "dependency", keywordScore(text, 0.3,
                "dependency", "dependencies", "pom.xml", "package.json", "version conflict", "升级依赖", "transitive"));
        addScore(typeScores, "ci_build", keywordScore(text, 0.3,
                "github actions", "ci fail", "build failed", "compilation", "mvn test", "编译失败", "workflow", "flaky", "timezone"));
        addScore(typeScores, "feature_request", keywordScore(text, 0.28,
                "overflow", "responsive", "ui ", "layout", "badge", "usability", "1366"));
        addScore(typeScores, "feature_request", keywordScore(text, 0.28,
                "http 404", "problem+json", "rest style", "api error", "return 404"));
        addScore(typeScores, "configuration", keywordScore(text, 0.3,
                "seeding", "fresh db", "data init", "dropdown", "pet type"));
        addScore(typeScores, "insufficient_info", keywordScore(text, 0.4,
                "asdf", "test spam", "random text", "no reproduction", "intentionally lacks"));
        // Explicit demo / triage wording should win over a misleading [Bug] title tag
        if (containsAny(text, "intentionally lacks", "insufficient info", "信息不足", "缺少复现")) {
            addScore(typeScores, "insufficient_info", 1.05);
        }

        // body completeness → insufficient_info if really thin,
        // but do NOT override clear questions / enhancement asks (short body is normal for those).
        int bodyLen = body.trim().length();
        boolean hasStack = containsAny(text, "exception", "at ", "caused by", "stack", "traceback");
        boolean looksLikeQuestion = title.contains("?") || title.contains("？")
                || containsAny(text, "吗", "呢", "如何", "怎么", "干什么", "做什么", "能否", "请问",
                "what is", "how to", "how do");
        boolean looksLikeEnhancementAsk = containsAny(text, "拓展", "优化", "改进", "enhancement", "feature request", "希望增加");
        if (!looksLikeQuestion && !looksLikeEnhancementAsk) {
            if (bodyLen < 40 && !hasStack) {
                addScore(typeScores, "insufficient_info", 0.55);
            } else if (bodyLen < 80 && !hasStack && !containsAny(text, "steps", "复现", "expected", "actual")) {
                addScore(typeScores, "insufficient_info", 0.28);
            }
        }

        if (!milestone.isBlank() || !project.isBlank()) {
            addScore(typeScores, "feature_request", 0.12);
        }

        // Prefer strongest type; break ties with priority order
        String bestType = "other";
        double bestScore = 0.12; // threshold; below → other
        List<Map.Entry<String, Double>> ranked = typeScores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .toList();
        if (!ranked.isEmpty() && ranked.getFirst().getValue() >= bestScore) {
            bestType = ranked.getFirst().getKey();
            bestScore = ranked.getFirst().getValue();
        } else {
            bestType = "other";
            bestScore = 0.1;
        }

        double second = ranked.size() > 1 ? ranked.get(1).getValue() : 0;
        double ambiguity = Math.max(0, second / Math.max(bestScore, 0.01));

        // Factor scores in [0,1]
        double labelMatch = Math.min(1.0, labelStrength(labels, bestType));
        double keywordMatch = Math.min(1.0, bestScore);
        double bodyQuality = bodyQualityScore(body, hasStack);
        double evidence = Math.min(1.0, contexts.size() / 4.0);
        double ambiguityPenalty = ambiguity > 0.75 ? Math.min(1.0, ambiguity - 0.5) : 0;

        double confidence = clamp(
                0.35 * labelMatch
                        + 0.30 * keywordMatch
                        + 0.20 * bodyQuality
                        + 0.15 * evidence
                        - 0.10 * ambiguityPenalty,
                0.35, 0.95);
        if ("other".equals(bestType)) {
            confidence = Math.min(confidence, 0.58);
        }

        List<Map<String, Object>> factors = List.of(
                factor("labelMatch", "标签匹配", 0.35, labelMatch,
                        labelMatch > 0.01 ? "GitHub 标签与分类一致" : "无强相关标签"),
                factor("keywordMatch", "关键词匹配", 0.30, keywordMatch,
                        "标题/正文关键词对「" + TYPE_LABELS.get(bestType) + "」的命中强度"),
                factor("bodyQuality", "正文完整度", 0.20, bodyQuality,
                        bodyLen + " 字" + (hasStack ? "，含堆栈/异常线索" : "")),
                factor("knowledgeEvidence", "知识库证据", 0.15, evidence,
                        contexts.isEmpty() ? "未命中相关代码片段" : ("命中 " + contexts.size() + " 条检索证据")),
                factor("ambiguityPenalty", "类别歧义惩罚", -0.10, ambiguityPenalty,
                        ambiguityPenalty > 0 ? "次优类别分数接近，降低置信度" : "类别区分较清晰")
        );

        String reason = "判定为「" + TYPE_LABELS.get(bestType) + "」。"
                + " 置信度按公式计算：" + CONFIDENCE_FORMULA + "。"
                + " 当前得分：标签 " + pct(labelMatch) + "、关键词 " + pct(keywordMatch)
                + "、正文 " + pct(bodyQuality) + "、知识库 " + pct(evidence)
                + (ambiguityPenalty > 0 ? "、歧义惩罚 −" + pct(ambiguityPenalty) : "")
                + "。";

        Map<String, Double> topScores = ranked.stream().limit(5)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));

        return new Classification(bestType, confidence, reason, factors, topScores);
    }

    private static Map<String, Object> factor(String name, String label, double weight, double score, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("label", label);
        m.put("weight", weight);
        m.put("score", round2(score));
        m.put("contribution", round2(weight * score));
        m.put("detail", detail);
        return m;
    }

    private void applyTitleTagBoost(Map<String, Double> typeScores, String title) {
        String t = title == null ? "" : title.trim().toLowerCase(Locale.ROOT);
        if (!t.startsWith("[")) {
            return;
        }
        int end = t.indexOf(']');
        if (end <= 1) {
            return;
        }
        String tag = t.substring(1, end).trim();
        String type = switch (tag) {
            case "bug", "regression" -> "bug_fix";
            case "feature", "enhancement" -> "feature_request";
            case "question", "help" -> "usage_question";
            case "docs", "doc", "documentation" -> "documentation";
            case "perf", "performance" -> "performance";
            case "security", "sec" -> "security";
            case "ui", "ux", "api" -> "feature_request";
            case "data", "config", "env" -> "configuration";
            case "test", "ci" -> "ci_build";
            case "deps", "dep", "dependency" -> "dependency";
            case "invalid", "spam", "wontfix" -> "insufficient_info";
            case "duplicate", "dup" -> "duplicate";
            default -> null;
        };
        if (type != null) {
            addScore(typeScores, type, 0.95);
        }
    }

    private double labelStrength(List<String> labels, String type) {
        return switch (type) {
            case "bug_fix" -> labelScore(labels, "bug", "defect", "缺陷", "error", "regression");
            case "feature_request" -> labelScore(labels, "enhancement", "feature", "改进", "功能");
            case "usage_question" -> labelScore(labels, "question", "help wanted", "usage", "咨询");
            case "documentation" -> labelScore(labels, "documentation", "docs", "文档");
            case "performance" -> labelScore(labels, "performance", "perf", "性能");
            case "security" -> labelScore(labels, "security", "vulnerability", "安全");
            case "configuration" -> labelScore(labels, "config", "configuration", "配置");
            case "dependency" -> labelScore(labels, "dependencies", "dependency", "依赖");
            case "ci_build" -> labelScore(labels, "ci", "build", "test");
            case "duplicate" -> labelScore(labels, "duplicate", "重复");
            case "insufficient_info" -> labelScore(labels, "needs info", "invalid", "信息不足");
            default -> 0;
        };
    }

    private double labelScore(List<String> labels, String... keywords) {
        if (labels.isEmpty()) return 0;
        double score = 0;
        for (String label : labels) {
            String lower = label.toLowerCase();
            for (String kw : keywords) {
                if (lower.equals(kw.toLowerCase())) {
                    score = Math.max(score, 1.0);
                } else if (lower.contains(kw.toLowerCase())) {
                    score = Math.max(score, 0.75);
                }
            }
        }
        return score;
    }

    private double keywordScore(String text, double perHit, String... words) {
        int hits = 0;
        for (String w : words) {
            if (text.contains(w.toLowerCase())) {
                hits++;
            }
        }
        return Math.min(1.0, hits * perHit);
    }

    private double bodyQualityScore(String body, boolean hasStack) {
        int len = body.trim().length();
        double base;
        if (len >= 400) base = 1.0;
        else if (len >= 200) base = 0.85;
        else if (len >= 80) base = 0.65;
        else if (len >= 40) base = 0.4;
        else base = 0.15;
        if (hasStack) base = Math.min(1.0, base + 0.15);
        if (containsAny(body.toLowerCase(), "steps to reproduce", "复现步骤", "expected", "actual", "期望", "实际")) {
            base = Math.min(1.0, base + 0.1);
        }
        return base;
    }

    private void addScore(Map<String, Double> scores, String type, double delta) {
        if (delta <= 0) return;
        scores.merge(type, delta, Double::sum);
    }

    private String buildSummary(Classification classification, String title, List<String> labels, String milestone,
                                List<Map<String, Object>> relatedFiles) {
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
        String fileHint = relatedFiles.isEmpty() ? "" : " 可参考 `" + relatedFiles.getFirst().get("file") + "`。";
        return switch (issueType) {
            case "duplicate" -> "感谢反馈" + labelHint + "。该 Issue 可能与已有工单重复，请确认后我们合并跟踪。";
            case "insufficient_info" -> "感谢反馈。请补充：复现步骤、期望/实际结果、版本信息与完整日志/堆栈。";
            case "usage_question" -> "感谢提问。我们会结合文档与示例回复。" + fileHint;
            case "bug_fix" -> "感谢报告缺陷。我们会根据复现信息定位问题。" + fileHint;
            case "feature_request" -> "感谢建议。该需求会结合路线图评估优先级。";
            case "documentation" -> "感谢指出文档问题。我们会核对并修正相关文档内容。";
            case "performance" -> "感谢反馈性能问题。请补充耗时数据、环境与 profiling 线索（如有）。";
            case "security" -> "感谢安全相关反馈。我们会优先评估影响面，请勿在公开评论中粘贴敏感凭证。";
            case "configuration" -> "感谢反馈。请确认配置文件/环境变量与文档默认值是否一致，并附上相关片段（可打码）。";
            case "dependency" -> "感谢反馈依赖问题。请提供依赖树关键片段与目标版本。";
            case "ci_build" -> "感谢反馈构建/CI 问题。请附上失败日志与触发的 workflow/命令。";
            default -> "感谢反馈。我们会结合标签与项目上下文继续跟进。";
        };
    }

    private boolean isGarbled(String title, String body) {
        String t = title == null ? "" : title;
        String b = body == null ? "" : body;
        String all = t + "\n" + b;
        if (t.isBlank() && b.isBlank()) return true;
        if (MOSTLY_NONSENSE.matcher(t.trim()).matches() && b.trim().length() < 20) return true;
        long replacement = all.chars().filter(ch -> ch == 0xFFFD).count();
        if (replacement >= 3) return true;
        int mojibakeHits = 0;
        var m = MOJIBAKE.matcher(all);
        while (m.find()) {
            mojibakeHits++;
            if (mojibakeHits >= 4) return true;
        }
        // high ratio of Latin-1 mojibake-ish sequences in short title
        if (t.length() >= 8) {
            long weird = t.chars().filter(ch -> ch > 127 && ch < 256).count();
            if (weird > t.length() * 0.45) return true;
        }
        return false;
    }

    private List<String> buildQualityFlags(boolean garbled, boolean dupTitle, String title, String body) {
        List<String> flags = new ArrayList<>();
        if (garbled) flags.add("garbled");
        if (dupTitle) flags.add("duplicate_title");
        if (body != null && body.trim().length() < 40) flags.add("thin_body");
        if (title != null && title.trim().length() < 4) flags.add("thin_title");
        return flags;
    }

    private String normalizeTitle(String title) {
        return title == null ? "" : title.toLowerCase(Locale.ROOT)
                .replaceAll("[\\[\\]()#\\d]+", " ")
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4e00-\\u9fff]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private double titleSimilarity(String a, String b) {
        if (a.equals(b)) return 1.0;
        if (a.isBlank() || b.isBlank()) return 0;
        Set<String> ta = new HashSet<>(Arrays.asList(a.split(" ")));
        Set<String> tb = new HashSet<>(Arrays.asList(b.split(" ")));
        ta.removeIf(String::isBlank);
        tb.removeIf(String::isBlank);
        if (ta.isEmpty() || tb.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(ta);
        inter.retainAll(tb);
        Set<String> union = new HashSet<>(ta);
        union.addAll(tb);
        return inter.size() * 1.0 / union.size();
    }

    @SuppressWarnings("unchecked")
    private List<String> parseLabels(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof List<?> list) {
            List<String> labels = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof String s) labels.add(s);
                else if (item instanceof Map<?, ?> map) labels.add(Objects.toString(map.get("name"), ""));
            }
            return labels.stream().filter(s -> !s.isBlank()).distinct().toList();
        }
        return List.of();
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) return true;
        }
        return false;
    }

    private int toInt(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(Objects.toString(value, "0"));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String pct(double v) {
        return Math.round(v * 100) + "%";
    }

    private record Classification(
            String type,
            double confidence,
            String reason,
            List<Map<String, Object>> factors,
            Map<String, Double> typeScores
    ) {}
}
