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

    private final KnowledgeService knowledgeService;

    public KnowledgeQueryService(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    public QueryResult retrieve(String repoId, String question, String ownerLogin) {
        String lower = question.toLowerCase();
        Set<String> intents = new LinkedHashSet<>();
        if (containsAny(lower, "commit", "提交", "版本历史", "提交历史", "弃用", "废弃", "采用", "revert", "回滚")) {
            intents.add("history");
        }
        if (containsAny(lower, "api", "接口", "endpoint", "openapi", "swagger")) {
            intents.add("api");
        }
        if (containsAny(lower, "部署", "布置", "启动", "安装", "环境变量", "服务器", "docker", "上线",
                "deploy", "deployment", "start", "install", "environment variable")) {
            intents.add("deployment");
        }
        if (containsAny(lower, "项目目的", "主要目的", "主要功能", "项目介绍", "做什么", "是什么项目",
                "purpose", "project overview")) {
            intents.add("overview");
        }
        if (intents.isEmpty()) {
            intents.add("code");
        }

        List<Map<String, Object>> contexts = new ArrayList<>();
        try {
            if (intents.contains("history")) {
                contexts.addAll(knowledgeService.commitHistoryContexts(repoId, ownerLogin, requestedCommitCount(question)));
            }
            if (intents.contains("overview")) {
                contexts.add(knowledgeService.repositoryOverviewContext(repoId, ownerLogin));
                contexts.addAll(knowledgeService.retrieveChunksByPathHints(
                        repoId, ownerLogin, question + " 项目说明 README", List.of("readme", "openapi", "package.json", "pom.xml"), 8));
            }
            if (intents.contains("api")) {
                contexts.addAll(knowledgeService.apiSpecificationContexts(repoId, ownerLogin, 100));
                if (contexts.stream().noneMatch(row -> "api_spec".equals(row.get("sourceType")))) {
                    contexts.addAll(knowledgeService.retrieveChunksByPathHints(
                            repoId, ownerLogin, question + " controller endpoint operationId", API_PATH_HINTS, 15));
                }
            }
            if (intents.contains("deployment")) {
                contexts.addAll(knowledgeService.retrieveChunksByPathHints(
                        repoId, ownerLogin, question + " 启动 配置 端口 环境变量", DEPLOY_PATH_HINTS, 15));
            }
            if (intents.contains("code") || contexts.isEmpty()) {
                contexts.addAll(knowledgeService.retrieveChunks(repoId, ownerLogin, question, null, 10));
            }
        } catch (Exception ex) {
            // Index may be missing or CodeWiki offline; Chat still returns a structured fallback.
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

        List<Map<String, Object>> deduplicated = deduplicateAndBound(contexts, intents.contains("history") ? 50 : 45);
        return new QueryResult(String.join("+", intents), deduplicated);
    }

    private List<Map<String, Object>> deduplicateAndBound(List<Map<String, Object>> rows, int maxItems) {
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        int totalChars = 0;
        for (Map<String, Object> row : rows) {
            String key = row.getOrDefault("file", "") + ":" + row.getOrDefault("line", 0)
                    + ":" + row.getOrDefault("symbolName", "");
            if (unique.containsKey(key)) {
                continue;
            }
            String content = String.valueOf(row.getOrDefault("content", ""));
            if (!unique.isEmpty() && totalChars + content.length() > 36_000) {
                break;
            }
            unique.put(key, row);
            totalChars += content.length();
            if (unique.size() >= maxItems) {
                break;
            }
        }
        return new ArrayList<>(unique.values());
    }

    private int requestedCommitCount(String question) {
        Matcher matcher = COMMIT_COUNT.matcher(question);
        if (!matcher.find()) {
            return 20;
        }
        return Math.min(Math.max(Integer.parseInt(matcher.group(1)), 1), 50);
    }

    private boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }

    public record QueryResult(String intent, List<Map<String, Object>> contexts) {}
}
