package com.repopilot.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbc;
    private final RepoAuthorizationService authorization;
    private final ProgressService progress;
    private final KnowledgeBuildTaskService tasks;
    private final ObjectMapper mapper;

    public KnowledgeService(KnowledgeStore store, CodeWikiClient codeWiki, GitRepositoryService git,
                            JdbcTemplate jdbc, RepoAuthorizationService authorization, ProgressService progress,
                            KnowledgeBuildTaskService tasks, ObjectMapper mapper) {
        this.store = store;
        this.codeWiki = codeWiki;
        this.git = git;
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.progress = progress;
        this.tasks = tasks;
        this.mapper = mapper;
    }

    public Map<String, Object> buildKnowledge(String repoId, String ownerLogin, String token, boolean ignoredIndexEachCommit,
                                              int maxCommits, List<String> ignoredCommitShas) {
        String taskId = tasks.create(repoId, "incremental");
        return buildKnowledge(repoId, ownerLogin, token, false, maxCommits, null, taskId);
    }

    public Map<String, Object> buildKnowledge(String repoId, String ownerLogin, String token, boolean ignoredIndexEachCommit,
                                              int maxCommits, List<String> ignoredCommitShas, String taskId) {
        String progressKey = "knowledge:" + repoId;
        final int TOTAL = 20;
        progress.start(progressKey, TOTAL, "准备同步仓库");
        progress.setStage(progressKey, "preparing");
        tasks.start(taskId);
        try {
            // ── 阶段1: 准备 (0→2) ──
            setTaskAndProgress(taskId, progressKey, 1, TOTAL, "preparing", "验证仓库访问权限");
            JsonNode repo = authorization.requireAccess(repoId, token);
            String fullName = required(repo, "full_name");
            String branch = repo.path("default_branch").asText("main");

            setTaskAndProgress(taskId, progressKey, 2, TOTAL, "preparing", "初始化索引记录");
            RepoIndex index = store.upsertIndex(repoId, fullName, branch, "indexing", ownerLogin);
            store.upsertSettings(repoId, false, maxCommits, "");

            // ── 阶段2: Git 同步 (2→4) ──
            setTaskAndProgress(taskId, progressKey, 3, TOTAL, "git_sync", "正在准备 Git 仓库同步");
            GitRepositoryService.SyncResult sync = git.sync(repoId, fullName, ownerLogin, token, branch,
                    msg -> {
                        progress.setDone(progressKey, 3, msg);
                        progress.setStage(progressKey, "git_sync");
                    });
            tasks.setCommits(taskId, sync.oldHead(), sync.head());

            setTaskAndProgress(taskId, progressKey, 4, TOTAL, "git_sync", "Git 仓库同步完成");

            String codeWikiId = index.getCodeWikiRepoId() == null ? "" : index.getCodeWikiRepoId().trim();
            if (!codeWikiId.isBlank() && !codeWikiRepoAlive(codeWikiId)) {
                String remapped = findRegisteredRepo(fullName, sync.codeWikiPath());
                if (remapped.isBlank()) {
                    remapped = findRegisteredRepoByLocalRepoId(repoId);
                }
                if (!remapped.isBlank()) {
                    codeWikiId = remapped;
                    index.setCodeWikiRepoId(codeWikiId);
                    store.saveIndex(index);
                } else {
                    codeWikiId = "";
                    index.setCodeWikiRepoId("");
                    store.saveIndex(index);
                }
            }

            boolean needRegister = codeWikiId.isBlank();
            boolean needAnalyze = needRegister || !codeWikiGraphHasData(codeWikiId);
            // Zombie running analyze blocks new analyze forever after CodeWiki restarts
            if (!needRegister && codeWiki.hasZombieRunningRun(codeWikiId)) {
                setTaskAndProgress(taskId, progressKey, 5, TOTAL, "register",
                        "检测到 CodeWiki 僵尸分析任务，正在重置仓库注册");
                try {
                    codeWiki.deleteRepo(codeWikiId);
                } catch (Exception ignored) {
                    // delete best-effort
                }
                codeWikiId = "";
                index.setCodeWikiRepoId("");
                store.saveIndex(index);
                needRegister = true;
                needAnalyze = true;
            }
            boolean fullBuild = needAnalyze;

            if (needRegister) {
                setTaskAndProgress(taskId, progressKey, 5, TOTAL, "register", "向 CodeWiki 注册本地仓库");
                CodeWikiClient.RepoResponse registered = codeWiki.register(sync.codeWikiPath(), fullName);
                codeWikiId = registered == null ? "" : registered.resolvedId();
                if (codeWikiId.isBlank()) {
                    codeWikiId = findRegisteredRepo(fullName, sync.codeWikiPath());
                }
                if (codeWikiId.isBlank()) {
                    codeWikiId = findRegisteredRepoByLocalRepoId(repoId);
                }
                if (codeWikiId.isBlank()) throw new IllegalStateException("CodeWiki 注册响应缺少仓库 id");
                index.setCodeWikiRepoId(codeWikiId);
                store.saveIndex(index);
            }

            if (needAnalyze) {
                runFullAnalyzeAndGraph(taskId, progressKey, TOTAL, codeWikiId, needRegister);
            } else {
                setTaskAndProgress(taskId, progressKey, 6, TOTAL, "update", "正在增量更新 CodeWiki 索引");
                try {
                    codeWiki.update(codeWikiId);
                    setTaskAndProgress(taskId, progressKey, 12, TOTAL, "update", "增量更新完成");
                    setTaskAndProgress(taskId, progressKey, 15, TOTAL, "graphrag",
                            "增量后刷新实体 Embedding（标准 GraphRAG）");
                    try {
                        codeWiki.buildStandardGraph(codeWikiId);
                        setTaskAndProgress(taskId, progressKey, 17, TOTAL, "graphrag", "实体向量刷新完成");
                    } catch (Exception embedEx) {
                        setTaskAndProgress(taskId, progressKey, 17, TOTAL, "graphrag",
                                "实体向量刷新跳过: " + rootMessage(embedEx));
                    }
                } catch (CodeWikiException ex) {
                    if (!isMissingCodeWikiRepo(ex) && !ex.retryable()) {
                        throw ex;
                    }
                    setTaskAndProgress(taskId, progressKey, 5, TOTAL, "register",
                            "增量更新失败，改为全量重建");
                    try {
                        codeWiki.deleteRepo(codeWikiId);
                    } catch (Exception ignored) {
                    }
                    CodeWikiClient.RepoResponse registered = codeWiki.register(sync.codeWikiPath(), fullName);
                    codeWikiId = registered == null ? "" : registered.resolvedId();
                    if (codeWikiId.isBlank()) {
                        codeWikiId = findRegisteredRepo(fullName, sync.codeWikiPath());
                    }
                    if (codeWikiId.isBlank()) {
                        codeWikiId = findRegisteredRepoByLocalRepoId(repoId);
                    }
                    if (codeWikiId.isBlank()) throw new IllegalStateException("CodeWiki 注册响应缺少仓库 id");
                    index.setCodeWikiRepoId(codeWikiId);
                    store.saveIndex(index);
                    runFullAnalyzeAndGraph(taskId, progressKey, TOTAL, codeWikiId, true);
                    fullBuild = true;
                }
            }

            // Graph nodes without chunks ⇒ Q&A retrieve has nothing usable. Ensure GraphRAG build.
            ensureGraphChunks(taskId, progressKey, TOTAL, codeWikiId);

            // ── 阶段7: 索引项目元数据 (17→19) ──
            setTaskAndProgress(taskId, progressKey, 18, TOTAL, "indexing", "正在索引项目元数据");
            JsonNode graphStatus = codeWiki.graphStatus(codeWikiId);
            projectIndex(index, repo, sync.head(), graphStatus);
            store.upsertSettings(repoId, false, maxCommits, sync.head());
            tasks.projectCounts(taskId, graphStatus);

            // ── 阶段8: 质量评分 (19→20) ──
            setTaskAndProgress(taskId, progressKey, 19, TOTAL, "quality", "正在计算构建质量评分");
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

    public Map<String, Object> getOverview(String repoId, String ownerLogin, String commitSha) {
        RepoIndex index = store.findIndex(repoId).orElse(null);
        if (index == null) return emptyOverview(repoId);
        // Heal stale H2 counters from live CodeWiki (common after analyze-only / skipped GraphRAG).
        refreshIndexCountsFromCodeWiki(index);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repoId", repoId);
        result.put("fullName", safe(index.getFullName()));
        result.put("status", safe(index.getStatus(), "not_indexed"));
        result.put("indexedAt", safe(index.getIndexedAt()));
        result.put("fileCount", value(index.getFileCount()));
        result.put("chunkCount", value(index.getChunkCount()));
        Map<String, Object> lineStats = Map.of();
        try {
            lineStats = RepoLineCountService.count(git.hostPath(repoId, ownerLogin(repoId)));
        } catch (Exception ignored) {
            lineStats = Map.of("lineCount", 0, "sourceFileCount", 0, "lineCountByLanguage", Map.of());
        }
        result.put("lineCount", lineStats.getOrDefault("lineCount", 0));
        result.put("sourceFileCount", lineStats.getOrDefault("sourceFileCount", 0));
        result.put("lineCountByLanguage", lineStats.getOrDefault("lineCountByLanguage", Map.of()));
        result.put("lineCountNote", lineStats.getOrDefault("lineCountNote", ""));
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
        result.put("wikiStatus", wikiStatus(repoId, ownerLogin, "zh"));
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
            return git.history(repoId, ownerLogin(repoId), 50).stream().map(row -> {
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

    public List<Map<String, Object>> commitHistoryContexts(String repoId, String ownerLogin, int limit) {
        return git.history(repoId, ownerLogin, limit);
    }

    public List<Map<String, Object>> branchContexts(String repoId, String ownerLogin) {
        RepoIndex index = store.findIndex(repoId).orElse(null);
        String defaultBranch = index == null ? "main" : safe(index.getDefaultBranch(), "main");
        return git.listBranches(repoId, ownerLogin, defaultBranch);
    }

    public Map<String, Object> repositoryOverviewContext(String repoId, String ownerLogin) {
        List<Map<String, Object>> evidence = retrieveChunks(repoId, ownerLogin,
                "repository overview architecture modules purpose README", null, 8);
        if (!evidence.isEmpty()) return evidence.getFirst();
        Map<String, Object> overview = getOverview(repoId, ownerLogin, null);
        return evidence("knowledge/repository-overview", "repository_overview",
                "仓库: " + overview.getOrDefault("fullName", "")
                        + "\n摘要: " + overview.getOrDefault("summary", "")
                        + "\nfileCount: " + overview.getOrDefault("fileCount", 0)
                        + "\nchunkCount: " + overview.getOrDefault("chunkCount", 0)
                        + "\nlineCount: " + overview.getOrDefault("lineCount", 0)
                        + "\nsourceFileCount: " + overview.getOrDefault("sourceFileCount", 0)
                        + "\nlineCountNote: " + overview.getOrDefault("lineCountNote", ""));
    }

    /**
     * Explicit knowledge-base readiness for chat: graph communities/nodes ARE the build result.
     * Prevents the LLM from claiming "未构建" when only structure text is present.
     */
    public Map<String, Object> knowledgeStatusContext(String repoId, String ownerLogin) {
        Map<String, Object> overview = getOverview(repoId, ownerLogin, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> graph = overview.get("graphStatus") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();
        String status = String.valueOf(overview.getOrDefault("status", "not_indexed"));
        int files = numberOrZero(overview.get("fileCount"));
        int chunks = numberOrZero(overview.get("chunkCount"));
        int nodes = numberOrZero(graph.get("nodeCount"));
        int edges = numberOrZero(graph.get("edgeCount"));
        int communities = numberOrZero(graph.get("communityCount"));
        boolean built = "ready".equals(status) && (nodes > 0 || chunks > 0 || files > 0);
        String verdict = built
                ? "knowledgeBuilt=true。知识库已构建完成。图节点/社区/边即 GraphRAG 构建结果，不是「未构建」。"
                : "knowledgeBuilt=false。知识库尚未就绪（status=" + status
                + "，nodes=" + nodes + "，chunks=" + chunks + "）。请先在知识库页点击构建。";
        String content = verdict
                + "\nrepo=" + overview.getOrDefault("fullName", repoId)
                + "\nstatus=" + status
                + "\nindexedAt=" + overview.getOrDefault("indexedAt", "")
                + "\nfileCount=" + files
                + "\nchunkCount=" + chunks
                + "\ngraphNodeCount=" + nodes
                + "\ngraphEdgeCount=" + edges
                + "\ncommunityCount=" + communities
                + "\n说明: 检索到的 community / graph_explore 内容本身就证明知识库已构建；"
                + "不要因为上下文里没有「构建日志」字样就判断为未构建。";
        Map<String, Object> row = evidence("knowledge/status", "knowledge_status", content);
        row.put("score", 200);
        row.put("retrievalType", "structured");
        row.put("built", built);
        return row;
    }

    private static int numberOrZero(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    public Map<String, Object> compareCommits(String repoId, String baseSha, String headSha) {
        return git.compare(repoId, ownerLogin(repoId), baseSha, headSha);
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

    public List<Map<String, Object>> retrieveChunks(String repoId, String ownerLogin, String question, String ignoredCommitSha, int limit) {
        // Legacy path kept for FAQ/Issue fallback. Prefer graphRagContexts for chat.
        RepoIndex index = requireReadyIndex(repoId);
        JsonNode response = codeWiki.retrieve(index.getCodeWikiRepoId(), question, 2);
        return evidenceRows(response, limit, "code");
    }

    /**
     * Standard GraphRAG Local Search: entity vector retrieval → graph expand →
     * community reports + relationships + code windows.
     * Falls back to explore+communities if the standard endpoint is unavailable.
     */
    public List<Map<String, Object>> graphRagContexts(String repoId, String ownerLogin, String question, int limit) {
        return localSearchContexts(repoId, ownerLogin, question, limit);
    }

    public List<Map<String, Object>> localSearchContexts(String repoId, String ownerLogin, String question, int limit) {
        RepoIndex index = requireReadyIndex(repoId);
        String codeWikiId = index.getCodeWikiRepoId();
        int bound = Math.min(Math.max(limit, 4), 48);
        try {
            JsonNode response = codeWiki.localSearch(codeWikiId, question, 2);
            List<Map<String, Object>> contexts = contextsFromSearchResponse(response);
            if (!contexts.isEmpty()) {
                // Local search returns entity docs first, then communities/relationships,
                // then code_window. Blind subList(0, N) drops the source code windows.
                return prioritizeLocalContexts(contexts, bound);
            }
        } catch (Exception ex) {
            // Fall through to legacy explore path.
        }
        return legacyExploreContexts(codeWikiId, question, bound);
    }

    /**
     * Keep code windows when truncating GraphRAG local contexts.
     * Order: code_window → entity → relationship → community_report → other.
     */
    static List<Map<String, Object>> prioritizeLocalContexts(List<Map<String, Object>> contexts, int bound) {
        if (contexts == null || contexts.isEmpty() || contexts.size() <= bound) {
            return contexts == null ? List.of() : contexts;
        }
        List<String> priority = List.of(
                "code_window", "relationship", "entity", "community_report", "global_map");
        List<Map<String, Object>> selected = new ArrayList<>();
        for (String type : priority) {
            for (Map<String, Object> row : contexts) {
                if (selected.size() >= bound) {
                    return selected;
                }
                if (type.equals(String.valueOf(row.getOrDefault("sourceType", ""))) && !selected.contains(row)) {
                    selected.add(row);
                }
            }
        }
        for (Map<String, Object> row : contexts) {
            if (selected.size() >= bound) {
                break;
            }
            if (!selected.contains(row)) {
                selected.add(row);
            }
        }
        return selected;
    }

    /**
     * Standard GraphRAG Global Search (DCS + map-reduce). Returns contexts plus
     * a precomputed answer in the result map under key {@code answer}.
     */
    public Map<String, Object> globalSearchResult(String repoId, String ownerLogin, String question) {
        RepoIndex index = requireReadyIndex(repoId);
        String codeWikiId = index.getCodeWikiRepoId();
        JsonNode response = codeWiki.globalSearch(codeWikiId, question, 0, true);
        List<Map<String, Object>> contexts = contextsFromSearchResponse(response);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", response.path("answer").asText(""));
        result.put("contexts", contexts);
        result.put("mode", "global");
        result.put("selectedCommunityIds", response.path("selected_community_ids"));
        result.put("prunedCommunityIds", response.path("pruned_community_ids"));
        return result;
    }

    private List<Map<String, Object>> contextsFromSearchResponse(JsonNode response) {
        List<Map<String, Object>> contexts = new ArrayList<>();
        if (response == null) return contexts;
        JsonNode rows = response.path("contexts");
        if (!rows.isArray()) return contexts;
        for (JsonNode item : rows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("file", item.path("file").asText("knowledge/context"));
            row.put("line", item.path("line").asInt(1));
            row.put("endLine", item.path("endLine").asInt(item.path("line").asInt(1)));
            if (item.hasNonNull("symbolName")) row.put("symbolName", item.path("symbolName").asText());
            if (item.hasNonNull("symbolKind")) row.put("symbolKind", item.path("symbolKind").asText());
            row.put("content", item.path("content").asText(""));
            row.put("score", item.path("score").asDouble(0));
            row.put("retrievalType", item.path("retrievalType").asText("graph"));
            row.put("sourceType", item.path("sourceType").asText("code"));
            contexts.add(row);
        }
        return contexts;
    }

    private List<Map<String, Object>> legacyExploreContexts(String codeWikiId, String question, int bound) {
        List<Map<String, Object>> contexts = new ArrayList<>();
        try {
            JsonNode explore = codeWiki.explore(codeWikiId, mapper.valueToTree(Map.of(
                    "query", question,
                    "max_files", 12,
                    "max_nodes", 120
            )));
            String exploreText = explore.path("text").asText("");
            if (!exploreText.isBlank()) {
                Map<String, Object> row = evidence("codewiki/graph-explore", "graph_explore",
                        exploreText.length() > 12000 ? exploreText.substring(0, 12000) + "\n…(truncated)" : exploreText);
                row.put("score", 98);
                row.put("retrievalType", "graph");
                contexts.add(row);
            }
            JsonNode relationships = explore.path("relationships");
            if (relationships.isArray() && !relationships.isEmpty()) {
                StringBuilder rel = new StringBuilder("Graph relationships:\n");
                int n = 0;
                for (JsonNode edge : relationships) {
                    rel.append("- ").append(edge.toString()).append('\n');
                    if (++n >= 40) break;
                }
                Map<String, Object> row = evidence("codewiki/graph-relationships", "graph_relationships", rel.toString());
                row.put("score", 90);
                row.put("retrievalType", "graph");
                contexts.add(row);
            }
        } catch (Exception ex) {
            Map<String, Object> notice = evidence("codewiki/graph-explore", "system",
                    "图探索暂不可用: " + rootMessage(ex));
            notice.put("score", 10);
            contexts.add(notice);
        }
        try {
            contexts.addAll(communityContexts(codeWikiId, question, 8));
        } catch (Exception ignored) {
            // optional
        }
        if (contexts.isEmpty()) {
            throw new IllegalStateException("GraphRAG 检索未返回可用图上下文，请确认知识库已构建且 CodeWiki 健康");
        }
        return contexts.size() > bound ? contexts.subList(0, bound) : contexts;
    }

    public List<Map<String, Object>> listCommunities(String repoId, String ownerLogin) {
        requireReadyIndex(repoId);
        return communityContexts(codeWikiId(repoId), "", 40);
    }

    /**
     * Proxy the live CodeWiki graph (nodes/edges/communities). Not synthesized locally.
     */
    public Map<String, Object> fullGraph(String repoId, String ownerLogin) {
        requireReadyIndex(repoId);
        JsonNode graph = codeWiki.fullGraph(codeWikiId(repoId));
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        List<Map<String, Object>> communities = new ArrayList<>();
        if (graph.path("nodes").isArray()) {
            for (JsonNode node : graph.path("nodes")) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", node.path("id").asText());
                row.put("type", node.path("type").asText(""));
                row.put("name", node.path("name").asText(""));
                row.put("filePath", node.path("file_path").asText(""));
                row.put("startLine", node.path("start_line").isNull() ? null : node.path("start_line").asInt());
                row.put("endLine", node.path("end_line").isNull() ? null : node.path("end_line").asInt());
                row.put("language", node.path("language").asText(""));
                row.put("symbolId", node.path("symbol_id").asText(""));
                row.put("confidence", node.path("confidence").asDouble(1.0));
                nodes.add(row);
            }
        }
        if (graph.path("edges").isArray()) {
            for (JsonNode edge : graph.path("edges")) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", edge.path("id").asText());
                row.put("source", edge.path("source").asText());
                row.put("target", edge.path("target").asText());
                row.put("type", edge.path("type").asText(""));
                row.put("confidence", edge.path("confidence").asDouble(1.0));
                row.put("reason", edge.path("reason").asText(""));
                edges.add(row);
            }
        }
        if (graph.path("communities").isArray()) {
            for (JsonNode community : graph.path("communities")) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", community.path("id").asText());
                row.put("name", community.path("name").asText(""));
                row.put("level", community.path("level").asInt(0));
                row.put("summary", community.path("summary").asText(""));
                row.put("rank", community.path("rank").asInt(0));
                communities.add(row);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "codewiki");
        result.put("codeWikiRepoId", codeWikiId(repoId));
        result.put("repoId", repoId);
        result.put("nodeCount", nodes.size());
        result.put("edgeCount", edges.size());
        result.put("communityCount", communities.size());
        result.put("nodes", nodes);
        result.put("edges", edges);
        result.put("communities", communities);
        result.put("note", "数据直接来自 CodeWiki GET /api/repos/{id}/graph，非本地编造");
        return result;
    }

    private List<Map<String, Object>> communityContexts(String codeWikiId, String question, int limit) {
        JsonNode rows = codeWiki.communities(codeWikiId);
        if (rows == null) return List.of();
        JsonNode list = rows.isArray() ? rows : rows.path("items");
        if (!list.isArray()) list = rows.path("communities");
        if (!list.isArray()) return List.of();
        String lower = question == null ? "" : question.toLowerCase();
        List<Map<String, Object>> scored = new ArrayList<>();
        for (JsonNode community : list) {
            String name = firstText(community, "name", "title", "id");
            String summary = firstText(community, "summary", "description", "content");
            if (name.isBlank() && summary.isBlank()) continue;
            String blob = (name + "\n" + summary).toLowerCase();
            int score = lower.isBlank() ? 50 : 20;
            if (!lower.isBlank()) {
                for (String token : lower.split("[\\s\\p{Punct}]+")) {
                    if (token.length() >= 3 && blob.contains(token)) score += 8;
                }
            }
            Map<String, Object> row = evidence("codewiki/community/" + name, "community",
                    "community: " + name + "\n" + summary);
            row.put("score", score);
            row.put("retrievalType", "community");
            row.put("symbolName", name);
            row.put("symbolKind", "community");
            scored.add(row);
        }
        scored.sort((a, b) -> Integer.compare(
                ((Number) b.getOrDefault("score", 0)).intValue(),
                ((Number) a.getOrDefault("score", 0)).intValue()));
        return scored.size() > limit ? scored.subList(0, limit) : scored;
    }

    public List<Map<String, Object>> retrieveChunksByPathHints(
            String repoId, String ownerLogin, String question, List<String> pathHints, int limit) {
        return retrieveChunks(repoId, ownerLogin, question + "\nRelevant paths: " + String.join(", ", pathHints), null, limit);
    }

    public List<Map<String, Object>> apiSpecificationContexts(String repoId, String ownerLogin, int limit) {
        return retrieveChunks(repoId, ownerLogin, "API endpoints controllers routes OpenAPI Swagger request response", null, limit)
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
                .map(index -> {
                    Map<String, Object> view = new LinkedHashMap<>(graphStatusView(index));
                    try {
                        JsonNode live = codeWiki.graphStatus(codeWikiId(repoId));
                        view.put("nodeCount", firstInt(live, "node_count", "nodes"));
                        view.put("edgeCount", firstInt(live, "edge_count", "edges"));
                        view.put("chunkCount", firstInt(live, "chunk_count", "chunks"));
                        view.put("fileCount", firstInt(live, "file_count", "files"));
                        if (live.path("nodes_by_type").isObject()) {
                            view.put("nodesByType", jsonValue(live.path("nodes_by_type")));
                        }
                        if (live.path("edges_by_type").isObject()) {
                            view.put("edgesByType", jsonValue(live.path("edges_by_type")));
                        }
                        try {
                            JsonNode communities = codeWiki.communities(codeWikiId(repoId));
                            JsonNode list = communities != null && communities.isArray()
                                    ? communities
                                    : (communities == null ? null : communities.path("items"));
                            if (list != null && list.isArray()) {
                                view.put("communityCount", list.size());
                            }
                        } catch (Exception ignoredCommunities) {
                            // keep cached communityCount
                        }
                        view.put("inspectHint",
                                "可在本机打开 http://127.0.0.1:8001 查看 CodeWiki；"
                                        + "或调用 GET /api/repos/{repoId}/knowledge/communities 阅读社区摘要。");
                        view.put("status", firstInt(live, "node_count", "nodes") > 0 ? "ready" : view.get("status"));
                    } catch (Exception ignored) {
                        // Keep H2 cached counts when CodeWiki is down.
                    }
                    return view;
                })
                .orElse(Map.of("status", "not_indexed", "provider", "codewiki",
                        "nodeCount", 0, "edgeCount", 0, "communityCount", 0, "chunkCount", 0));
    }

    public Map<String, Object> graphSearch(String repoId, String ownerLogin, JsonNode parameters) {
        JsonNode response = codeWiki.graphSearch(codeWikiId(repoId), parameters);
        List<Map<String, Object>> items = new ArrayList<>();
        response.path("results").forEach(hit -> items.add(graphNode(hit.path("node"), hit.path("score").asDouble(0))));
        return Map.of(
                "query", response.path("query").asText(parameters.path("query").asText("")),
                "items", items,
                "total", items.size()
        );
    }

    public Map<String, Object> callers(String repoId, String ownerLogin, JsonNode parameters) {
        return relationshipTraversal(codeWiki.callers(codeWikiId(repoId), parameters));
    }

    public Map<String, Object> callees(String repoId, String ownerLogin, JsonNode parameters) {
        return relationshipTraversal(codeWiki.callees(codeWikiId(repoId), parameters));
    }

    public Map<String, Object> impact(String repoId, String ownerLogin, JsonNode parameters) {
        JsonNode response = codeWiki.impact(codeWikiId(repoId), parameters);
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        response.path("nodes").forEach(node -> nodes.add(graphNode(node, null)));
        response.path("edges").forEach(edge -> edges.add(graphEdge(edge)));
        return Map.of("nodes", nodes, "edges", edges, "truncated", false);
    }

    public Map<String, Object> explore(String repoId, String ownerLogin, JsonNode body) {
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

    public Map<String, Object> affected(String repoId, String ownerLogin, JsonNode body) {
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

    public JsonNode generateWiki(String repoId, String ownerLogin, JsonNode body) { return codeWiki.generateWiki(codeWikiId(repoId), body); }

    public void setWikiError(String repoId, String error) { wikiErrors.put(repoId, error); }
    public void clearWikiError(String repoId) { wikiErrors.remove(repoId); }
    public String getWikiError(String repoId) { return wikiErrors.getOrDefault(repoId, ""); }
    public Map<String, Object> readWiki(String repoId, String ownerLogin, String language) {
        JsonNode response = codeWiki.readWiki(codeWikiId(repoId), language);
        List<Map<String, Object>> pages = new ArrayList<>();
        int order = 0;
        for (JsonNode page : response.path("pages")) {
            String title = page.path("title").asText(page.path("slug").asText("Untitled"));
            String content = page.path("markdown").asText("");
            if (isErrorWikiPage(title, content)) {
                continue;
            }
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("id", firstText(page, "id", "slug"));
            mapped.put("title", title);
            mapped.put("path", page.path("slug").asText(""));
            mapped.put("content", content);
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
        RepoIndex index = requireReadyIndex(repoId);
        String id = index.getCodeWikiRepoId() == null ? "" : index.getCodeWikiRepoId().trim();
        if (!id.isBlank() && codeWikiRepoAlive(id)) {
            return id;
        }
        String remapped = findRegisteredRepo(safe(index.getFullName()), "");
        if (remapped.isBlank()) {
            remapped = findRegisteredRepoByLocalRepoId(repoId);
        }
        if (!remapped.isBlank()) {
            index.setCodeWikiRepoId(remapped);
            store.saveIndex(index);
            return remapped;
        }
        throw new IllegalStateException(
                "CodeWiki 仓库记录已失效（可能因 Docker 重建），请在知识库页点击「重新构建」");
    }

    private void runFullAnalyzeAndGraph(String taskId, String progressKey, int total,
                                        String codeWikiId, boolean firstAttempt) {
        setTaskAndProgress(taskId, progressKey, 6, total, "analyze",
                firstAttempt ? "正在提交 CodeWiki 源码分析任务" : "CodeWiki 图谱为空或任务中断，重新分析源码");
        try {
            CodeWikiClient.RunResponse run = codeWiki.analyze(codeWikiId);
            if (run == null || run.resolvedId().isBlank()) {
                throw new IllegalStateException("CodeWiki analyze 响应缺少 run_id");
            }
            codeWiki.awaitRun(codeWikiId, run.resolvedId(),
                    (pollCount, elapsedSec) -> {
                        int subStep = Math.min(6 + pollCount / 2, 13);
                        setTaskAndProgress(taskId, progressKey, subStep, total, "analyze",
                                "CodeWiki 正在分析源码 · 已等待 " + elapsedSec + " 秒");
                    });
        } catch (CodeWikiException ex) {
            // Container recreate (e.g. start-dev / compose up) kills in-process analyze workers.
            // Wait for CodeWiki to come back and retry once instead of failing the UI at ~30%.
            if (firstAttempt && isWorkerLostAfterRestart(ex)) {
                setTaskAndProgress(taskId, progressKey, 6, total, "analyze",
                        "检测到 CodeWiki 容器重启，等待服务恢复后自动重试分析");
                waitForCodeWikiReady(90);
                runFullAnalyzeAndGraph(taskId, progressKey, total, codeWikiId, false);
                return;
            }
            throw ex;
        }
        setTaskAndProgress(taskId, progressKey, 14, total, "community_naming",
                "等待 Leiden 社区 LLM 命名/摘要（analyze 后台任务）");
        ensureCommunityReports(taskId, progressKey, total, codeWikiId);

        setTaskAndProgress(taskId, progressKey, 15, total, "graphrag",
                "正在构建标准 GraphRAG（实体描述 Embedding，供 Local Search）");
        try {
            codeWiki.buildStandardGraph(codeWikiId);
            setTaskAndProgress(taskId, progressKey, 17, total, "graphrag", "标准 GraphRAG（实体向量）构建完成");
        } catch (Exception ex) {
            String msg = rootMessage(ex);
            setTaskAndProgress(taskId, progressKey, 16, total, "graphrag",
                    "标准 GraphRAG 失败，回退无向量索引: " + msg);
            try {
                // Avoid re-triggering the same embedding 403/ToS failure.
                codeWiki.buildGraph(codeWikiId, false);
                setTaskAndProgress(taskId, progressKey, 17, total, "graphrag",
                        "已回退构建普通 chunks（无 embedding）。请修正 CODEWIKI_LLM__PROFILES__EMBEDDING__* 后重建");
            } catch (Exception fallbackEx) {
                throw new IllegalStateException(
                        "GraphRAG 构建失败。请检查仓库根目录 .env 中 CODEWIKI_LLM__*（OpenRouter 模型须带 "
                                + "openrouter/ 前缀，见 .env.example），然后 docker compose up -d codewiki。"
                                + "原始错误: " + msg,
                        fallbackEx);
            }
        }
    }

    /** Ensure communities have LLM names/summaries; fall back to POST /communities/name. */
    private void ensureCommunityReports(String taskId, String progressKey, int total, String codeWikiId) {
        try {
            JsonNode rows = codeWiki.communities(codeWikiId);
            JsonNode list = rows != null && rows.isArray() ? rows
                    : (rows != null ? rows.path("items") : null);
            if (list == null || !list.isArray()) {
                list = rows != null ? rows.path("communities") : null;
            }
            int withSummary = 0;
            int totalCommunities = 0;
            if (list != null && list.isArray()) {
                totalCommunities = list.size();
                for (JsonNode community : list) {
                    String summary = community.path("summary").asText("");
                    if (summary != null && !summary.isBlank()) {
                        withSummary++;
                    }
                }
            }
            if (totalCommunities > 0 && withSummary * 2 >= totalCommunities) {
                setTaskAndProgress(taskId, progressKey, 14, total, "community_naming",
                        "社区报告已就绪（" + withSummary + "/" + totalCommunities + "）");
                return;
            }
            setTaskAndProgress(taskId, progressKey, 14, total, "community_naming",
                    "正在调用 LLM 生成社区命名与摘要");
            codeWiki.nameCommunities(codeWikiId, 40);
            setTaskAndProgress(taskId, progressKey, 14, total, "community_naming", "社区报告生成完成");
        } catch (Exception ex) {
            setTaskAndProgress(taskId, progressKey, 14, total, "community_naming",
                    "社区报告生成跳过/失败: " + rootMessage(ex));
        }
    }

    private static boolean isWorkerLostAfterRestart(Throwable ex) {
        String message = rootMessage(ex).toLowerCase();
        return message.contains("worker lost")
                || message.contains("worker dead")
                || message.contains("cleared stale")
                || message.contains("sigsegv")
                || message.contains("exit 139")
                || message.contains("analyze worker")
                || message.contains("容器可能已重启")
                || message.contains("进度长时间无变化")
                || message.contains("分析任务已丢失");
    }

    private void waitForCodeWikiReady(int timeoutSeconds) {
        Instant deadline = Instant.now().plusSeconds(Math.max(5, timeoutSeconds));
        while (Instant.now().isBefore(deadline)) {
            try {
                if (codeWiki.healthOk()) {
                    return;
                }
            } catch (Exception ignored) {
                // keep waiting
            }
            try {
                Thread.sleep(3000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private RepoIndex requireReadyIndex(String repoId) {
        RepoIndex index = store.findIndex(repoId)
                .orElseThrow(() -> new IllegalStateException("仓库尚未构建知识库"));
        if (index.getCodeWikiRepoId() == null || index.getCodeWikiRepoId().isBlank()) {
            throw new IllegalStateException("仓库尚未在 CodeWiki 注册");
        }
        return index;
    }

    private boolean codeWikiRepoAlive(String codeWikiId) {
        try {
            codeWiki.graphStatus(codeWikiId);
            return true;
        } catch (CodeWikiException ex) {
            return !isMissingCodeWikiRepo(ex);
        } catch (Exception ex) {
            String message = rootMessage(ex).toLowerCase();
            return !(message.contains("not found") || message.contains("404"));
        }
    }

    private boolean codeWikiGraphHasData(String codeWikiId) {
        try {
            JsonNode status = codeWiki.graphStatus(codeWikiId);
            return status.path("chunk_count").asInt(0) > 0
                    || status.path("node_count").asInt(0) > 0
                    || status.path("file_count").asInt(0) > 0;
        } catch (Exception ex) {
            return false;
        }
    }

    /** If graph has nodes but no chunks/entity embeddings, run standard GraphRAG build. */
    private void ensureGraphChunks(String taskId, String progressKey, int total, String codeWikiId) {
        try {
            JsonNode status = codeWiki.graphStatus(codeWikiId);
            int chunks = firstInt(status, "chunk_count", "chunks");
            int nodes = firstInt(status, "node_count", "nodes");
            if (chunks > 0 || nodes <= 0) {
                return;
            }
            setTaskAndProgress(taskId, progressKey, 15, total, "graphrag",
                    "图谱有节点但无实体检索片段，正在补建标准 GraphRAG");
            try {
                codeWiki.buildStandardGraph(codeWikiId);
            } catch (Exception standardEx) {
                codeWiki.buildGraph(codeWikiId);
            }
            setTaskAndProgress(taskId, progressKey, 17, total, "graphrag", "GraphRAG 补建完成");
        } catch (Exception ex) {
            // Keep going — projectIndex will still persist whatever counts are available.
            setTaskAndProgress(taskId, progressKey, 17, total, "graphrag",
                    "GraphRAG 补建失败: " + rootMessage(ex));
        }
    }

    /**
     * Sync H2 counters from live CodeWiki graph/status so the UI / Chat readiness
     * do not stay stuck at chunkCount=0 after a partial build.
     */
    private void refreshIndexCountsFromCodeWiki(RepoIndex index) {
        String codeWikiId = index.getCodeWikiRepoId() == null ? "" : index.getCodeWikiRepoId().trim();
        if (codeWikiId.isBlank()) return;
        try {
            JsonNode status = codeWiki.graphStatus(codeWikiId);
            int chunks = firstInt(status, "chunk_count", "chunks");
            int nodes = firstInt(status, "node_count", "nodes");
            int edges = firstInt(status, "edge_count", "edges");
            int communities = firstInt(status, "community_count", "communities");
            int files = firstInt(status, "file_count", "files", "documents");
            boolean changed = false;
            if (chunks > value(index.getChunkCount())) {
                index.setChunkCount(chunks);
                changed = true;
            }
            if (nodes > value(index.getGraphNodeCount())) {
                index.setGraphNodeCount(nodes);
                changed = true;
            }
            if (edges > value(index.getGraphEdgeCount())) {
                index.setGraphEdgeCount(edges);
                changed = true;
            }
            if (communities > value(index.getGraphCommunityCount())) {
                index.setGraphCommunityCount(communities);
                changed = true;
            }
            if (files > value(index.getFileCount())) {
                index.setFileCount(files);
                changed = true;
            }
            if (status.path("languages").isObject()) {
                String languages = status.path("languages").toString();
                if (!languages.equals(safe(index.getLanguages()))) {
                    index.setLanguages(languages);
                    changed = true;
                }
            }
            // Promote to ready when CodeWiki already has retrievable graph data.
            if ((chunks > 0 || nodes > 0) && !"ready".equals(safe(index.getStatus()))) {
                index.setStatus("ready");
                if (safe(index.getIndexedAt()).isBlank()) {
                    index.setIndexedAt(LocalDateTime.now(ZoneOffset.UTC).format(TS));
                }
                changed = true;
            }
            if (changed) {
                store.saveIndex(index);
            }
        } catch (Exception ignored) {
            // Keep cached H2 values when CodeWiki is temporarily unreachable.
        }
    }

    private static boolean isMissingCodeWikiRepo(Throwable ex) {
        String message = rootMessage(ex).toLowerCase();
        return message.contains("not found") || message.contains("404") || message.contains("repository not found");
    }

    private String findRegisteredRepo(String fullName, String path) {
        JsonNode rows = repoRows();
        if (rows == null) return "";
        for (JsonNode row : rows) {
            String name = row.path("name").asText("");
            String rowPath = row.path("path").asText("");
            if ((!fullName.isBlank() && fullName.equals(name))
                    || (!path.isBlank() && path.equals(rowPath))) {
                return row.path("id").asText(row.path("repo_id").asText(""));
            }
        }
        return "";
    }

    private String findRegisteredRepoByLocalRepoId(String repoId) {
        if (repoId == null || repoId.isBlank()) return "";
        JsonNode rows = repoRows();
        if (rows == null) return "";
        String suffix = "/" + repoId;
        String sole = "";
        int count = 0;
        for (JsonNode row : rows) {
            count++;
            String rowPath = row.path("path").asText("");
            if (rowPath.endsWith(suffix) || rowPath.endsWith(repoId)) {
                return row.path("id").asText(row.path("repo_id").asText(""));
            }
            sole = row.path("id").asText(row.path("repo_id").asText(""));
        }
        return count == 1 ? sole : "";
    }

    private JsonNode repoRows() {
        JsonNode repos = codeWiki.listRepos();
        if (repos == null) return null;
        if (repos.isArray()) return repos;
        JsonNode items = repos.path("items");
        return items.isArray() ? items : null;
    }

    private void setTaskAndProgress(String taskId, String progressKey, int done, int total,
                                     String stage, String message) {
        tasks.progress(taskId, done, total, message);
        progress.setDone(progressKey, done, message);
        progress.setStage(progressKey, stage);
    }

    private static boolean isErrorWikiPage(String title, String content) {
        String lower = (title + " " + content).toLowerCase();
        return lower.contains("agent loop")
                || lower.contains("验证错误")
                || lower.contains("llm 未返回")
                || lower.contains("生成或验证失败")
                || lower.contains("此页面未被推广")
                || (title.contains("#") && content.length() < 200
                    && (lower.contains("error") || lower.contains("failed") || lower.contains("失败")));
    }

    private void projectIndex(RepoIndex index, JsonNode repo, String head, JsonNode status) {
        index.setStatus("ready");
        index.setIndexedAt(LocalDateTime.now(ZoneOffset.UTC).format(TS));
        index.setActiveCommitSha(head);
        index.setCommitSha(shortSha(head));
        index.setFileCount(firstInt(status, "file_count", "files", "documents"));
        // Do not fall back to node_count for chunks — that hid "0 chunks" bugs.
        index.setChunkCount(firstInt(status, "chunk_count", "chunks"));
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

    private String wikiStatus(String repoId, String ownerLogin, String language) {
        try {
            String error = getWikiError(repoId);
            if (!error.isBlank()) return "failed";
            Map<String, Object> wiki = readWiki(repoId, ownerLogin, language);
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

    // ── 重置知识库 ───────────────────────────────────

    public Map<String, Object> resetKnowledge(String repoId, String ownerLogin) {
        RepoIndex index = store.findIndex(repoId).orElseThrow(() ->
                new IllegalArgumentException("仓库 " + repoId + " 未注册"));

        String codeWikiId = index.getCodeWikiRepoId() == null ? "" : index.getCodeWikiRepoId().trim();
        boolean codeWikiDeleted = false;
        String codeWikiWarning = "";

        // 1) Delete remote CodeWiki repo (graph / chunks / wiki). Without this,
        //    rebuild would reuse stale graph and look like "reset did nothing".
        if (!codeWikiId.isBlank()) {
            try {
                codeWiki.deleteRepo(codeWikiId);
                codeWikiDeleted = true;
            } catch (Exception ex) {
                codeWikiWarning = rootMessage(ex);
                // Also try path-based rematch in case local id is stale.
                try {
                    String remapped = findRegisteredRepo(safe(index.getFullName()), "");
                    if (remapped.isBlank()) {
                        remapped = findRegisteredRepoByLocalRepoId(repoId);
                    }
                    if (!remapped.isBlank() && !remapped.equals(codeWikiId)) {
                        codeWiki.deleteRepo(remapped);
                        codeWikiDeleted = true;
                        codeWikiWarning = "";
                    }
                } catch (Exception ignored) {
                    // keep original warning
                }
            }
        }

        // 2) Delete FAQ + build task errors + tasks
        jdbc.update("DELETE FROM repo_faq_items WHERE repo_id = ?", repoId);
        jdbc.update("""
                DELETE FROM knowledge_build_errors
                WHERE task_id IN (SELECT task_id FROM knowledge_build_tasks WHERE repo_id = ?)
                """, repoId);
        jdbc.update("DELETE FROM knowledge_build_tasks WHERE repo_id = ?", repoId);

        // 3) Reset local index so next build must re-register + full analyze
        jdbc.update("""
                UPDATE repo_index SET status = 'not_indexed', chunk_count = 0,
                    file_count = 0, graph_node_count = 0, graph_edge_count = 0,
                    graph_community_count = 0, codewiki_repo_id = '',
                    summary = '', languages = '{}', indexed_at = '',
                    commit_sha = '', active_commit_sha = '',
                    quality_status = 'unknown', quality_score = 0, quality_report = '{}',
                    last_task_id = ''
                WHERE repo_id = ?
                """, repoId);

        jdbc.update("DELETE FROM repo_index_settings WHERE repo_id = ?", repoId);
        wikiErrors.remove(repoId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repoId", repoId);
        result.put("status", "not_indexed");
        result.put("codeWikiDeleted", codeWikiDeleted);
        result.put("previousCodeWikiRepoId", codeWikiId);
        if (!codeWikiWarning.isBlank()) {
            result.put("codeWikiWarning", codeWikiWarning);
            result.put("message", "本地索引已重置，但 CodeWiki 远端清理失败（" + codeWikiWarning
                    + "）。请确认 CodeWiki 可用后再重新构建。");
        } else if (codeWikiId.isBlank()) {
            result.put("message", "本地索引已重置。未发现关联的 CodeWiki 仓库，可直接重新构建。");
        } else {
            result.put("message", "知识库已完整重置（含 CodeWiki 图谱与 FAQ），请重新构建。");
        }
        return result;
    }

    // ── 以下为原有代码

    private Map<String, Object> emptyOverview(String repoId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repoId", repoId); result.put("status", "not_indexed");
        result.put("tree", List.of()); result.put("modules", List.of());
        result.put("dependencies", List.of()); result.put("fileCount", 0);
        result.put("chunkCount", 0); result.put("lineCount", 0);
        result.put("sourceFileCount", 0); result.put("lineCountByLanguage", Map.of());
        result.put("lineCountNote", ""); result.put("summary", "");
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
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("file", file);
        row.put("line", 1);
        row.put("endLine", 1);
        row.put("symbolName", file);
        row.put("symbolKind", "repository");
        row.put("score", 1);
        row.put("retrievalType", "structured");
        row.put("sourceType", type);
        row.put("content", content);
        return row;
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
            if (value.isNumber()) return value.asInt();
            if (value.isTextual()) {
                try {
                    return Integer.parseInt(value.asText().trim());
                } catch (NumberFormatException ignored) {
                    // try next field
                }
            }
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

    private String ownerLogin(String repoId) {
        return store.findIndex(repoId)
                .map(RepoIndex::getFullName)
                .map(fn -> fn.contains("/") ? fn.substring(0, fn.indexOf('/')) : fn)
                .orElse("");
    }
}
