package com.repopilot.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class KnowledgeQueryService {

    private static final Pattern COMMIT_COUNT = Pattern.compile("(\\d+)\\s*(?:个)?\\s*(?:commit|提交)", Pattern.CASE_INSENSITIVE);
    private static final List<String> API_PATH_HINTS = List.of(
            "openapi", "swagger", "controller", "api/generated");
    private static final List<String> DEPLOY_PATH_HINTS = List.of(
            "readme", "run.ps1", "application.yml", "application.yaml", "package.json",
            "pom.xml", "vite.config", "dockerfile", "docker-compose", ".env");
    private static final List<String> OVERVIEW_PATH_HINTS = List.of(
            "readme", "README.md", "README", "package.json", "pom.xml", "docker-compose");

    private final KnowledgeService knowledgeService;
    private final PortfolioService portfolioService;

    public KnowledgeQueryService(KnowledgeService knowledgeService, PortfolioService portfolioService) {
        this.knowledgeService = knowledgeService;
        this.portfolioService = portfolioService;
    }

    public QueryResult retrieve(String repoId, String question, String ownerLogin) {
        return retrieve(repoId, question, ownerLogin, null, List.of());
    }

    public QueryResult retrieve(String repoId, String question, String ownerLogin, String githubToken) {
        return retrieve(repoId, question, ownerLogin, githubToken, List.of());
    }

    public QueryResult retrieve(String repoId, String question, String ownerLogin, String githubToken,
                                List<String> priorUserMessages) {
        // Retrieval query may include prior turns so follow-ups like「该类还有哪些方法」resolve.
        String retrievalQuestion = expandWithHistory(question, priorUserMessages);
        String lower = retrievalQuestion.toLowerCase();
        Set<String> intents = new LinkedHashSet<>();
        if (containsAny(lower, "commit", "提交", "版本历史", "提交历史", "弃用", "废弃", "采用",
                "revert", "回滚", "无效", "过时", "空合并")) {
            intents.add("history");
        }
        if (containsAny(lower, "api", "接口", "endpoint", "openapi", "swagger")) {
            intents.add("api");
        }
        if (containsAny(lower, "部署", "布置", "启动", "安装", "环境变量", "服务器", "docker", "上线",
                "deploy", "deployment", "start", "install", "environment variable")) {
            intents.add("deployment");
        }
        if (isOverviewQuestion(lower) && !isSpecificTypeQuestion(retrievalQuestion)) {
            intents.add("overview");
        }
        if (isSpecificTypeQuestion(retrievalQuestion)
                || containsAny(lower, "哪些方法", "所有方法", "有哪些方法", "方法列表", "源码", "原代码")) {
            intents.add("code");
        }
        if (containsAny(lower, "参与者", "贡献者", "collaborator", "contributor", "作者", "谁写的", "维护者")) {
            intents.add("history");
        }
        if (lower.contains("分支") || lower.contains("branch")) {
            intents.add("branches");
        }
        if (containsAny(lower, "构建知识库", "知识库构建", "知识库是否", "知识库有没有",
                "是否构建", "有没有构建", "还没有构建", "尚未构建", "有没有索引",
                "是否已索引", "知识库就绪", "知识库准备好")) {
            intents.add("knowledge_status");
        }
        if (containsAny(lower, "哪些仓库", "哪个仓库", "仓库列表", "几乎没有内容", "空仓库", "没有内容",
                "与main", "与 main", "版本落后", "落后很多", "无关", "portfolio", "repos",
                "仓库是", "仓库的内容")) {
            if (!intents.contains("branches")) {
                intents.add("portfolio");
            }
        }
        if (intents.isEmpty()) {
            intents.add("code");
        }

        List<Map<String, Object>> contexts = new ArrayList<>();
        // Only inject status evidence when the user actually asks about knowledge readiness.
        if (intents.contains("knowledge_status")) {
            try {
                contexts.add(knowledgeService.knowledgeStatusContext(repoId, ownerLogin));
            } catch (Exception ex) {
                contexts.add(systemNotice("knowledge/status",
                        "无法读取知识库状态：" + ex.getMessage()));
            }
        }
        try {
            if (intents.contains("branches")) {
                contexts.addAll(knowledgeService.branchContexts(repoId, ownerLogin));
            }
            if (intents.contains("portfolio")) {
                contexts.addAll(portfolioContexts(githubToken, ownerLogin));
            }
            if (intents.contains("history")) {
                contexts.addAll(knowledgeService.commitHistoryContexts(repoId, ownerLogin, requestedCommitCount(retrievalQuestion)));
            }
            if (intents.contains("overview")) {
                contexts.add(knowledgeService.repositoryOverviewContext(repoId, ownerLogin));
                contexts.addAll(knowledgeService.retrieveChunksByPathHints(
                        repoId, ownerLogin, retrievalQuestion + " 项目介绍 目的 架构 模块 结构",
                        OVERVIEW_PATH_HINTS, 10));
                contexts.addAll(knowledgeService.graphRagContexts(
                        repoId, ownerLogin, retrievalQuestion + " 项目说明 README 架构 模块 主流程", 10));
            }
            if (intents.contains("api")) {
                contexts.addAll(knowledgeService.graphRagContexts(
                        repoId, ownerLogin, retrievalQuestion + " API endpoints controllers OpenAPI", 12));
                if (contexts.stream().noneMatch(row ->
                        "graph_rag_answer".equals(row.get("sourceType"))
                                || "graph_explore".equals(row.get("sourceType"))
                                || "api_spec".equals(row.get("sourceType"))
                                || "source_code".equals(row.get("sourceType")))) {
                    contexts.addAll(knowledgeService.retrieveChunksByPathHints(
                            repoId, ownerLogin, retrievalQuestion + " controller endpoint operationId", API_PATH_HINTS, 8));
                }
            }
            if (intents.contains("deployment")) {
                contexts.addAll(knowledgeService.graphRagContexts(
                        repoId, ownerLogin, retrievalQuestion + " 启动 部署 docker compose", 8));
                contexts.addAll(knowledgeService.retrieveChunksByPathHints(
                        repoId, ownerLogin, retrievalQuestion + " 启动 配置 端口 环境变量", DEPLOY_PATH_HINTS, 8));
            }
            // Prefer GraphRAG for code questions. Status-only / structured-only skip heavy explore.
            boolean statusOnly = intents.contains("knowledge_status")
                    && !intents.contains("code")
                    && !intents.contains("overview")
                    && !intents.contains("api")
                    && !intents.contains("deployment")
                    && !intents.contains("history")
                    && !intents.contains("portfolio")
                    && !intents.contains("branches");
            // Specific class/method questions must use the raw question (not README-biased overview query).
            if (!statusOnly && intents.contains("code")
                    && !intents.contains("portfolio")
                    && !intents.contains("history")
                    && !intents.contains("branches")) {
                contexts.addAll(knowledgeService.graphRagContexts(repoId, ownerLogin, retrievalQuestion, 20));
            } else if (!statusOnly && contexts.isEmpty()) {
                contexts.addAll(knowledgeService.graphRagContexts(repoId, ownerLogin, retrievalQuestion, 16));
            }
        } catch (Exception ex) {
            Map<String, Object> notice = new LinkedHashMap<>();
            notice.put("file", "knowledge/unavailable");
            notice.put("line", 1);
            notice.put("endLine", 1);
            notice.put("content", "知识库暂不可用：" + ex.getMessage() + "。请先构建知识库并确认 CodeWiki 已启动。");
            notice.put("score", 0);
            notice.put("retrievalType", "keyword");
            notice.put("sourceType", "system");
            contexts.add(notice);
        }

        contexts.removeIf(KnowledgeQueryService::isLowValueContext);
        int bound = intents.contains("history") || intents.contains("portfolio") || intents.contains("branches")
                ? 60 : 55;
        List<Map<String, Object>> deduplicated = deduplicateAndBound(contexts, bound);
        return new QueryResult(String.join("+", intents), deduplicated);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> portfolioContexts(String githubToken, String ownerLogin) {
        if (githubToken == null || githubToken.isBlank()) {
            return List.of(systemNotice("portfolio/unavailable",
                    "缺少 GitHub token，无法拉取多仓库列表。请重新登录后再问「哪些仓库…」。"));
        }
        Map<String, Object> overview = portfolioService.overview(githubToken, ownerLogin, 50);
        Map<String, Object> summary = (Map<String, Object>) overview.getOrDefault("summary", Map.of());
        List<Map<String, Object>> repos = (List<Map<String, Object>>) overview.getOrDefault("repos", List.of());

        StringBuilder sb = new StringBuilder();
        sb.append("多仓库 Portfolio 摘要\n")
                .append("repoCount=").append(summary.getOrDefault("repoCount", 0))
                .append(", indexedCount=").append(summary.getOrDefault("indexedCount", 0))
                .append(", totalIndexedFiles=").append(summary.getOrDefault("totalIndexedFiles", 0))
                .append(", totalChunks=").append(summary.getOrDefault("totalChunks", 0))
                .append('\n');
        sb.append("判断说明: fileCount/chunkCount=0 或未索引 → 几乎没有本地知识库内容；")
                .append("pushedAt 很久未更新 → 可能相对落后；")
                .append("本列表不直接做与 upstream main 的提交差，只能结合 pushedAt/索引规模估计。\n");

        List<String> emptyish = new ArrayList<>();
        List<String> stale = new ArrayList<>();
        int line = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> repo : repos) {
            Map<String, Object> knowledge = (Map<String, Object>) repo.getOrDefault("knowledge", Map.of());
            boolean indexed = Boolean.TRUE.equals(knowledge.get("indexed"));
            int files = number(knowledge.get("fileCount"));
            int chunks = number(knowledge.get("chunkCount"));
            String fullName = String.valueOf(repo.getOrDefault("fullName", ""));
            String pushedAt = String.valueOf(repo.getOrDefault("pushedAt", ""));
            String entry = fullName
                    + " | indexed=" + indexed
                    + " | files=" + files
                    + " | chunks=" + chunks
                    + " | stars=" + repo.getOrDefault("stars", 0)
                    + " | language=" + repo.getOrDefault("language", "")
                    + " | pushedAt=" + pushedAt;
            sb.append("- ").append(entry).append('\n');
            if (!indexed || (files <= 0 && chunks <= 0)) {
                emptyish.add(fullName);
            }
            // Rough staleness: date string yyyy-mm-dd lexicographically old enough is left to LLM;
            // still flag repos with no recent push marker.
            if (pushedAt.isBlank() || pushedAt.compareTo("2025-01-01") < 0) {
                stale.add(fullName);
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("file", "portfolio/repos/" + fullName);
            row.put("line", ++line);
            row.put("endLine", line);
            row.put("symbolName", fullName);
            row.put("symbolKind", "repository");
            row.put("score", 100 - line);
            row.put("retrievalType", "structured");
            row.put("sourceType", "portfolio");
            row.put("content", entry);
            rows.add(row);
        }

        Map<String, Object> digest = new LinkedHashMap<>();
        digest.put("file", "portfolio/overview");
        digest.put("line", 1);
        digest.put("endLine", 1);
        digest.put("symbolName", "portfolio_overview");
        digest.put("symbolKind", "summary");
        digest.put("score", 200);
        digest.put("retrievalType", "structured");
        digest.put("sourceType", "portfolio");
        digest.put("content", sb
                + "\n候选-几乎没有内容: " + (emptyish.isEmpty() ? "(无)" : String.join(", ", emptyish))
                + "\n候选-可能落后/很久未推送: " + (stale.isEmpty() ? "(无)" : String.join(", ", stale)));
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(digest);
        result.addAll(rows);
        return result;
    }

    private static Map<String, Object> systemNotice(String file, String content) {
        Map<String, Object> notice = new LinkedHashMap<>();
        notice.put("file", file);
        notice.put("line", 1);
        notice.put("endLine", 1);
        notice.put("content", content);
        notice.put("score", 0);
        notice.put("retrievalType", "keyword");
        notice.put("sourceType", "system");
        return notice;
    }

    private static int number(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static boolean isLowValueContext(Map<String, Object> row) {
        String sourceType = String.valueOf(row.getOrDefault("sourceType", ""));
        if ("portfolio".equals(sourceType) || "commit_history".equals(sourceType)
                || "branch_list".equals(sourceType)
                || "graph_explore".equals(sourceType) || "graph_rag_answer".equals(sourceType)
                || "community".equals(sourceType) || "graph_nodes".equals(sourceType)
                || "graph_relationships".equals(sourceType)
                || "system".equals(sourceType) || "repository_overview".equals(sourceType)
                || "knowledge_status".equals(sourceType)
                || "source_code".equals(sourceType) || "code".equals(sourceType)) {
            return false;
        }
        String file = String.valueOf(row.getOrDefault("file", "")).toLowerCase().replace('\\', '/');
        String content = String.valueOf(row.getOrDefault("content", "")).trim();
        if (content.length() < 16) {
            return true;
        }
        if (file.contains("tsconfig") || file.contains("/api/generated/") || file.endsWith(".gen.ts")) {
            return true;
        }
        String compact = content.replaceAll("\\s+", "");
        return compact.matches("[{}\\[\\],:\"]+");
    }

    private List<Map<String, Object>> deduplicateAndBound(List<Map<String, Object>> rows, int maxItems) {
        // Prefer real source excerpts over structural summaries when budgeting context.
        List<Map<String, Object>> ordered = new ArrayList<>(rows);
        ordered.sort((a, b) -> Integer.compare(sourcePriority(b), sourcePriority(a)));
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        int totalChars = 0;
        for (Map<String, Object> row : ordered) {
            String key = row.getOrDefault("file", "") + ":" + row.getOrDefault("line", 0)
                    + ":" + row.getOrDefault("symbolName", "");
            if (unique.containsKey(key)) {
                continue;
            }
            String content = String.valueOf(row.getOrDefault("content", ""));
            if (!unique.isEmpty() && totalChars + content.length() > 64_000) {
                continue;
            }
            unique.put(key, row);
            totalChars += content.length();
            if (unique.size() >= maxItems) {
                break;
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static int sourcePriority(Map<String, Object> row) {
        String type = String.valueOf(row.getOrDefault("sourceType", ""));
        String retrieval = String.valueOf(row.getOrDefault("retrievalType", ""));
        // Communities orient Q&A; concrete source/API still win when present.
        if ("local_api".equals(retrieval)) {
            return 110;
        }
        if ("local_file".equals(retrieval)) {
            return 105;
        }
        return switch (type) {
            case "source_code", "code" -> 100;
            case "community" -> 85;
            case "repository_overview" -> 80;
            case "graph_explore" -> 45;
            default -> 50;
        };
    }

    private int requestedCommitCount(String question) {
        Matcher matcher = COMMIT_COUNT.matcher(question);
        if (!matcher.find()) {
            return 30;
        }
        return Math.min(Math.max(Integer.parseInt(matcher.group(1)), 1), 50);
    }

    private boolean containsAny(String value, String... terms) {
        return containsAnyStatic(value, terms);
    }

    private static boolean containsAnyStatic(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static String expandWithHistory(String question, List<String> priorUserMessages) {
        if (priorUserMessages == null || priorUserMessages.isEmpty()) {
            return question;
        }
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, priorUserMessages.size() - 3);
        for (int i = from; i < priorUserMessages.size(); i++) {
            String prior = priorUserMessages.get(i);
            if (prior == null || prior.isBlank()) {
                continue;
            }
            sb.append(prior.trim()).append('\n');
        }
        sb.append(question == null ? "" : question.trim());
        return sb.toString();
    }

    private boolean isOverviewQuestion(String lower) {
        if (containsAny(lower,
                "项目目的", "项目的目的", "该项目的目的", "主要目的", "主要功能",
                "项目介绍", "介绍项目", "是什么项目", "项目是什么", "主要内容", "项目内容",
                "项目结构", "代码结构", "目录结构", "模块结构", "仓库结构",
                "项目架构", "整体架构", "系统架构", "模块划分",
                "主链路", "主要链路", "核心链路", "调用链", "主流程", "主要流程",
                "purpose", "project overview", "about this project", "architecture",
                "project structure", "what does this project")) {
            return true;
        }
        // 「这个项目在做什么」— but NOT 「KnowledgeService在做什么」
        if (containsAny(lower, "做什么", "干什么")
                && !lower.contains("分支") && !lower.contains("branch")
                && containsAny(lower, "项目", "仓库", "系统", "本仓库", "这个库", "当前项目")
                && !isSpecificTypeQuestion(lower)) {
            return true;
        }
        boolean aboutProject = containsAny(lower, "项目", "仓库", "系统", "本仓库", "这个库");
        return aboutProject && containsAny(lower, "目的", "结构", "架构", "链路", "流程", "介绍")
                && !isSpecificTypeQuestion(lower);
    }

    /** True when the user is asking about a concrete type like KnowledgeService / XxxController. */
    static boolean isSpecificTypeQuestion(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (TYPE_SUFFIX.matcher(text).find()) {
            return true;
        }
        String lower = text.toLowerCase();
        // Bare PascalCase only counts when the question is clearly about that type's API/source.
        return containsAnyStatic(lower, "方法", "类", "源码", "原代码", "签名")
                && TYPE_PASCAL.matcher(text).find();
    }

    private static final Pattern TYPE_SUFFIX = Pattern.compile(
            "(?i)\\b[a-z][a-z0-9]*(?:Service|Controller|Client|Utils|Store|Manager|Handler|Repository|Config)\\b");
    private static final Pattern TYPE_PASCAL = Pattern.compile(
            "\\b[A-Z][a-zA-Z0-9]{2,}(?:Service|Controller|Client|Utils|Store|Manager|Handler|Repository)?\\b");

    public record QueryResult(String intent, List<Map<String, Object>> contexts) {}
}
