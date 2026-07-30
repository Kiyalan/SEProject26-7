package com.repopilot.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

            // GraphRAG index + optional embedding LLM. Always refresh so incremental builds
            // also pick up newly enabled embedding / chunk changes.
            buildGraphRagWithLlm(taskId, progressKey, TOTAL, codeWikiId);

            // Required LLM community naming/summaries (not optional skip).
            nameCommunitiesWithLlm(taskId, progressKey, TOTAL, codeWikiId);

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
            Path repoPath = resolveLocalRepoDir(repoId, ownerLogin, index);
            lineStats = RepoLineCountService.count(repoPath);
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
        Map<String, Object> overview = getOverview(repoId, ownerLogin, null);
        String summary = String.valueOf(overview.getOrDefault("summary", "")).trim();
        StringBuilder content = new StringBuilder();
        content.append("仓库：").append(overview.getOrDefault("fullName", repoId)).append('\n');
        if (!summary.isBlank() && !"null".equalsIgnoreCase(summary)) {
            content.append("简介：").append(summary).append('\n');
        }
        content.append("源文件约 ").append(overview.getOrDefault("sourceFileCount",
                        overview.getOrDefault("fileCount", 0)))
                .append(" 个，代码行约 ").append(overview.getOrDefault("lineCount", 0)).append(" 行。");
        // Prefer a README / docs chunk when GraphRAG retrieve has one — append as extra signal.
        try {
            List<Map<String, Object>> evidence = retrieveChunks(repoId, ownerLogin,
                    "repository overview architecture modules purpose README", null, 3);
            for (Map<String, Object> row : evidence) {
                String body = String.valueOf(row.getOrDefault("content", "")).trim();
                if (body.length() < 40) {
                    continue;
                }
                content.append("\n\n文档摘录（").append(row.getOrDefault("file", "readme")).append("）：\n");
                content.append(body.length() > 2500 ? body.substring(0, 2500) + "…" : body);
                break;
            }
        } catch (Exception ignored) {
            // overview metadata alone is still useful
        }
        return evidence("仓库概览", "repository_overview", content.toString());
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
                ? "知识库已构建完成。"
                : "知识库尚未就绪（状态=" + status + "）。请先在知识库页点击构建。";
        String content = verdict
                + "\n仓库：" + overview.getOrDefault("fullName", repoId)
                + "\n索引时间：" + overview.getOrDefault("indexedAt", "")
                + "\n文件约 " + files + " 个，索引块 " + chunks
                + "，图节点 " + nodes + "，边 " + edges + "，社区 " + communities + "。";
        Map<String, Object> row = evidence("知识库状态", "knowledge_status", content);
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
     * Chat retrieval: GraphRAG retrieve is the primary evidence; communities orient;
     * only a few target files are read locally (never bulk-expand a whole community).
     */
    public List<Map<String, Object>> graphRagContexts(String repoId, String ownerLogin, String question, int limit) {
        RepoIndex index = requireReadyIndex(repoId);
        String codeWikiId = index.getCodeWikiRepoId();
        List<Map<String, Object>> contexts = new ArrayList<>();
        int bound = Math.min(Math.max(limit, 12), 32);
        Set<String> seenFiles = new LinkedHashSet<>();
        LinkedHashSet<String> targetFiles = new LinkedHashSet<>();

        // 1) GraphRAG retrieve — primary concrete evidence (chunks / embeddings / FTS)
        try {
            JsonNode retrieve = codeWiki.retrieve(codeWikiId, question, 2);
            List<Map<String, Object>> chunks = evidenceRows(retrieve, Math.min(bound, 16), "source_code");
            for (Map<String, Object> chunk : chunks) {
                String content = String.valueOf(chunk.getOrDefault("content", "")).trim();
                if (content.isBlank()) {
                    continue;
                }
                String file = normalizeRepoPath(String.valueOf(chunk.getOrDefault("file", "")));
                if (content.length() > 6000) {
                    chunk.put("content", content.substring(0, 6000) + "\n…(truncated)");
                }
                chunk.put("score", Math.max(asDouble(chunk.get("score")), 60) + 40);
                chunk.put("retrievalType", "graphrag_chunk");
                contexts.add(chunk);
                if (!file.isBlank()) {
                    seenFiles.add(file);
                    targetFiles.add(file);
                }
            }
            JsonNode pack = retrieve.path("context_pack");
            if (pack.isObject()) {
                String packText = firstText(pack, "text", "content", "markdown");
                if (!packText.isBlank() && packText.length() > 80) {
                    Map<String, Object> row = evidence("codewiki/context-pack", "source_code",
                            packText.length() > 8000 ? packText.substring(0, 8000) + "\n…(truncated)" : packText);
                    row.put("score", 95);
                    row.put("retrievalType", "graphrag_pack");
                    contexts.add(row);
                }
            }
        } catch (Exception ex) {
            Map<String, Object> notice = evidence("codewiki/graphrag-retrieve", "system",
                    "GraphRAG 源码检索暂不可用: " + rootMessage(ex));
            notice.put("score", 8);
            contexts.add(notice);
        }

        // 2) Communities — map only (do not expand every member file)
        List<Map<String, Object>> communities = List.of();
        try {
            communities = communityContexts(codeWikiId, question, 6);
            for (Map<String, Object> community : communities) {
                community.put("score", Math.max(asDouble(community.get("score")), 40) + 15);
                contexts.add(community);
                // Collect candidate paths that also match the question tokens
                Object filesObj = community.get("memberFiles");
                if (filesObj instanceof List<?> list) {
                    for (Object item : list) {
                        String path = normalizeRepoPath(String.valueOf(item));
                        if (!path.isBlank() && pathMatchesQuestion(path, question)) {
                            targetFiles.add(path);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // optional
        }

        // 3) Question-named symbols/paths → at most a few local targets
        try {
            List<Map<String, Object>> named = localSourceFallbacks(
                    repoId, ownerLogin, index, question, seenFiles, 3);
            for (Map<String, Object> row : named) {
                contexts.add(row);
                String file = normalizeRepoPath(String.valueOf(row.getOrDefault("file", "")));
                if (file.endsWith("#api")) {
                    file = file.substring(0, file.length() - 4);
                }
                if (!file.isBlank()) {
                    targetFiles.add(file);
                }
            }
        } catch (Exception ignored) {
            // optional
        }

        // 4) Read only top target files hinted by GraphRAG (+ question overlap), not whole community
        try {
            List<String> rankedTargets = rankTargetFiles(targetFiles, question, 3);
            if (!rankedTargets.isEmpty()) {
                String joined = String.join(" ", rankedTargets);
                contexts.addAll(localSourceFallbacks(repoId, ownerLogin, index, joined, seenFiles, 3));
            }
        } catch (Exception ignored) {
            // optional
        }

        // 5) Graph explore — structural hint (secondary)
        try {
            JsonNode explore = codeWiki.explore(codeWikiId, mapper.valueToTree(Map.of(
                    "query", question,
                    "max_files", 8,
                    "max_nodes", 80
            )));
            String exploreText = explore.path("text").asText("");
            if (!exploreText.isBlank()) {
                Map<String, Object> row = evidence("codewiki/graph-explore", "graph_explore",
                        exploreText.length() > 6000 ? exploreText.substring(0, 6000) + "\n…(truncated)" : exploreText);
                row.put("score", 55);
                row.put("retrievalType", "graph");
                contexts.add(row);
            }
            contexts.addAll(localFilesFromExplore(repoId, ownerLogin, index, explore, seenFiles, 2));
        } catch (Exception ex) {
            Map<String, Object> notice = evidence("codewiki/graph-explore", "system",
                    "图探索暂不可用: " + rootMessage(ex));
            notice.put("score", 10);
            contexts.add(notice);
        }

        contexts.sort((a, b) -> Double.compare(asDouble(b.get("score")), asDouble(a.get("score"))));
        if (contexts.isEmpty()) {
            throw new IllegalStateException("GraphRAG 检索未返回可用图上下文，请确认知识库已构建且 CodeWiki 健康");
        }
        return contexts.size() > bound ? contexts.subList(0, bound) : contexts;
    }

    private static boolean pathMatchesQuestion(String path, String question) {
        if (path == null || path.isBlank() || question == null || question.isBlank()) {
            return false;
        }
        String lowerQ = question.toLowerCase();
        String base = Path.of(path).getFileName().toString().toLowerCase();
        String stem = base.contains(".") ? base.substring(0, base.lastIndexOf('.')) : base;
        if (stem.length() >= 3 && lowerQ.contains(stem)) {
            return true;
        }
        for (String token : lowerQ.split("[\\s\\p{Punct}]+")) {
            if (token.length() >= 4 && (base.contains(token) || path.toLowerCase().contains(token))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> rankTargetFiles(Set<String> candidates, String question, int limit) {
        if (candidates == null || candidates.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<String> ranked = new ArrayList<>(candidates);
        ranked.sort((a, b) -> Integer.compare(
                pathMatchScore(b, question), pathMatchScore(a, question)));
        // Prefer question-matching paths; if none match, still take top GraphRAG hits
        List<String> matched = ranked.stream()
                .filter(p -> pathMatchScore(p, question) > 0)
                .limit(limit)
                .toList();
        if (!matched.isEmpty()) {
            return matched;
        }
        return ranked.stream().limit(limit).toList();
    }

    private static int pathMatchScore(String path, String question) {
        if (path == null || path.isBlank()) {
            return 0;
        }
        int score = 1; // GraphRAG-hit baseline
        if (pathMatchesQuestion(path, question)) {
            score += 10;
        }
        String base = Path.of(path).getFileName().toString().toLowerCase();
        if (base.endsWith(".java") || base.endsWith(".py") || base.endsWith(".ts") || base.endsWith(".tsx")) {
            score += 2;
        }
        return score;
    }

    static List<String> extractPathsFromText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        Matcher m = Pattern.compile(
                "(?i)(?:`|/|^|\\s)([\\w./\\\\-]+\\.(?:java|ts|tsx|js|jsx|py|go|rs|md|yml|yaml|toml|xml|json|ps1|sh))\\b"
        ).matcher(text);
        while (m.find()) {
            String path = normalizeRepoPath(m.group(1));
            if (!path.isBlank() && !path.contains("..")
                    && (path.indexOf('/') >= 0 || path.contains("."))) {
                if (path.length() >= 3) {
                    paths.add(path);
                }
            }
        }
        return new ArrayList<>(paths);
    }

    /** Read named docs/sources from the local clone when GraphRAG chunks are thin. */
    private List<Map<String, Object>> localSourceFallbacks(
            String repoId, String ownerLogin, RepoIndex index, String question,
            Set<String> seenFiles, int limit) {
        Path root = resolveLocalRepoDir(repoId, ownerLogin, index);
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        List<String> candidates = new ArrayList<>();
        String q = question == null ? "" : question;
        String lower = q.toLowerCase();
        boolean wantsApiInventory = containsAnyCi(lower,
                "哪些方法", "所有方法", "包含哪些", "有哪些方法", "方法列表", "原代码", "源代码",
                "源码", "全部方法", "public", "做什么", "在做什么", "职责");
        if (lower.contains("readme")) {
            candidates.addAll(List.of("README.md", "README", "readme.md", "Readme.md"));
        }
        Matcher pathToken = Pattern.compile(
                "([\\w./\\\\-]+\\.(?:java|ts|tsx|js|jsx|py|go|rs|md|yml|yaml|xml|json|ps1))",
                Pattern.CASE_INSENSITIVE).matcher(q);
        while (pathToken.find()) {
            candidates.add(pathToken.group(1).replace('\\', '/'));
        }
        Matcher typeToken = Pattern.compile(
                "\\b([A-Za-z][A-Za-z0-9]*(?:Service|Controller|Client|Utils|Store|Manager|Handler|Repository|Config|Namer|Gateway))\\b"
                        + "|\\b([A-Z][a-zA-Z0-9]{2,})\\b").matcher(q);
        while (typeToken.find()) {
            String type = typeToken.group(1) != null ? typeToken.group(1) : typeToken.group(2);
            if (type == null || isNoiseTypeName(type)) {
                continue;
            }
            String javaName = guessJavaTypeName(type);
            candidates.add(javaName + ".java");
            candidates.add(type + ".java");
            candidates.add(javaName + ".ts");
            candidates.add(javaName + ".tsx");
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (String rel : candidates) {
            if (out.size() >= limit) {
                break;
            }
            String norm = normalizeRepoPath(rel);
            if (seenFiles.contains(norm) || norm.contains("..")) {
                continue;
            }
            String baseName = Path.of(rel).getFileName().toString();
            Path file = root.resolve(rel).normalize();
            boolean exactPath = file.startsWith(root.normalize()) && Files.isRegularFile(file);
            if (!exactPath) {
                file = findTypeSourceFile(root, baseName, 8000);
                if (file == null) {
                    continue;
                }
                norm = normalizeRepoPath(root.relativize(file).toString());
            }
            if (seenFiles.contains(norm)) {
                continue;
            }
            boolean isTestPath = norm.contains("/test/") || norm.contains("\\test\\")
                    || baseName.endsWith("Test.java") || baseName.endsWith("Tests.java")
                    || baseName.endsWith(".test.ts") || baseName.endsWith(".spec.ts");
            if (isTestPath && !lower.contains("test") && !lower.contains("单元测试")) {
                continue;
            }

            String fullText = readSourceFileCapped(file, 400_000);
            if (fullText.isBlank()) {
                continue;
            }
            String symbol = baseName.contains(".") ? baseName.substring(0, baseName.lastIndexOf('.')) : baseName;

            if (baseName.endsWith(".java")) {
                String inventory = extractJavaPublicApi(symbol, fullText);
                if (!inventory.isBlank()) {
                    Map<String, Object> api = evidence(norm + "#api", "source_code", inventory);
                    api.put("score", 200);
                    api.put("retrievalType", "local_api");
                    api.put("symbolName", symbol);
                    api.put("line", 1);
                    out.add(api);
                }
            } else if (baseName.endsWith(".py")) {
                String inventory = extractPythonPublicApi(symbol, fullText);
                if (!inventory.isBlank()) {
                    Map<String, Object> api = evidence(norm + "#api", "source_code", inventory);
                    api.put("score", 200);
                    api.put("retrievalType", "local_api");
                    api.put("symbolName", symbol);
                    api.put("line", 1);
                    out.add(api);
                }
            }

            int cap = wantsApiInventory ? 100_000
                    : (baseName.endsWith(".java") || baseName.endsWith(".py") ? 60_000 : 8000);
            String body = fullText.length() > cap ? fullText.substring(0, cap) + "\n…(truncated)" : fullText;
            Map<String, Object> row = evidence(norm, "source_code", body);
            row.put("score", isTestPath ? 85 : 160);
            row.put("retrievalType", "local_file");
            row.put("symbolName", symbol);
            row.put("line", 1);
            out.add(row);
            seenFiles.add(norm);
            seenFiles.add(norm + "#api");
        }
        return out;
    }

    private static Set<String> focusedSourceFiles(List<Map<String, Object>> contexts) {
        Set<String> focus = new LinkedHashSet<>();
        for (Map<String, Object> row : contexts) {
            if (!"local_file".equals(String.valueOf(row.get("retrievalType")))
                    && !"local_api".equals(String.valueOf(row.get("retrievalType")))) {
                continue;
            }
            String file = normalizeRepoPath(String.valueOf(row.getOrDefault("file", "")));
            if (file.endsWith("#api")) {
                file = file.substring(0, file.length() - 4);
            }
            if (!file.isBlank()) {
                focus.add(file);
            }
        }
        return focus;
    }

    private static String guessJavaTypeName(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        if (Character.isUpperCase(raw.charAt(0))) {
            return raw;
        }
        String lower = raw.toLowerCase();
        for (String suffix : List.of("service", "controller", "client", "utils", "store",
                "manager", "handler", "repository", "config", "namer", "gateway")) {
            if (lower.endsWith(suffix) && lower.length() > suffix.length()) {
                String head = raw.substring(0, raw.length() - suffix.length());
                return Character.toUpperCase(head.charAt(0)) + head.substring(1)
                        + Character.toUpperCase(suffix.charAt(0)) + suffix.substring(1);
            }
        }
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }

    /** Extract constructors + methods so Q&A can list APIs even when the file is huge. */
    static String extractJavaPublicApi(String typeName, String source) {
        if (source == null || source.isBlank()) {
            return "";
        }
        List<String> methods = new ArrayList<>();
        Pattern method = Pattern.compile(
                "(?m)^[ \\t]*public[ \\t][\\w.<>,\\[\\]?\\s]+[ \\t]+([A-Za-z_][A-Za-z0-9_]*)[ \\t]*\\(([^;{}]*)\\)[ \\t]*(?:throws[ \\t][^;{]+)?[ \\t]*\\{?");
        Matcher m = method.matcher(source);
        while (m.find()) {
            String name = m.group(1);
            if (name == null || name.equals(typeName) || name.equals("class") || name.equals("interface")
                    || name.equals("enum") || name.equals("record")) {
                continue;
            }
            String args = m.group(2) == null ? "" : m.group(2).replaceAll("\\s+", " ").trim();
            String sig = name + "(" + args + ")";
            if (!methods.contains(sig)) {
                methods.add(sig);
            }
        }
        Pattern ctor = Pattern.compile(
                "(?m)^[ \\t]*public[ \\t]+" + Pattern.quote(typeName) + "[ \\t]*\\(([^;{}]*)\\)");
        Matcher c = ctor.matcher(source);
        List<String> ctors = new ArrayList<>();
        while (c.find()) {
            String args = c.group(1) == null ? "" : c.group(1).replaceAll("\\s+", " ").trim();
            String sig = typeName + "(" + args + ")";
            if (!ctors.contains(sig)) {
                ctors.add(sig);
            }
        }
        if (methods.isEmpty() && ctors.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("类 ").append(typeName).append(" 的公开 API 清单（从源码抽取，完整）：\n");
        if (!ctors.isEmpty()) {
            sb.append("构造方法：\n");
            for (String sig : ctors) {
                sb.append("- ").append(sig).append('\n');
            }
        }
        sb.append("方法：\n");
        for (String sig : methods) {
            sb.append("- ").append(sig).append('\n');
        }
        sb.append("共 ").append(methods.size()).append(" 个方法。回答「有哪些方法」时必须覆盖此清单，不得只列举被其他类调用到的子集。");
        return sb.toString();
    }

    private static boolean containsAnyCi(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNoiseTypeName(String type) {
        return Set.of(
                "README", "HTTP", "HTTPS", "API", "JWT", "FAQ", "LLM", "SSE", "JSON", "XML", "SQL",
                "URL", "URI", "UUID", "DTO", "DAO", "REST", "GraphRAG", "CodeWiki", "RepoPilot",
                "What", "Where", "How", "Why", "Java", "Spring", "React", "Docker", "Postgres",
                "OpenAPI", "GitHub", "True", "False", "Null", "Main", "App", "Config"
        ).contains(type);
    }

    /** Prefer src/main (or non-test) hits for a basename like KnowledgeService.java. */
    private static Path findTypeSourceFile(Path root, String fileName, int maxVisited) {
        if (fileName == null || fileName.isBlank() || fileName.contains("..")) {
            return null;
        }
        Path mainHit = null;
        Path otherHit = null;
        Path testHit = null;
        try (var stream = Files.walk(root, 14)) {
            int visited = 0;
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                if (++visited > maxVisited) {
                    break;
                }
                if (!fileName.equalsIgnoreCase(p.getFileName().toString())) {
                    continue;
                }
                String path = p.toString().replace('\\', '/').toLowerCase();
                boolean test = path.contains("/test/") || path.contains("/tests/")
                        || fileName.toLowerCase().contains("test");
                if (path.contains("/main/") && !test) {
                    mainHit = p;
                    break;
                }
                if (test) {
                    if (testHit == null) {
                        testHit = p;
                    }
                } else if (otherHit == null) {
                    otherHit = p;
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        if (mainHit != null) {
            return mainHit;
        }
        if (otherHit != null) {
            return otherHit;
        }
        return testHit;
    }

    private List<Map<String, Object>> localFilesFromExplore(
            String repoId, String ownerLogin, RepoIndex index, JsonNode explore,
            Set<String> seenFiles, int limit) {
        Path root = resolveLocalRepoDir(repoId, ownerLogin, index);
        if (root == null || !Files.isDirectory(root) || explore == null) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        for (String field : List.of("files", "seed_nodes", "nodes", "results")) {
            JsonNode arr = explore.path(field);
            if (!arr.isArray()) {
                continue;
            }
            for (JsonNode node : arr) {
                String path = firstText(node, "file_path", "path", "file");
                if (!path.isBlank()) {
                    paths.add(path.replace('\\', '/'));
                }
            }
        }
        // Also scrape paths from explore text
        Matcher m = Pattern.compile(
                "([\\w./\\\\-]+\\.(?:java|ts|tsx|js|jsx|py|go|rs|md|yml|yaml))",
                Pattern.CASE_INSENSITIVE).matcher(explore.path("text").asText(""));
        while (m.find() && paths.size() < 20) {
            paths.add(m.group(1).replace('\\', '/'));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String rel : paths) {
            if (out.size() >= limit) {
                break;
            }
            String norm = normalizeRepoPath(rel);
            if (seenFiles.contains(norm) || norm.contains("..")) {
                continue;
            }
            Path file = root.resolve(rel).normalize();
            if (!file.startsWith(root.normalize()) || !Files.isRegularFile(file)) {
                Path found = findFileByName(root, Path.of(rel).getFileName().toString(), 4000);
                if (found == null) {
                    continue;
                }
                file = found;
                norm = normalizeRepoPath(root.relativize(found).toString());
            }
            String body = readSourceFileCapped(file, 8000);
            if (body.isBlank()) {
                continue;
            }
            Map<String, Object> row = evidence(norm, "source_code", body);
            row.put("score", 88);
            row.put("retrievalType", "local_file");
            row.put("line", 1);
            out.add(row);
            seenFiles.add(norm);
        }
        return out;
    }

    private static Path findFileByName(Path root, String fileName, int maxVisited) {
        return findTypeSourceFile(root, fileName, maxVisited);
    }

    private static String readSourceFileCapped(Path file, int maxChars) {
        try {
            long size = Files.size(file);
            if (size <= 0 || size > 512_000) {
                return "";
            }
            String body = Files.readString(file);
            if (body.length() > maxChars) {
                return body.substring(0, maxChars) + "\n…(truncated)";
            }
            return body;
        } catch (Exception ex) {
            return "";
        }
    }

    private static String normalizeRepoPath(String path) {
        return path == null ? "" : path.replace('\\', '/').replaceAll("^\\./", "");
    }

    private static double asDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
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

        Map<String, List<String>> symbolsByFile = graphSymbolsByFile(codeWikiId);

        String lower = question == null ? "" : question.toLowerCase();
        List<Map<String, Object>> scored = new ArrayList<>();
        for (JsonNode community : list) {
            String name = firstText(community, "name", "title", "id");
            String summary = firstText(community, "summary", "description", "content");
            if (name.isBlank() && summary.isBlank()) continue;
            String blob = (name + "\n" + summary).toLowerCase();
            int score = lower.isBlank() ? 55 : 30;
            if (!lower.isBlank()) {
                for (String token : lower.split("[\\s\\p{Punct}]+")) {
                    if (token.length() >= 3 && blob.contains(token)) score += 10;
                }
            }
            List<String> memberFiles = extractPathsFromText(name + "\n" + summary);
            StringBuilder enriched = new StringBuilder();
            enriched.append("community: ").append(name).append('\n').append(summary);
            if (!memberFiles.isEmpty()) {
                enriched.append("\n\nFILES:\n");
                for (String file : memberFiles) {
                    enriched.append("- ").append(file);
                    List<String> symbols = symbolsByFile.getOrDefault(file, List.of());
                    if (!symbols.isEmpty()) {
                        enriched.append(" :: ").append(String.join(", ",
                                symbols.stream().limit(24).toList()));
                    }
                    enriched.append('\n');
                }
            }
            Map<String, Object> row = evidence("codewiki/community/" + name, "community", enriched.toString());
            row.put("score", score);
            row.put("retrievalType", "community");
            row.put("symbolName", name);
            row.put("symbolKind", "community");
            row.put("memberFiles", memberFiles);
            scored.add(row);
        }
        scored.sort((a, b) -> Integer.compare(
                ((Number) b.getOrDefault("score", 0)).intValue(),
                ((Number) a.getOrDefault("score", 0)).intValue()));
        return scored.size() > limit ? scored.subList(0, limit) : scored;
    }

    private Map<String, List<String>> graphSymbolsByFile(String codeWikiId) {
        Map<String, List<String>> byFile = new LinkedHashMap<>();
        try {
            JsonNode graph = codeWiki.fullGraph(codeWikiId);
            JsonNode nodes = graph.path("nodes");
            if (!nodes.isArray()) {
                return byFile;
            }
            for (JsonNode node : nodes) {
                String type = node.path("type").asText("");
                if (!"class".equals(type) && !"function".equals(type) && !"method".equals(type)
                        && !"interface".equals(type)) {
                    continue;
                }
                String file = normalizeRepoPath(node.path("file_path").asText(""));
                String name = node.path("name").asText("");
                if (file.isBlank() || name.isBlank()) {
                    continue;
                }
                byFile.computeIfAbsent(file, key -> new ArrayList<>());
                List<String> symbols = byFile.get(file);
                if (!symbols.contains(name) && symbols.size() < 40) {
                    symbols.add(name);
                }
            }
        } catch (Exception ignored) {
            // optional enrichment
        }
        return byFile;
    }

    /** Extract Python class/def names so Q&A can list APIs for .py modules. */
    static String extractPythonPublicApi(String moduleHint, String source) {
        if (source == null || source.isBlank()) {
            return "";
        }
        List<String> classes = new ArrayList<>();
        List<String> functions = new ArrayList<>();
        List<String> methods = new ArrayList<>();
        String currentClass = "";
        String[] lines = source.split("\n", -1);
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            Matcher classMatch = Pattern.compile("^class\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*[:(]").matcher(raw);
            if (classMatch.find()) {
                currentClass = classMatch.group(1);
                if (!classes.contains(currentClass)) {
                    classes.add(currentClass);
                }
                continue;
            }
            Matcher defMatch = Pattern.compile("^( {0,7}|\\t?)def\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^)]*)\\)").matcher(raw);
            if (!defMatch.find()) {
                if (!raw.isBlank() && !raw.startsWith(" ") && !raw.startsWith("\t") && !raw.startsWith("#")) {
                    currentClass = "";
                }
                continue;
            }
            boolean indented = raw.startsWith(" ") || raw.startsWith("\t");
            String name = defMatch.group(2);
            String args = defMatch.group(3) == null ? "" : defMatch.group(3).replaceAll("\\s+", " ").trim();
            if (name.startsWith("_") && !name.startsWith("__")) {
                continue;
            }
            String sig = name + "(" + args + ")";
            if (indented && !currentClass.isBlank()) {
                String methodSig = currentClass + "." + sig;
                if (!methods.contains(methodSig)) {
                    methods.add(methodSig);
                }
            } else if (!indented) {
                currentClass = "";
                if (!functions.contains(sig)) {
                    functions.add(sig);
                }
            }
        }
        if (classes.isEmpty() && functions.isEmpty() && methods.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("模块 ").append(moduleHint == null ? "" : moduleHint)
                .append(" 的公开 API 清单（从源码抽取）：\n");
        if (!classes.isEmpty()) {
            sb.append("类: ").append(String.join(", ", classes)).append('\n');
        }
        if (!functions.isEmpty()) {
            sb.append("函数:\n");
            for (String fn : functions) {
                sb.append("- ").append(fn).append('\n');
            }
        }
        if (!methods.isEmpty()) {
            sb.append("方法:\n");
            for (String method : methods) {
                sb.append("- ").append(method).append('\n');
            }
        }
        return sb.toString().trim();
    }

    // ── Chat agent tools (community → concrete code) ─────────────────────────

    public String toolListCommunities(String repoId) {
        List<Map<String, Object>> rows = communityContexts(codeWikiId(repoId), "", 40);
        if (rows.isEmpty()) {
            return "（无社区）请确认知识库已构建。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(rows.size()).append(" 个 GraphRAG 社区：\n");
        int i = 1;
        for (Map<String, Object> row : rows) {
            String name = String.valueOf(row.getOrDefault("symbolName", ""));
            String content = String.valueOf(row.getOrDefault("content", ""));
            String preview = content.length() > 280 ? content.substring(0, 280) + "…" : content;
            sb.append(i++).append(". ").append(name).append('\n').append(preview).append("\n\n");
        }
        return sb.toString().trim();
    }

    public String toolGetCommunity(String repoId, String nameOrId) {
        if (nameOrId == null || nameOrId.isBlank()) {
            return "请提供社区名称或 id。";
        }
        String needle = nameOrId.trim().toLowerCase();
        for (Map<String, Object> row : communityContexts(codeWikiId(repoId), nameOrId, 40)) {
            String name = String.valueOf(row.getOrDefault("symbolName", ""));
            String file = String.valueOf(row.getOrDefault("file", ""));
            if (name.toLowerCase().contains(needle) || file.toLowerCase().contains(needle)
                    || needle.contains(name.toLowerCase())) {
                return String.valueOf(row.getOrDefault("content", ""));
            }
        }
        return "未找到匹配社区: " + nameOrId;
    }

    public String toolListFiles(String repoId) {
        try {
            JsonNode graph = codeWiki.fullGraph(codeWikiId(repoId));
            LinkedHashSet<String> files = new LinkedHashSet<>();
            for (JsonNode node : graph.path("nodes")) {
                String path = normalizeRepoPath(node.path("file_path").asText(""));
                if (!path.isBlank()) {
                    files.add(path);
                }
            }
            if (files.isEmpty()) {
                return "图中暂无 file_path。";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("仓库文件（来自 CodeWiki 图谱，共 ").append(files.size()).append(" 个）：\n");
            for (String file : files) {
                sb.append("- ").append(file).append('\n');
            }
            return sb.toString().trim();
        } catch (Exception ex) {
            return "列出文件失败: " + rootMessage(ex);
        }
    }

    public String toolListSymbols(String repoId, String fileOrQuery) {
        try {
            JsonNode graph = codeWiki.fullGraph(codeWikiId(repoId));
            String needle = fileOrQuery == null ? "" : fileOrQuery.trim().toLowerCase();
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (JsonNode node : graph.path("nodes")) {
                String type = node.path("type").asText("");
                if (!"class".equals(type) && !"function".equals(type) && !"method".equals(type)
                        && !"interface".equals(type)) {
                    continue;
                }
                String path = normalizeRepoPath(node.path("file_path").asText(""));
                String name = node.path("name").asText("");
                if (name.isBlank()) {
                    continue;
                }
                String line = path + " :: " + type + " " + name;
                if (!needle.isBlank()
                        && !path.toLowerCase().contains(needle)
                        && !name.toLowerCase().contains(needle)
                        && !line.toLowerCase().contains(needle)) {
                    continue;
                }
                sb.append("- ").append(line).append('\n');
                if (++count >= 80) {
                    sb.append("…(截断)\n");
                    break;
                }
            }
            return count == 0 ? "未找到匹配符号。" : ("符号列表：\n" + sb);
        } catch (Exception ex) {
            return "列出符号失败: " + rootMessage(ex);
        }
    }

    public String toolReadFile(String repoId, String ownerLogin, String path) {
        if (path == null || path.isBlank()) {
            return "请提供文件路径。";
        }
        RepoIndex index = requireReadyIndex(repoId);
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> rows = localSourceFallbacks(
                repoId, ownerLogin, index, path.trim(), seen, 4);
        if (rows.isEmpty()) {
            return "无法读取文件: " + path + "（请确认路径相对仓库根目录，且知识库本地克隆存在）";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> row : rows) {
            sb.append("### ").append(row.get("file")).append(" [").append(row.get("retrievalType")).append("]\n")
                    .append(row.get("content")).append("\n\n");
        }
        return sb.toString().trim();
    }

    public String toolRetrieveCode(String repoId, String query) {
        try {
            JsonNode retrieve = codeWiki.retrieve(codeWikiId(repoId), query == null ? "" : query, 2);
            List<Map<String, Object>> chunks = evidenceRows(retrieve, 8, "source_code");
            if (chunks.isEmpty()) {
                return "检索无结果。";
            }
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> chunk : chunks) {
                String content = String.valueOf(chunk.getOrDefault("content", ""));
                if (content.length() > 3500) {
                    content = content.substring(0, 3500) + "\n…(truncated)";
                }
                sb.append("### ").append(chunk.get("file"))
                        .append(" L").append(chunk.get("line")).append('\n')
                        .append(content).append("\n\n");
            }
            return sb.toString().trim();
        } catch (Exception ex) {
            return "retrieve 失败: " + rootMessage(ex);
        }
    }

    public String toolExploreGraph(String repoId, String query) {
        try {
            JsonNode explore = codeWiki.explore(codeWikiId(repoId), mapper.valueToTree(Map.of(
                    "query", query == null ? "" : query,
                    "max_files", 12,
                    "max_nodes", 100
            )));
            String text = explore.path("text").asText("");
            if (text.isBlank()) {
                return explore.toString();
            }
            return text.length() > 8000 ? text.substring(0, 8000) + "\n…(truncated)" : text;
        } catch (Exception ex) {
            return "explore 失败: " + rootMessage(ex);
        }
    }

    public String toolCallers(String repoId, String symbolOrQuery) {
        return toolRelationship(repoId, "callers", symbolOrQuery);
    }

    public String toolCallees(String repoId, String symbolOrQuery) {
        return toolRelationship(repoId, "callees", symbolOrQuery);
    }

    public String toolImpact(String repoId, String symbolOrQuery) {
        return toolRelationship(repoId, "impact", symbolOrQuery);
    }

    private String toolRelationship(String repoId, String kind, String symbolOrQuery) {
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            String q = symbolOrQuery == null ? "" : symbolOrQuery.trim();
            if (!q.isBlank()) {
                params.put("symbol", q);
                params.put("name", q);
                params.put("query", q);
            }
            JsonNode body = mapper.valueToTree(params);
            Map<String, Object> result = switch (kind) {
                case "callers" -> callers(repoId, "", body);
                case "callees" -> callees(repoId, "", body);
                case "impact" -> impact(repoId, "", body);
                default -> Map.of();
            };
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
            return json.length() > 10_000 ? json.substring(0, 10_000) + "\n…(truncated)" : json;
        } catch (Exception ex) {
            return kind + " 失败: " + rootMessage(ex);
        }
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
        // GraphRAG + LLM embedding / community naming happen in the shared post-analyze stages.
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

    /**
     * Always (re)build GraphRAG chunks; when embeddings are enabled this is an LLM step
     * and must produce vectors for non-empty chunk sets.
     */
    private void buildGraphRagWithLlm(String taskId, String progressKey, int total, String codeWikiId) {
        boolean withEmbed = codeWiki.includeEmbeddings();
        setTaskAndProgress(taskId, progressKey, 15, total, "graphrag",
                withEmbed ? "正在用 LLM 生成 GraphRAG embedding 索引" : "正在构建 GraphRAG 检索片段");
        JsonNode built = codeWiki.buildGraph(codeWikiId);
        assertGraphRagBuildOk(built);
        int chunks = built.path("chunk_count").asInt(0);
        int embeddings = built.path("embedding_count").asInt(0);
        setTaskAndProgress(taskId, progressKey, 16, total, "graphrag",
                withEmbed
                        ? "GraphRAG 完成：chunks=" + chunks + "，embeddings=" + embeddings
                        : "GraphRAG 完成：chunks=" + chunks);
    }

    private void assertGraphRagBuildOk(JsonNode built) {
        if (built == null) {
            throw new IllegalStateException("GraphRAG 构建无响应");
        }
        String status = built.path("status").asText("");
        if ("empty_graph".equals(status)) {
            throw new IllegalStateException("图谱为空，无法构建 GraphRAG，请先完成源码分析");
        }
        if (!codeWiki.includeEmbeddings()) {
            return;
        }
        int chunks = built.path("chunk_count").asInt(0);
        int embeddings = built.path("embedding_count").asInt(0);
        if (chunks > 0 && embeddings <= 0) {
            throw new IllegalStateException(
                    "已开启 LLM embedding，但未生成任何向量（chunks=" + chunks
                            + "）。请检查根目录 .env 中 CODEWIKI_LLM__PROFILES__EMBEDDING__* 配置后重试");
        }
    }

    /** Synchronous CommunityNamer — required LLM stage of the knowledge build. */
    private void nameCommunitiesWithLlm(String taskId, String progressKey, int total, String codeWikiId) {
        setTaskAndProgress(taskId, progressKey, 17, total, "communities",
                "正在用 LLM 生成社区名称与摘要（构建必做）");
        JsonNode naming = codeWiki.nameCommunities(codeWikiId, 150);
        String status = naming == null ? "" : naming.path("status").asText("");
        int renamed = naming == null ? 0 : naming.path("renamed_count").asInt(0);
        int communityCount = naming == null ? 0 : naming.path("community_count").asInt(0);
        if ("no_communities".equals(status)) {
            setTaskAndProgress(taskId, progressKey, 17, total, "communities",
                    "图谱尚无社区可命名（community_count=0）");
            return;
        }
        if (!"renamed".equals(status) && !"partial".equals(status)) {
            throw new IllegalStateException(
                    "社区 LLM 命名失败：status=" + status
                            + "。请检查 CODEWIKI_LLM__DEFAULT__*（聊天模型）配置");
        }
        setTaskAndProgress(taskId, progressKey, 17, total, "communities",
                "社区 LLM 摘要完成：renamed=" + renamed + " / communities=" + communityCount
                        + ("partial".equals(status) ? "（部分批次有告警）" : ""));
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

    /** All CodeWiki repo ids that match this GitHub fullName or local repoId path suffix. */
    private List<String> findAllRegisteredRepoIds(String fullName, String repoId) {
        JsonNode rows = repoRows();
        if (rows == null) return List.of();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        String suffix = repoId == null || repoId.isBlank() ? "" : "/" + repoId;
        for (JsonNode row : rows) {
            String name = row.path("name").asText("");
            String rowPath = row.path("path").asText("");
            String id = row.path("id").asText(row.path("repo_id").asText(""));
            if (id.isBlank()) continue;
            if ((!fullName.isBlank() && fullName.equals(name))
                    || (!suffix.isBlank() && (rowPath.endsWith(suffix) || rowPath.endsWith(repoId)))) {
                ids.add(id);
            }
        }
        return new ArrayList<>(ids);
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
        String fullName = safe(index.getFullName());

        // 1) Delete remote CodeWiki repo (graph / chunks / wiki). Without this,
        //    rebuild would reuse stale graph and look like "reset did nothing".
        //    Also delete any CodeWiki entry matching the same fullName (stale id / rematch).
        LinkedHashSet<String> deleteIds = new LinkedHashSet<>();
        if (!codeWikiId.isBlank()) {
            deleteIds.add(codeWikiId);
        }
        try {
            for (String matched : findAllRegisteredRepoIds(fullName, repoId)) {
                if (!matched.isBlank()) {
                    deleteIds.add(matched);
                }
            }
        } catch (Exception ignored) {
            // listing optional when CodeWiki is down
        }
        for (String id : deleteIds) {
            try {
                codeWiki.deleteRepo(id);
                codeWikiDeleted = true;
            } catch (Exception ex) {
                if (codeWikiWarning.isBlank()) {
                    codeWikiWarning = rootMessage(ex);
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

    /**
     * Local clone lives under {@code data/repos/{loginUser}/{repoId}}, not necessarily
     * under the GitHub org in full_name (e.g. Kiyalan/SEProject cloned as Yu-Liang-Yan/...).
     */
    private Path resolveLocalRepoDir(String repoId, String requestOwnerLogin, RepoIndex index) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (requestOwnerLogin != null && !requestOwnerLogin.isBlank()) {
            candidates.add(requestOwnerLogin.trim());
        }
        if (index != null && index.getOwnerLogin() != null && !index.getOwnerLogin().isBlank()) {
            candidates.add(index.getOwnerLogin().trim());
        }
        for (String login : candidates) {
            Path path = git.hostPath(repoId, login);
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        // Last resort: scan host root for */{repoId}
        try {
            Path probe = git.hostPath(repoId, candidates.isEmpty() ? "_" : candidates.iterator().next());
            Path root = probe.getParent() == null ? null : probe.getParent().getParent();
            if (root != null && Files.isDirectory(root)) {
                try (var stream = Files.list(root)) {
                    for (Path ownerDir : stream.toList()) {
                        if (!Files.isDirectory(ownerDir)) continue;
                        Path hit = ownerDir.resolve(repoId);
                        if (Files.isDirectory(hit)) {
                            return hit;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        String fallback = candidates.isEmpty() ? ownerLogin(repoId) : candidates.iterator().next();
        return git.hostPath(repoId, fallback == null ? "" : fallback);
    }

    private String ownerLogin(String repoId) {
        return store.findIndex(repoId)
                .map(index -> {
                    String stored = index.getOwnerLogin();
                    if (stored != null && !stored.isBlank()) {
                        return stored.trim();
                    }
                    String fullName = index.getFullName();
                    if (fullName != null && fullName.contains("/")) {
                        return fullName.substring(0, fullName.indexOf('/'));
                    }
                    return fullName == null ? "" : fullName;
                })
                .orElse("");
    }
}
