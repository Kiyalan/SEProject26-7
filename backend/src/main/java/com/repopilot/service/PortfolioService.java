package com.repopilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.client.GitHubClient;
import com.repopilot.entity.RepoIndex;
import com.repopilot.repository.RepoIndexRepository;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class PortfolioService {

    private final GitHubClient github;
    private final RepoIndexRepository repoIndexRepository;

    public PortfolioService(GitHubClient github, RepoIndexRepository repoIndexRepository) {
        this.github = github;
        this.repoIndexRepository = repoIndexRepository;
    }
    public Map<String, Object> overview(String token, int maxRepos) {
        JsonNode repos = github.get("/user/repos", token, Map.of(
                "visibility", "all",
                "affiliation", "owner,collaborator,organization_member",
                "sort", "updated",
                "per_page", Math.min(maxRepos, 100),
                "page", 1
        ));
        if (!repos.isArray()) {
            throw new IllegalStateException("GitHub 返回格式异常");
        }

        Map<String, Map<String, Object>> local = localIndexMap();
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Integer> langCounter = new HashMap<>();
        int totalStars = 0;
        int totalOpenIssues = 0;
        int indexedCount = 0;
        int totalFiles = 0;
        int totalChunks = 0;

        for (JsonNode repo : repos) {
            if (items.size() >= maxRepos) {
                break;
            }
            String repoId = repo.path("id").asText();
            String primaryLang = repo.path("language").asText("Unknown");
            langCounter.merge(primaryLang, 1, Integer::sum);
            int stars = repo.path("stargazers_count").asInt(0);
            int openIssues = repo.path("open_issues_count").asInt(0);
            totalStars += stars;
            totalOpenIssues += openIssues;

            Map<String, Object> localInfo = local.getOrDefault(repoId, Map.of());
            if (Boolean.TRUE.equals(localInfo.get("indexed"))) {
                indexedCount++;
                totalFiles += ((Number) localInfo.getOrDefault("fileCount", 0)).intValue();
                totalChunks += ((Number) localInfo.getOrDefault("chunkCount", 0)).intValue();
            }

            String pushed = repo.path("pushed_at").asText("").substring(0, Math.min(10, repo.path("pushed_at").asText("").length()));
            Map<String, Object> knowledge = new LinkedHashMap<>();
            knowledge.put("indexed", localInfo.getOrDefault("indexed", false));
            knowledge.put("indexedAt", localInfo.getOrDefault("indexedAt", ""));
            knowledge.put("fileCount", localInfo.getOrDefault("fileCount", 0));
            knowledge.put("chunkCount", localInfo.getOrDefault("chunkCount", 0));
            knowledge.put("commitSnapshots", localInfo.getOrDefault("commitSnapshots", 0));

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("repoId", repoId);
            item.put("fullName", repo.path("full_name").asText());
            item.put("language", primaryLang);
            item.put("stars", stars);
            item.put("openIssues", openIssues);
            item.put("pushedAt", pushed);
            item.put("knowledge", knowledge);
            items.add(item);
        }

        items.sort(Comparator.comparing((Map<String, Object> i) -> (String) i.get("pushedAt")).reversed());
        int langTotalValue = langCounter.values().stream().mapToInt(Integer::intValue).sum();
        if (langTotalValue == 0) {
            langTotalValue = 1;
        }
        final int langTotal = langTotalValue;
        List<Map<String, Object>> languageBreakdown = new ArrayList<>();
        langCounter.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(12)
                .forEach(e -> languageBreakdown.add(Map.of(
                        "language", e.getKey(),
                        "count", e.getValue(),
                        "percent", Math.round(e.getValue() * 1000.0 / langTotal) / 10.0
                )));

        Map<String, List<String>> clusters = new LinkedHashMap<>();
        clusters.put("TypeScript/JavaScript", new ArrayList<>());
        clusters.put("Python", new ArrayList<>());
        clusters.put("Other", new ArrayList<>());
        for (Map<String, Object> item : items) {
            String lang = (String) item.get("language");
            String fullName = (String) item.get("fullName");
            if ("TypeScript".equals(lang) || "JavaScript".equals(lang)) {
                clusters.get("TypeScript/JavaScript").add(fullName);
            } else if ("Python".equals(lang)) {
                clusters.get("Python").add(fullName);
            } else {
                clusters.get("Other").add(fullName);
            }
        }
        clusters.entrySet().removeIf(e -> e.getValue().isEmpty());
        clusters.replaceAll((k, v) -> v.stream().limit(8).toList());

        return Map.of(
                "summary", Map.of(
                        "repoCount", items.size(),
                        "indexedCount", indexedCount,
                        "indexRate", items.isEmpty() ? 0 : Math.round(indexedCount * 1000.0 / items.size()) / 10.0,
                        "totalStars", totalStars,
                        "totalOpenIssues", totalOpenIssues,
                        "totalIndexedFiles", totalFiles,
                        "totalChunks", totalChunks
                ),
                "languageBreakdown", languageBreakdown,
                "clusters", clusters,
                "timeline", items.stream().limit(15)
                        .map(i -> Map.of(
                                "fullName", i.get("fullName"),
                                "pushedAt", i.get("pushedAt"),
                                "indexedAt", ((Map<?, ?>) i.get("knowledge")).get("indexedAt")
                        ))
                        .toList(),
                "repos", items,
                "notes", List.of(
                        "语言/Star/Issue 来自 GitHub API",
                        "索引状态来自本地知识库"
                )
        );
    }

    private Map<String, Map<String, Object>> localIndexMap() {
        Map<String, Map<String, Object>> result = new HashMap<>();
        for (RepoIndex row : repoIndexRepository.findAll()) {
            String repoId = row.getRepoId();
            boolean ready = "ready".equals(row.getStatus());
            result.put(repoId, Map.of(
                    "indexed", ready,
                    "indexedAt", Objects.toString(row.getIndexedAt(), ""),
                    "fileCount", row.getFileCount() == null ? 0 : row.getFileCount(),
                    "chunkCount", row.getChunkCount() == null ? 0 : row.getChunkCount(),
                    "commitSnapshots", ready ? 1 : 0
            ));
        }
        return result;
    }
}
