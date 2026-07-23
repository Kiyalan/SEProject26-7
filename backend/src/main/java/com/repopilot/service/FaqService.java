package com.repopilot.service;

import com.repopilot.util.JsonUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class FaqService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final List<TopicSeed> SEEDS = List.of(
            new TopicSeed("overview", "这个项目是做什么的？",
                    "repository overview architecture modules purpose README"),
            new TopicSeed("getting_started", "如何本地启动或运行这个项目？",
                    "getting started install setup run start npm mvn docker compose"),
            new TopicSeed("api", "主要 API / 接口入口在哪里？",
                    "API endpoints controllers routes OpenAPI request response"),
            new TopicSeed("deployment", "项目如何部署或发布？",
                    "deployment docker compose production release CI CD"),
            new TopicSeed("architecture", "代码结构与模块划分是怎样的？",
                    "architecture modules packages layers frontend backend"),
            new TopicSeed("troubleshooting", "常见报错或排查入口有哪些？",
                    "error exception troubleshooting fail crash bug fix")
    );

    private final JdbcTemplate jdbc;
    private final KnowledgeService knowledgeService;
    private final LlmService llmService;

    public FaqService(JdbcTemplate jdbc, KnowledgeService knowledgeService, LlmService llmService) {
        this.jdbc = jdbc;
        this.knowledgeService = knowledgeService;
        this.llmService = llmService;
    }

    public Map<String, Object> list(String repoId) {
        List<Map<String, Object>> items = loadItems(repoId);
        String generatedAt = items.isEmpty() ? "" : Objects.toString(items.getFirst().get("updatedAt"), "");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repoId", repoId);
        result.put("status", items.isEmpty() ? "empty" : "ready");
        result.put("generatedAt", generatedAt);
        result.put("itemCount", items.size());
        result.put("items", items);
        result.put("message", items.isEmpty()
                ? "尚未生成 FAQ，请先构建知识库后点击生成"
                : "已从 GraphRAG 证据聚类生成 FAQ");
        return result;
    }

    @Transactional
    public Map<String, Object> generate(String repoId, String ownerLogin, int maxItems) {
        int limit = Math.min(Math.max(maxItems, 1), 24);
        List<Map<String, Object>> generated = new ArrayList<>();
        String now = LocalDateTime.now(ZoneOffset.UTC).format(TS);

        for (TopicSeed seed : SEEDS) {
            if (generated.size() >= limit) {
                break;
            }
            List<Map<String, Object>> evidence;
            try {
                evidence = knowledgeService.retrieveChunks(repoId, ownerLogin, seed.query(), null, 4);
            } catch (Exception ex) {
                throw new IllegalStateException("知识库未就绪，请先构建 GraphRAG 后再生成 FAQ", ex);
            }
            if (evidence.isEmpty()) {
                continue;
            }
            Map<String, Object> item = buildItem(repoId, seed, evidence, now);
            generated.add(item);
        }

        jdbc.update("DELETE FROM repo_faq_items WHERE repo_id = ?", repoId);
        for (Map<String, Object> item : generated) {
            jdbc.update("""
                    INSERT INTO repo_faq_items
                    (id, repo_id, category, question, answer, related_files, confidence, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    item.get("id"),
                    repoId,
                    item.get("category"),
                    item.get("question"),
                    item.get("answer"),
                    JsonUtils.toJson(item.get("relatedFiles")),
                    item.get("confidence"),
                    item.get("updatedAt"));
        }

        Map<String, Object> result = list(repoId);
        if (generated.isEmpty()) {
            result.put("status", "empty");
            result.put("message", "未能从 GraphRAG 检索到可用证据，请确认索引已构建");
        }
        return result;
    }

    public Map<String, Object> export(String repoId, String format) {
        List<Map<String, Object>> items = loadItems(repoId);
        String exportedAt = LocalDateTime.now(ZoneOffset.UTC).format(TS);
        String normalized = "json".equalsIgnoreCase(format) ? "json" : "markdown";
        String content = "json".equals(normalized)
                ? JsonUtils.toJson(Map.of("repoId", repoId, "exportedAt", exportedAt, "items", items))
                : toMarkdown(repoId, items, exportedAt);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repoId", repoId);
        result.put("format", normalized);
        result.put("content", content);
        result.put("itemCount", items.size());
        result.put("exportedAt", exportedAt);
        return result;
    }

    private Map<String, Object> buildItem(String repoId, TopicSeed seed,
                                          List<Map<String, Object>> evidence, String now) {
        List<Map<String, Object>> related = evidence.stream().limit(3)
                .map(row -> Map.<String, Object>of(
                        "file", Objects.toString(row.get("file"), ""),
                        "line", row.get("line") instanceof Number n ? n.intValue() : 1))
                .toList();
        String answer;
        if (llmService.configured()) {
            try {
                answer = llmService.generateAnswer(seed.question(), "what", evidence, seed.category());
            } catch (Exception ex) {
                answer = fallbackAnswer(seed, evidence);
            }
        } else {
            answer = fallbackAnswer(seed, evidence);
        }
        double confidence = Math.min(0.95, 0.55 + evidence.size() * 0.1);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        item.put("category", seed.category());
        item.put("question", seed.question());
        item.put("answer", answer);
        item.put("relatedFiles", related);
        item.put("confidence", confidence);
        item.put("updatedAt", now);
        return item;
    }

    private String fallbackAnswer(TopicSeed seed, List<Map<String, Object>> evidence) {
        StringBuilder sb = new StringBuilder();
        sb.append("根据知识库检索（").append(seed.category()).append("）整理：\n");
        for (int i = 0; i < Math.min(evidence.size(), 3); i++) {
            Map<String, Object> row = evidence.get(i);
            String file = Objects.toString(row.get("file"), "unknown");
            String content = Objects.toString(row.get("content"), "").trim();
            if (content.length() > 280) {
                content = content.substring(0, 280) + "…";
            }
            sb.append(i + 1).append(". `").append(file).append("` — ").append(content).append('\n');
        }
        return sb.toString().trim();
    }

    private List<Map<String, Object>> loadItems(String repoId) {
        return jdbc.query("""
                SELECT id, category, question, answer, related_files, confidence, updated_at
                FROM repo_faq_items
                WHERE repo_id = ?
                ORDER BY category, updated_at DESC
                """, (rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getString("id"));
            item.put("category", rs.getString("category"));
            item.put("question", rs.getString("question"));
            item.put("answer", rs.getString("answer"));
            item.put("relatedFiles", JsonUtils.parseMapList(rs.getString("related_files")));
            item.put("confidence", rs.getDouble("confidence"));
            item.put("updatedAt", rs.getString("updated_at"));
            return item;
        }, repoId);
    }

    private String toMarkdown(String repoId, List<Map<String, Object>> items, String exportedAt) {
        StringBuilder sb = new StringBuilder();
        sb.append("# FAQ — ").append(repoId).append('\n');
        sb.append("导出时间: ").append(exportedAt).append("\n\n");
        if (items.isEmpty()) {
            sb.append("_暂无 FAQ 条目_\n");
            return sb.toString();
        }
        for (Map<String, Object> item : items) {
            sb.append("## ").append(item.get("question")).append('\n');
            sb.append("类别: `").append(item.get("category")).append("` · 置信度 ")
                    .append(Math.round(((Number) item.get("confidence")).doubleValue() * 100))
                    .append("%\n\n");
            sb.append(item.get("answer")).append("\n\n");
        }
        return sb.toString();
    }

    private record TopicSeed(String category, String question, String query) {}
}
