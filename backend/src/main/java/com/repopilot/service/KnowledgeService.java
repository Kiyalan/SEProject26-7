package com.repopilot.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.client.CodeWikiClient;
import com.repopilot.client.CodeWikiException;
import com.repopilot.entity.RepoIndex;
import com.repopilot.repository.support.KnowledgeStore;
import com.repopilot.util.JsonUtils;

@Service
public class KnowledgeService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final ConcurrentHashMap<String, String> wikiErrors = new ConcurrentHashMap<>();

    private final KnowledgeStore store;
    private final CodeWikiClient codeWiki;
    private final GitRepositoryService git;
    private final RepoAuthorizationService authorization;
    private final ProgressService progress;
    private final KnowledgeBuildTaskService tasks;
    private final ObjectMapper mapper;

    public KnowledgeService(KnowledgeStore store, CodeWikiClient codeWiki, GitRepositoryService git,
                            RepoAuthorizationService authorization, ProgressService progress,
                            KnowledgeBuildTaskService tasks, ObjectMapper mapper) {
        this.store = store;
        this.codeWiki = codeWiki;
        this.git = git;
        this.authorization = authorization;
        this.progress = progress;
        this.tasks = tasks;
        this.mapper = mapper;
    }

    public Map<String, Object> buildKnowledge(String repoId, String token, boolean ignoredIndexEachCommit,
                                              int maxCommits, List<String> ignoredCommitShas) {
        String taskId = tasks.create(repoId, "incremental");
        return buildKnowledge(repoId, token, false, maxCommits, null, taskId);
    }

    public Map<String, Object> buildKnowledge(String repoId, String token, boolean ignoredIndexEachCommit,
                                              int maxCommits, List<String> ignoredCommitShas, String taskId) {
        String progressKey = "knowledge:" + repoId;
        progress.start(progressKey, 5, "准备同步仓库");
        tasks.start(taskId);
        try {
            JsonNode repo = authorization.requireAccess(repoId, token);
            String fullName = required(repo, "full_name");
            String branch = repo.path("default_branch").asText("main");
            RepoIndex index = store.upsertIndex(repoId, fullName, branch, "indexing");
            store.upsertSettings(repoId, false, maxCommits, "");

            tasks.progress(taskId, 1, 5, "克隆或拉取默认分支");
            progress.step(progressKey, "同步 Git 默认分支");
            GitRepositoryService.SyncResult sync = git.sync(repoId, fullName, branch, token);
            tasks.setCommits(taskId, sync.oldHead(), sync.head());

            String codeWikiId = index.getCodeWikiRepoId() == null ? "" : index.getCodeWikiRepoId();
            boolean fullBuild = codeWikiId.isBlank();
            if (fullBuild) {
                tasks.progress(taskId, 2, 5, "向 CodeWiki 注册本地仓库");
                CodeWikiClient.RepoResponse registered = codeWiki.register(sync.codeWikiPath(), fullName);
                codeWikiId = registered == null ? "" : registered.resolvedId();
                if (codeWikiId.isBlank()) {
                    codeWikiId = findRegisteredRepo(fullName, sync.codeWikiPath());
                }
                if (codeWikiId.isBlank()) throw new IllegalStateException("CodeWiki 注册响应缺少仓库 id");
                index.setCodeWikiRepoId(codeWikiId);
                store.saveIndex(index);

                tasks.progress(taskId, 3, 5, "CodeWiki 正在分析源码");
                CodeWikiClient.RunResponse run = codeWiki.analyze(codeWikiId);
                if (run == null || run.resolvedId().isBlank()) {
                    throw new IllegalStateException("CodeWiki analyze 响应缺少 run_id");
                }
                codeWiki.awaitRun(codeWikiId, run.resolvedId());
                tasks.progress(taskId, 4, 5, "构建 GraphRAG");
                codeWiki.buildGraph(codeWikiId);
            } else {
                tasks.progress(taskId, 3, 5, "增量更新 CodeWiki");
                codeWiki.update(codeWikiId);
            }

            JsonNode graphStatus = codeWiki.graphStatus(codeWikiId);
            projectIndex(index, repo, sync.head(), graphStatus);
            store.upsertSettings(repoId, false, maxCommits, sync.head());
            tasks.projectCounts(taskId, graphStatus);
            progress.finish(progressKey, "知识库构建完成");
            Map<String, Object> quality = tasks.complete(taskId, repoId);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("taskId", taskId);
            response.put("repoId", repoId);
            response.put("fullName", fullName);
            response.put("indexedCommits", 1);
            response.put("commits", List.of(Map.of("commitSha", sync.head(),
                    "shortSha", shortSha(sync.head()), "message", "HEAD")));
            response.put("activeCommitSha", sync.head());
            response.put("deduplication", storageStats(repoId));
            response.put("quality", quality);
            response.put("status", "ready");
            response.put("mode", fullBuild ? "full" : "incremental");
            return response;
        } catch (Exception ex) {
            store.resetIndexingStatus(repoId);
            String message = rootMessage(ex);
            progress.fail(progressKey, message);
            if (ex instanceof CodeWikiException codeWikiError) {
                tasks.error(taskId, codeWikiError.operation(), "", "CODEWIKI_ERROR",
                        message, codeWikiError.retryable());
            }
            tasks.fail(taskId, repoId, message);
            throw ex instanceof IllegalStateException state ? state
                    : new IllegalStateException("构建知识库失败: " + message, ex);
        }
    }

    public Map<String, Object> getOverview(String repoId, String commitSha) {
        RepoIndex index = store.findIndex(repoId).orElse(null);
        if (index == null) return emptyOverview(repoId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repoId", repoId);
        result.put("fullName", safe(index.getFullName()));
        result.put("status", safe(index.getStatus(), "not_indexed"));
        result.put("indexedAt", safe(index.getIndexedAt()));
        result.put("fileCount", value(index.getFileCount()));
        result.put("chunkCount", value(index.getChunkCount()));
        result.put("summary", safe(index.getSummary()));
        result.put("moduleSummary", "");
        result.put("languages", JsonUtils.parseIntMap(index.getLanguages()));
        result.put("readmePath", safe(index.getReadmePath()));
        result.put("readmePreview", safe(index.getReadmePreview()));
        String sha = "";
        try {
            sha = resolveActiveCommitSha(repoId, commitSha);
        } catch (Exception ignored) {
            sha = safe(index.getActiveCommitSha());
        }
        result.put("commitSha", sha);
        result.put("shortSha", shortSha(sha));
        result.put("topics", store.topics(index));
        result.put("license", safe(index.getLicenseName()));

        List<Map<String, Object>> indexedFiles = List.of();
        List<Map<String, Object>> tree = List.of();
        try {
            JsonNode fileView = loadFiles(index);
            tree = treeNodes(fileView.path("root").path("children"));
            if (tree.size() > 200) {
                tree = tree.subList(0, 200);
            }
            indexedFiles = indexedFiles(fileView.path("files"));
            if (indexedFiles.size() > 400) {
                indexedFiles = indexedFiles.subList(0, 400);
            }
        } catch (Exception ignored) {
            // CodeWiki /files 过大或中断时，仍返回索引元数据，避免整页 EOF。
        }
        result.put("tree", tree);
        result.put("indexedFiles", indexedFiles);
        result.put("modules", modules(indexedFiles));
        result.put("dependencies", List.of());
        try {
            result.put("commits", listIndexedCommits(repoId));
        } catch (Exception ignored) {
            result.put("commits", List.of());
        }
        result.put("settings", getSettings(repoId));
        result.put("deduplication", storageStats(repoId));
        result.put("provider", "codewiki");
        result.put("graphStatus", graphStatusView(index));
        result.put("wikiStatus", wikiStatus(repoId, "zh"));
        result.put("quality", Map.of("status", safe(index.getQualityStatus(), "unknown"),
                "score", index.getQualityScore() == null ? 0 : index.getQualityScore(),
                "report", JsonUtils.parseObject(index.getQualityReport()),
                "lastTaskId", safe(index.getLastTaskId())));
        result.put("storageModel", Map.of(
                "displayed", List.of("tree", "modules", "summary", "languages", "graphStatus"),
                "databaseOnly", List.of("chunk_blobs", "embeddings"),
                "dedupStrategy", "codewiki-content-addressed"
        ));
        return result;
    }

    public List<Map<String, Object>> listIndexedCommits(String repoId) {
        try {
            return git.history(repoId, 50).stream().map(row -> {
                String sha = String.valueOf(row.get("symbolName"));
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("commitSha", sha);
                item.put("shortSha", shortSha(sha));
                item.put("parentSha", "");
                item.put("message", extractLine(String.valueOf(row.get("content")), "message: "));
                item.put("author", extractLine(String.valueOf(row.get("content")), "author: "));
                item.put("committedAt", extractLine(String.valueOf(row.get("content")), "time: "));
                item.put("indexedAt", "");
                item.put("fileCount", 0);
                item.put("chunkCount", 0);
                item.put("status", "git");
                return item;
            }).toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    public List<Map<String, Object>> commitHistoryContexts(String repoId, int limit) {
        return git.history(repoId, limit);
    }

    public Map<String, Object> repositoryOverviewContext(String repoId) {
        List<Map<String, Object>> evidence = retrieveChunks(repoId,
                "repository overview architecture modules purpose README", null, 8);
        if (!evidence.isEmpty()) return evidence.getFirst();
        Map<String, Object> overview = getOverview(repoId, null);
        return evidence("knowledge/repository-overview", "repository_overview",
                "仓库: " + overview.getOrDefault("fullName", "") + "\n摘要: " + overview.getOrDefault("summary", ""));
    }

    public Map<String, Object> compareCommits(String repoId, String baseSha, String headSha) {
        return git.compare(repoId, baseSha, headSha);
    }

    public Map<String, Object> getSettings(String repoId) {
        Map<String, Object> current = new LinkedHashMap<>(store.settingsView(repoId));
        current.put("indexEachCommit", false);
        return current;
    }

    public Map<String, Object> saveSettings(String repoId, Boolean ignoredIndexEachCommit,
                                            Integer maxCommits, String ignoredActiveCommitSha) {
        Map<String, Object> current = getSettings(repoId);
        int max = maxCommits == null ? (Integer) current.get("maxCommits") : maxCommits;
        String head = resolveActiveCommitSha(repoId, null);
        store.upsertSettings(repoId, false, max, head);
        return getSettings(repoId);
    }

    public List<Map<String, Object>> retrieveChunks(String repoId, String question, String ignoredCommitSha, int limit) {
        RepoIndex index = requireReadyIndex(repoId);
        JsonNode response = codeWiki.retrieve(index.getCodeWikiRepoId(), question, 2);
        return evidenceRows(response, limit, "code");
    }

    public List<Map<String, Object>> retrieveChunksByPathHints(
            String repoId, String question, List<String> pathHints, int limit) {
        return retrieveChunks(repoId, question + "\nRelevant paths: " + String.join(", ", pathHints), null, limit);
    }

    public List<Map<String, Object>> apiSpecificationContexts(String repoId, int limit) {
        return retrieveChunks(repoId, "API endpoints controllers routes OpenAPI Swagger request response", null, limit)
                .stream().map(row -> {
                    Map<String, Object> copy = new LinkedHashMap<>(row);
                    copy.put("sourceType", "api_spec");
                    return copy;
                }).toList();
    }

    public String resolveActiveCommitSha(String repoId, String ignoredCommitSha) {
        return store.findIndex(repoId).map(RepoIndex::getActiveCommitSha).orElse("");
    }

    public Map<String, Object> storageStats(String repoId) {
        RepoIndex index = store.findIndex(repoId).orElse(null);
        int chunks = index == null ? 0 : value(index.getChunkCount());
        return Map.of("indexedCommits", index == null ? 0 : 1, "uniqueFileBlobs", 0,
                "uniqueChunkBlobs", chunks, "totalBlobBytes", 0, "fileReferences",
                index == null ? 0 : value(index.getFileCount()));
    }

    public Map<String, Object> graphStatus(String repoId) {
        return store.findIndex(repoId)
                .map(this::graphStatusView)
                .orElse(Map.of("status", "not_indexed", "provider", "codewiki",
                        "nodeCount", 0, "edgeCount", 0, "communityCount", 0, "chunkCount", 0));
    }

    public Map<String, Object> graphSearch(String repoId, JsonNode parameters) {
        JsonNode response = codeWiki.graphSearch(codeWikiId(repoId), parameters);
        List<Map<String, Object>> items = new ArrayList<>();
        response.path("results").forEach(hit -> items.add(graphNode(hit.path("node"), hit.path("score").asDouble(0))));
        return Map.of(
                "query", response.path("query").asText(parameters.path("query").asText("")),
                "items", items,
                "total", items.size()
        );
    }

    public Map<String, Object> callers(String repoId, JsonNode parameters) {
        return relationshipTraversal(codeWiki.callers(codeWikiId(repoId), parameters));
    }

    public Map<String, Object> callees(String repoId, JsonNode parameters) {
        return relationshipTraversal(codeWiki.callees(codeWikiId(repoId), parameters));
    }

    public Map<String, Object> impact(String repoId, JsonNode parameters) {
        JsonNode response = codeWiki.impact(codeWikiId(repoId), parameters);
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        response.path("nodes").forEach(node -> nodes.add(graphNode(node, null)));
        response.path("edges").forEach(edge -> edges.add(graphEdge(edge)));
        return Map.of("nodes", nodes, "edges", edges, "truncated", false);
    }

    public Map<String, Object> explore(String repoId, JsonNode body) {
        Map<String, Object> request = Map.of(
                "query", body.path("query").asText("repository overview"),
                "max_files", bounded(body.path("maxFiles").asInt(12), 1, 100),
                "max_nodes", bounded(body.path("maxNodes").asInt(160), 1, 500)
        );
        JsonNode response = codeWiki.explore(codeWikiId(repoId), mapper.valueToTree(request));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repoId", response.path("repo_id").asText(repoId));
        result.put("query", response.path("query").asText(""));
        result.put("entryPoints", jsonValue(response.path("entry_points")));
        result.put("relationships", jsonValue(response.path("relationships")));
        result.put("sourceSections", jsonValue(response.path("source_sections")));
        result.put("additionalFiles", jsonValue(response.path("additional_files")));
        result.put("text", response.path("text").asText(""));
        result.put("stats", jsonValue(response.path("stats")));
        return result;
    }

    public Map<String, Object> affected(String repoId, JsonNode body) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("file_paths", jsonValue(body.path("filePaths")));
        request.put("depth", bounded(body.path("depth").asInt(5), 1, 10));
        if (body.hasNonNull("testGlob")) request.put("test_glob", body.path("testGlob").asText());
        JsonNode response = codeWiki.affected(codeWikiId(repoId), mapper.valueToTree(request));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repoId", response.path("repo_id").asText(repoId));
        result.put("changedFiles", jsonValue(response.path("changed_files")));
        result.put("affectedFiles", jsonValue(response.path("affected_files")));
        result.put("affectedTests", jsonValue(response.path("affected_tests")));
        result.put("affectedWikiPages", jsonValue(response.path("affected_wiki_pages")));
        result.put("affectedNodeIds", jsonValue(response.path("affected_node_ids")));
        result.put("traversedFileCount", response.path("traversed_file_count").asInt(0));
        return result;
    }

    public JsonNode generateWiki(String repoId, JsonNode body) { return codeWiki.generateWiki(codeWikiId(repoId), body); }

    public void setWikiError(String repoId, String error) { wikiErrors.put(repoId, error); }
    public void clearWikiError(String repoId) { wikiErrors.remove(repoId); }
    public String getWikiError(String repoId) { return wikiErrors.getOrDefault(repoId, ""); }
    public Map<String, Object> readWiki(String repoId, String language) {
        JsonNode response = codeWiki.readWiki(codeWikiId(repoId), language);
        List<Map<String, Object>> pages = new ArrayList<>();
        int order = 0;
        for (JsonNode page : response.path("pages")) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("id", firstText(page, "id", "slug"));
            mapped.put("title", page.path("title").asText(page.path("slug").asText("Untitled")));
            mapped.put("path", page.path("slug").asText(""));
            mapped.put("content", page.path("markdown").asText(""));
            mapped.put("order", order++);
            pages.add(mapped);
        }
        String status = pages.isEmpty() ? "not_generated" : "ready";
        String error = getWikiError(repoId);
        if (!error.isBlank()) {
            status = "failed";
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("provider", "codewiki");
        result.put("language", language);
        result.put("pages", pages);
        if (!error.isBlank()) result.put("error", error);
        return result;
    }

    private String codeWikiId(String repoId) {
        return requireReadyIndex(repoId).getCodeWikiRepoId();
    }

    private RepoIndex requireReadyIndex(String repoId) {
        RepoIndex index = store.findIndex(repoId)
                .orElseThrow(() -> new IllegalStateException("仓库尚未构建知识库"));
        if (index.getCodeWikiRepoId() == null || index.getCodeWikiRepoId().isBlank()) {
            throw new IllegalStateException("仓库尚未在 CodeWiki 注册");
        }
        return index;
    }

    private String findRegisteredRepo(String fullName, String path) {
        JsonNode repos = codeWiki.listRepos();
        JsonNode rows = repos != null && repos.isArray() ? repos : repos == null ? null : repos.path("items");
        if (rows != null && rows.isArray()) {
            for (JsonNode row : rows) {
                if (fullName.equals(row.path("name").asText()) || path.equals(row.path("path").asText())) {
                    return row.path("id").asText(row.path("repo_id").asText(""));
                }
            }
        }
        return "";
    }

    private void projectIndex(RepoIndex index, JsonNode repo, String head, JsonNode status) {
        index.setStatus("ready");
        index.setIndexedAt(LocalDateTime.now(ZoneOffset.UTC).format(TS));
        index.setActiveCommitSha(head);
        index.setCommitSha(shortSha(head));
        index.setFileCount(firstInt(status, "file_count", "files", "documents"));
        index.setChunkCount(firstInt(status, "chunk_count", "chunks", "nodes"));
        index.setGraphNodeCount(firstInt(status, "node_count", "nodes"));
        index.setGraphEdgeCount(firstInt(status, "edge_count", "edges"));
        index.setGraphCommunityCount(firstInt(status, "community_count", "communities"));
        if (status != null && status.path("languages").isObject()) {
            index.setLanguages(status.path("languages").toString());
        }
        index.setSummary(status == null ? "" : status.path("summary").asText(""));
        List<String> topics = new ArrayList<>();
        repo.path("topics").forEach(topic -> topics.add(topic.asText()));
        index.setTopics(JsonUtils.toJson(topics));
        JsonNode license = repo.path("license");
        index.setLicenseName(license.path("spdx_id").asText(license.path("name").asText("")));
        store.saveIndex(index);
    }

    private List<Map<String, Object>> evidenceRows(JsonNode response, int limit, String defaultSource) {
        JsonNode rows = response;
        if (response != null && !response.isArray()) {
            for (String field : List.of("source_chunks", "results", "items", "contexts", "evidence", "chunks")) {
                if (response.path(field).isArray()) {
                    rows = response.path(field);
                    break;
                }
            }
        }
        if (rows == null || !rows.isArray()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("file", firstText(row, "file", "path", "file_path", "source"));
            item.put("line", firstInt(row, "line", "start_line"));
            item.put("endLine", firstInt(row, "end_line", "line"));
            item.put("symbolName", firstText(row, "symbol_name", "name", "title"));
            item.put("symbolKind", firstText(row, "symbol_kind", "type", "kind"));
            item.put("score", row.path("score").asDouble(0));
            String reasons = row.path("reasons").toString().toLowerCase();
            item.put("retrievalType", reasons.contains("vector") ? "vector"
                    : reasons.contains("fts") ? "fts" : "graph");
            item.put("sourceType", defaultSource);
            item.put("content", firstText(row, "content", "text", "snippet", "description"));
            result.add(item);
            if (result.size() >= Math.min(Math.max(limit, 1), 200)) break;
        }
        return result;
    }

    private Map<String, Object> graphStatusView(RepoIndex index) {
        return Map.of(
                "status", "ready".equals(index.getStatus()) ? "ready" : "not_indexed",
                "provider", "codewiki",
                "nodeCount", value(index.getGraphNodeCount()),
                "edgeCount", value(index.getGraphEdgeCount()),
                "communityCount", value(index.getGraphCommunityCount()),
                "chunkCount", value(index.getChunkCount())
        );
    }

    private String wikiStatus(String repoId, String language) {
        try {
            String error = getWikiError(repoId);
            if (!error.isBlank()) return "failed";
            Map<String, Object> wiki = readWiki(repoId, language);
            Object status = wiki.get("status");
            return status == null ? "not_generated" : String.valueOf(status);
        } catch (Exception ignored) {
            return "not_generated";
        }
    }

    private JsonNode loadFiles(RepoIndex index) {
        if (index.getCodeWikiRepoId() == null || index.getCodeWikiRepoId().isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode response = codeWiki.files(index.getCodeWikiRepoId());
            return response == null ? mapper.createObjectNode() : response;
        } catch (Exception ignored) {
            return mapper.createObjectNode();
        }
    }

    private List<Map<String, Object>> treeNodes(JsonNode rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode row : rows) {
            String path = row.path("path").asText(row.path("name").asText(""));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", path);
            item.put("title", row.path("name").asText(path));
            item.put("type", "directory".equals(row.path("type").asText()) ? "folder" : "file");
            if (row.path("children").isArray() && !row.path("children").isEmpty()) {
                item.put("children", treeNodes(row.path("children")));
            }
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> indexedFiles(JsonNode rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!rows.isArray()) return result;
        for (JsonNode row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", row.path("path").asText(""));
            item.put("size", row.path("size_bytes").asInt(0));
            item.put("language", row.path("language").asText("Other"));
            item.put("summary", "");
            item.put("astSymbols", List.of());
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> modules(List<Map<String, Object>> files) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> file : files) {
            String path = String.valueOf(file.getOrDefault("path", ""));
            String top = path.contains("/") ? path.substring(0, path.indexOf('/')) : "(root)";
            counts.merge(top, 1, Integer::sum);
        }
        return counts.entrySet().stream().limit(20).map(entry -> Map.<String, Object>of(
                "name", entry.getKey(),
                "desc", "CodeWiki 扫描到 " + entry.getValue() + " 个文件",
                "files", entry.getValue(),
                "deps", List.of()
        )).toList();
    }

    private Map<String, Object> relationshipTraversal(JsonNode response) {
        Map<String, Map<String, Object>> uniqueNodes = new LinkedHashMap<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        for (JsonNode relationship : response.path("relationships")) {
            Map<String, Object> source = graphNode(relationship.path("source"), null);
            Map<String, Object> target = graphNode(relationship.path("target"), null);
            uniqueNodes.put(String.valueOf(source.get("id")), source);
            uniqueNodes.put(String.valueOf(target.get("id")), target);
            edges.add(graphEdge(relationship.path("edge")));
        }
        return Map.of("nodes", new ArrayList<>(uniqueNodes.values()), "edges", edges, "truncated", false);
    }

    private Map<String, Object> graphNode(JsonNode node, Double score) {
        Map<String, Object> mapped = new LinkedHashMap<>();
        mapped.put("id", node.path("id").asText("unknown"));
        mapped.put("name", node.path("name").asText(node.path("id").asText("unknown")));
        mapped.put("type", publicNodeType(node.path("type").asText("file")));
        putIfText(mapped, "qualifiedName", node, "symbol_id");
        putIfText(mapped, "path", node, "file_path");
        putIfText(mapped, "language", node, "language");
        if (node.path("start_line").asInt(0) > 0) mapped.put("line", node.path("start_line").asInt());
        if (node.path("end_line").asInt(0) > 0) mapped.put("endLine", node.path("end_line").asInt());
        if (score != null) mapped.put("score", Math.max(0, Math.min(1, score)));
        return mapped;
    }

    private Map<String, Object> graphEdge(JsonNode edge) {
        Map<String, Object> mapped = new LinkedHashMap<>();
        mapped.put("source", firstText(edge, "source", "source_id"));
        mapped.put("target", firstText(edge, "target", "target_id"));
        mapped.put("type", publicEdgeType(edge.path("type").asText("related_to")));
        mapped.put("weight", Math.max(0, Math.min(1,
                edge.has("weight") ? edge.path("weight").asDouble(1) : edge.path("confidence").asDouble(1))));
        return mapped;
    }

    private Object jsonValue(JsonNode node) {
        return mapper.convertValue(node, Object.class);
    }

    private static void putIfText(Map<String, Object> target, String targetName,
                                  JsonNode source, String sourceName) {
        String value = source.path(sourceName).asText("");
        if (!value.isBlank()) target.put(targetName, value);
    }

    private static String publicNodeType(String type) {
        return switch (type) {
            case "repository", "module", "file", "class", "interface", "function", "method",
                    "variable", "community", "chunk" -> type;
            case "config" -> "file";
            case "enum", "record", "struct", "trait" -> "class";
            case "constructor", "endpoint", "route" -> "method";
            default -> "variable";
        };
    }

    private static String publicEdgeType(String type) {
        return switch (type) {
            case "contains", "imports", "calls", "inherits", "implements", "references",
                    "depends_on", "member_of", "related_to" -> type;
            case "defines" -> "contains";
            default -> "related_to";
        };
    }

    private static int bounded(int value, int minimum, int maximum) {
        return Math.min(Math.max(value, minimum), maximum);
    }

    private Map<String, Object> emptyOverview(String repoId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repoId", repoId); result.put("status", "not_indexed");
        result.put("tree", List.of()); result.put("modules", List.of());
        result.put("dependencies", List.of()); result.put("fileCount", 0);
        result.put("chunkCount", 0); result.put("summary", "");
        result.put("languages", Map.of()); result.put("indexedFiles", List.of());
        result.put("commits", List.of()); result.put("settings", getSettings(repoId));
        result.put("deduplication", storageStats(repoId));
        result.put("quality", Map.of("status", "unknown", "score", 0, "report", Map.of(), "lastTaskId", ""));
        result.put("provider", "codewiki");
        result.put("graphStatus", Map.of("status", "not_indexed", "provider", "codewiki",
                "nodeCount", 0, "edgeCount", 0, "communityCount", 0, "chunkCount", 0));
        result.put("wikiStatus", "not_generated");
        result.put("storageModel", Map.of(
                "displayed", List.of("tree", "modules", "summary", "languages", "graphStatus"),
                "databaseOnly", List.of("chunk_blobs", "embeddings"),
                "dedupStrategy", "codewiki-content-addressed"
        ));
        return result;
    }

    private static Map<String, Object> evidence(String file, String type, String content) {
        return Map.of("file", file, "line", 1, "endLine", 1, "symbolName", file,
                "symbolKind", "repository", "score", 1, "retrievalType", "structured",
                "sourceType", type, "content", content);
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) throw new IllegalStateException("GitHub 仓库响应缺少 " + field);
        return value;
    }

    private static String firstText(JsonNode node, String... fields) {
        if (node == null) return "";
        for (String field : fields) {
            String value = node.path(field).asText("");
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static int firstInt(JsonNode node, String... fields) {
        if (node == null) return 0;
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isInt() || value.isLong()) return value.asInt();
            if (value.isArray()) return value.size();
        }
        return 0;
    }

    private static int value(Integer value) { return value == null ? 0 : value; }
    private static String shortSha(String sha) {
        return sha == null ? "" : sha.substring(0, Math.min(12, sha.length()));
    }
    private static String safe(String value) { return safe(value, ""); }
    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
    private static String extractLine(String text, String prefix) {
        for (String line : text.split("\\R")) if (line.startsWith(prefix)) return line.substring(prefix.length());
        return "";
    }
    private static String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
