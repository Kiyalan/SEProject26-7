package com.repopilot.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.client.GitHubClient;
import com.repopilot.entity.CommitChunk;
import com.repopilot.entity.CommitFile;
import com.repopilot.entity.RepoCommit;
import com.repopilot.entity.RepoIndex;
import com.repopilot.repository.support.KnowledgeStore;
import com.repopilot.util.JsonUtils;
import com.repopilot.util.KnowledgePolicy;
import com.repopilot.util.KnowledgeUtils;

@Service
public class KnowledgeService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final KnowledgeStore store;
    private final GitHubClient github;
    private final ProgressService progress;
    private final LlmService llm;

    public KnowledgeService(KnowledgeStore store, GitHubClient github, ProgressService progress, LlmService llm) {
        this.store = store;
        this.github = github;
        this.progress = progress;
        this.llm = llm;
    }
    @Transactional
    public Map<String, Object> buildKnowledge(String repoId, String token, boolean indexEachCommit, int maxCommits, List<String> commitShas) {
        String progressKey = "knowledge:" + repoId;
        progress.start(progressKey, 100, "准备构建知识库");
        try {
            JsonNode repo = requireNode(github.get("/repositories/" + repoId, token), "仓库不存在");
            String fullName = text(repo, "full_name");
            String branch = text(repo, "default_branch", "main");
            List<String> topics = new ArrayList<>();
            repo.path("topics").forEach(t -> topics.add(t.asText("")));
            String licenseName = repo.path("license").isMissingNode() || repo.path("license").isNull()
                    ? "" : text(repo.path("license"), "spdx_id", text(repo.path("license"), "name", ""));

            store.upsertIndex(repoId, fullName, branch, "indexing");
            store.upsertSettings(repoId, indexEachCommit, maxCommits, "");
            progress.step(progressKey, "获取 commit 列表");

            List<JsonNode> targets = loadCommits(fullName, branch, token, indexEachCommit, maxCommits, commitShas);
            if (targets.isEmpty()) {
                throw new IllegalStateException("没有可索引的 commit");
            }

            List<Map<String, Object>> indexed = new ArrayList<>();
            for (int i = 0; i < targets.size(); i++) {
                progress.step(progressKey, "索引 commit " + (i + 1) + "/" + targets.size());
                indexed.add(indexCommit(repoId, fullName, targets.get(i), token, progressKey, i + 1, targets.size()));
            }

            Map<String, Object> latest = indexed.getFirst();
            String latestSha = str(latest.get("commitSha"));
            if (latestSha.isBlank()) {
                throw new IllegalStateException("索引结果缺少 commitSha");
            }

            RepoIndex index = store.findIndex(repoId).orElseThrow();
            index.setIndexedAt((String) latest.get("indexedAt"));
            index.setFileCount((Integer) latest.get("fileCount"));
            index.setChunkCount((Integer) latest.get("chunkCount"));
            index.setStatus("ready");
            index.setSummary((String) latest.get("summary"));
            index.setLanguages((String) latest.get("languages"));
            index.setReadmePath((String) latest.get("readmePath"));
            index.setCommitSha(latestSha.substring(0, Math.min(12, latestSha.length())));
            index.setTopics(JsonUtils.toJson(topics));
            index.setLicenseName(licenseName);
            index.setReadmePreview((String) latest.get("readmePreview"));
            index.setActiveCommitSha(latestSha);
            store.saveIndex(index);
            store.upsertSettings(repoId, indexEachCommit, maxCommits, latestSha);
            progress.finish(progressKey, "知识库构建完成");

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("repoId", repoId);
            response.put("fullName", fullName);
            response.put("indexedCommits", indexed.size());
            response.put("commits", indexed.stream().map(this::commitSummary).toList());
            response.put("activeCommitSha", latestSha);
            response.put("deduplication", storageStats(repoId));
            response.put("status", "ready");
            return response;
        } catch (Exception ex) {
            store.resetIndexingStatus(repoId);
            progress.fail(progressKey, rootMessage(ex));
            if (ex instanceof IllegalStateException illegal) {
                throw illegal;
            }
            throw new IllegalStateException("构建知识库失败: " + rootMessage(ex), ex);
        }
    }

    private List<JsonNode> loadCommits(String fullName, String branch, String token,
                                       boolean indexEachCommit, int maxCommits, List<String> commitShas) {
        List<JsonNode> targets = new ArrayList<>();
        if (commitShas != null && !commitShas.isEmpty()) {
            for (String sha : commitShas) {
                targets.add(requireNode(github.get("/repos/" + fullName + "/commits/" + sha, token), "commit 不存在: " + sha));
            }
            return targets;
        }
        if (indexEachCommit) {
            JsonNode commits = github.get("/repos/" + fullName + "/commits", token, Map.of("per_page", Math.min(maxCommits, 100)));
            if (commits != null && commits.isArray()) {
                commits.forEach(targets::add);
            }
            return targets;
        }
        JsonNode branchInfo = requireNode(github.get("/repos/" + fullName + "/branches/" + branch, token), "无法读取分支: " + branch);
        String headSha = text(branchInfo.path("commit"), "sha");
        if (headSha.isBlank()) {
            throw new IllegalStateException("无法获取默认分支 HEAD");
        }
        targets.add(requireNode(github.get("/repos/" + fullName + "/commits/" + headSha, token), "无法读取 HEAD commit"));
        return targets;
    }

    private Map<String, Object> indexCommit(String repoId, String fullName, JsonNode commit, String token,
                                            String progressKey, int commitIndex, int commitTotal) {
        String commitSha = text(commit, "sha");
        String parentSha = commit.path("parents").isArray() && commit.path("parents").size() > 0
                ? text(commit.path("parents").get(0), "sha") : "";
        String message = text(commit.path("commit"), "message");
        String author = text(commit.path("author"), "login", text(commit.path("commit").path("author"), "name", "unknown"));
        String committedAt = text(commit.path("commit").path("author"), "date").replace("T", " ");
        if (committedAt.length() > 19) {
            committedAt = committedAt.substring(0, 19);
        }
        String indexedAt = LocalDateTime.now(ZoneOffset.UTC).format(TS);

        JsonNode treeData = github.get("/repos/" + fullName + "/git/trees/" + commitSha, token, Map.of("recursive", "1"));
        List<String> selected = selectPaths(treeData);

        List<KnowledgeUtils.FileRow> files = new ArrayList<>();
        Map<String, String> fileMap = new HashMap<>();
        Map<String, String> fileSummaryByPath = new LinkedHashMap<>();
        for (int i = 0; i < selected.size(); i++) {
            String path = selected.get(i);
            progress.step(progressKey, "commit " + commitIndex + "/" + commitTotal + " 文件 " + (i + 1) + "/" + selected.size());
            String content = fetchFileContent(fullName, path, commitSha, token);
            if (content == null) {
                continue;
            }
            files.add(new KnowledgeUtils.FileRow(path, "file", content.length(), KnowledgeUtils.detectLanguage(path), content));
            fileMap.put(path, content);

            // LLM 生成文件摘要（跳过过小的文件）
            if (content.length() > 200 && llm.configured()) {
                String lang = KnowledgeUtils.detectLanguage(path);
                String summary = llm.summarizeCode(path, content, lang);
                if (summary != null && !summary.isBlank()) {
                    fileSummaryByPath.put(path, summary);
                }
            }
        }

        List<Map<String, Object>> modules = KnowledgeUtils.extractModules(files);
        Map<String, Integer> languages = KnowledgeUtils.extractLanguageStats(files);
        String readmePath = fileMap.keySet().stream().filter(p -> p.toLowerCase().contains("readme")).findFirst().orElse("");
        String readmePreview = readmePath.isEmpty() ? "" : fileMap.get(readmePath).substring(0, Math.min(800, fileMap.get(readmePath).length()));
        String summary = KnowledgeUtils.extractRepoSummary(fullName, fileMap, modules);

        List<CommitFile> commitFiles = new ArrayList<>();
        List<CommitChunk> commitChunks = new ArrayList<>();
        List<String> chunkTexts = new ArrayList<>();
        int chunkCount = 0;
        for (KnowledgeUtils.FileRow file : files) {
            String hash = storeContent(file.content());
            String fileSummary = fileSummaryByPath.getOrDefault(file.path(), "");
            commitFiles.add(KnowledgeStore.newFile(repoId, commitSha, file.path(), hash, file.language(), file.size(), fileSummary));
            for (Map<String, Object> chunk : KnowledgeUtils.chunkText(file.content(), file.path())) {
                String chunkContent = str(chunk.get("content"));
                commitChunks.add(KnowledgeStore.newChunk(
                        repoId, commitSha, str(chunk.get("file_path")),
                        (Integer) chunk.get("chunk_index"), chunkContent, (Integer) chunk.get("start_line"), null
                ));
                chunkTexts.add(chunkContent);
                chunkCount++;
            }
        }

        // 批量生成 embedding
        if (llm.configured() && !chunkTexts.isEmpty()) {
            List<float[]> embeddings = batchEmbed(chunkTexts, progressKey);
            for (int i = 0; i < commitChunks.size() && i < embeddings.size(); i++) {
                if (embeddings.get(i) != null) {
                    commitChunks.get(i).setEmbedding(LlmService.floatsToBytes(embeddings.get(i)));
                }
            }
        }

        store.replaceCommitArtifacts(repoId, commitSha, commitFiles, commitChunks);

        // 按模块聚合文件摘要，生成模块级概述
        StringBuilder moduleSummaryBuilder = new StringBuilder();
        if (llm.configured() && !fileSummaryByPath.isEmpty()) {
            Map<String, List<String>> moduleFileSummaries = new LinkedHashMap<>();
            for (Map<String, Object> module : modules) {
                String moduleName = str(module.get("name"));
                @SuppressWarnings("unchecked")
                List<String> moduleFiles = (List<String>) module.getOrDefault("files", List.of());
                List<String> summaries = new ArrayList<>();
                for (String f : moduleFiles) {
                    String s = fileSummaryByPath.get(f);
                    if (s != null) {
                        summaries.add(f + ": " + s);
                    }
                }
                if (!summaries.isEmpty()) {
                    moduleFileSummaries.put(moduleName, summaries);
                }
            }
            if (!moduleFileSummaries.isEmpty()) {
                List<String> moduleSummaries = new ArrayList<>();
                for (Map.Entry<String, List<String>> entry : moduleFileSummaries.entrySet()) {
                    String ms = llm.summarizeModule(entry.getKey(), entry.getValue());
                    if (ms != null && !ms.isBlank()) {
                        moduleSummaries.add("【" + entry.getKey() + "】" + ms);
                    }
                }
                moduleSummaryBuilder.append(String.join("\n\n", moduleSummaries));
            }
        }

        RepoCommit commitEntity = new RepoCommit();
        commitEntity.setRepoId(repoId);
        commitEntity.setCommitSha(commitSha);
        commitEntity.setParentSha(parentSha);
        commitEntity.setMessage(message);
        commitEntity.setAuthor(author);
        commitEntity.setCommittedAt(committedAt);
        commitEntity.setIndexedAt(indexedAt);
        commitEntity.setStatus("ready");
        commitEntity.setSummary(summary);
        commitEntity.setModuleSummary(moduleSummaryBuilder.isEmpty() ? "" : moduleSummaryBuilder.toString());
        commitEntity.setLanguages(JsonUtils.toJson(languages));
        commitEntity.setReadmePath(readmePath);
        commitEntity.setReadmePreview(readmePreview);
        commitEntity.setFileCount(files.size());
        commitEntity.setChunkCount(chunkCount);
        store.upsertCommit(commitEntity);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("commitSha", commitSha);
        result.put("message", message);
        result.put("fileCount", files.size());
        result.put("chunkCount", chunkCount);
        result.put("summary", summary);
        result.put("languages", JsonUtils.toJson(languages));
        result.put("readmePath", readmePath);
        result.put("readmePreview", readmePreview);
        result.put("indexedAt", indexedAt);
        return result;
    }

    private List<String> selectPaths(JsonNode treeData) {
        List<String> candidatePaths = new ArrayList<>();
        Map<String, Integer> sizeByPath = new HashMap<>();
        if (treeData == null || !treeData.path("tree").isArray()) {
            return List.of();
        }
        for (JsonNode item : treeData.path("tree")) {
            if (!"blob".equals(item.path("type").asText())) {
                continue;
            }
            String path = item.path("path").asText("");
            int size = item.path("size").asInt(0);
            if (path.isBlank() || KnowledgeUtils.shouldSkipPath(path) || !KnowledgeUtils.isTextFile(path) || size > KnowledgePolicy.MAX_FILE_BYTES) {
                continue;
            }
            sizeByPath.put(path, size);
            candidatePaths.add(path);
        }
        return candidatePaths.stream()
                .sorted(Comparator.comparingInt(p -> KnowledgeUtils.filePriority(p, sizeByPath.getOrDefault(p, 0))))
                .limit(KnowledgePolicy.MAX_FILES)
                .toList();
    }

    private Map<String, Object> commitSummary(Map<String, Object> commit) {
        String sha = str(commit.get("commitSha"));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("commitSha", sha);
        item.put("shortSha", sha.isBlank() ? "" : sha.substring(0, Math.min(12, sha.length())));
        item.put("message", str(commit.get("message")));
        return item;
    }

    public Map<String, Object> getOverview(String repoId, String commitSha) {
        String resolved = resolveCommitSha(repoId, commitSha);
        Optional<RepoIndex> indexOpt = store.findIndex(repoId);
        if (indexOpt.isEmpty() && resolved == null) {
            return emptyOverview(repoId);
        }
        if (resolved == null) {
            return emptyOverview(repoId);
        }
        Optional<RepoCommit> commitOpt = store.findCommit(repoId, resolved);
        if (commitOpt.isEmpty()) {
            return emptyOverview(repoId);
        }
        return overviewFromCommit(indexOpt.orElse(null), commitOpt.get(), repoId, resolved);
    }

    private Map<String, Object> overviewFromCommit(RepoIndex index, RepoCommit commitRow, String repoId, String commitSha) {
        List<CommitFile> fileEntities = store.listFiles(commitSha);

        List<KnowledgeUtils.FileRow> files = fileEntities.stream()
                .filter(f -> "file".equals(f.getFileType()))
                .map(f -> new KnowledgeUtils.FileRow(f.getId().getPath(), "file", f.getSize(), f.getLanguage(), null))
                .toList();

        Map<String, String> fileMap = new HashMap<>();
        for (CommitFile file : fileEntities) {
            if (!"file".equals(file.getFileType())) {
                continue;
            }
            store.getContent(file.getContentHash()).ifPresent(content ->
                    fileMap.put(file.getId().getPath(), content.substring(0, Math.min(500, content.length())))
            );
        }

        List<String> paths = fileEntities.stream().map(f -> f.getId().getPath()).toList();
        List<Map<String, Object>> indexedFiles = files.stream().limit(40)
                .map(f -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("path", f.path());
                    item.put("size", f.size());
                    item.put("language", f.language() == null || f.language().isBlank() ? "—" : f.language());
                    // 从 CommitFile 实体读取摘要
                    CommitFile cf = fileEntities.stream()
                            .filter(e -> e.getId().getPath().equals(f.path()))
                            .findFirst().orElse(null);
                    item.put("summary", cf != null && cf.getSummary() != null ? cf.getSummary() : "");
                    return item;
                }).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repoId", repoId);
        result.put("fullName", index == null ? "" : str(index.getFullName()));
        result.put("status", str(commitRow.getStatus(), "ready"));
        result.put("indexedAt", str(commitRow.getIndexedAt()));
        result.put("fileCount", commitRow.getFileCount());
        result.put("chunkCount", commitRow.getChunkCount());
        result.put("summary", str(commitRow.getSummary()));
        result.put("moduleSummary", str(commitRow.getModuleSummary()));
        result.put("languages", JsonUtils.parseIntMap(commitRow.getLanguages()));
        result.put("readmePath", str(commitRow.getReadmePath()));
        result.put("readmePreview", str(commitRow.getReadmePreview()));
        result.put("commitSha", commitSha);
        result.put("shortSha", commitSha.substring(0, Math.min(12, commitSha.length())));
        result.put("topics", index == null ? List.of() : store.topics(index));
        result.put("license", index == null ? "" : str(index.getLicenseName()));
        result.put("tree", KnowledgeUtils.buildTree(paths));
        result.put("modules", KnowledgeUtils.extractModules(files));
        result.put("dependencies", KnowledgeUtils.extractDependencies(fileMap));
        result.put("indexedFiles", indexedFiles);
        result.put("commits", listIndexedCommits(repoId));
        result.put("settings", getSettings(repoId));
        result.put("deduplication", storageStats(repoId));
        return result;
    }

    private Map<String, Object> emptyOverview(String repoId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repoId", repoId);
        result.put("status", "not_indexed");
        result.put("tree", List.of());
        result.put("modules", List.of());
        result.put("dependencies", List.of());
        result.put("fileCount", 0);
        result.put("chunkCount", 0);
        result.put("summary", "");
        result.put("languages", Map.of());
        result.put("indexedFiles", List.of());
        result.put("commits", List.of());
        result.put("settings", getSettings(repoId));
        return result;
    }

    public List<Map<String, Object>> listIndexedCommits(String repoId) {
        return store.listCommits(repoId).stream().map(commit -> {
            String sha = commit.getCommitSha();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("commitSha", sha);
            item.put("shortSha", sha == null ? "" : sha.substring(0, Math.min(12, sha.length())));
            item.put("parentSha", commit.getParentSha());
            item.put("message", commit.getMessage());
            item.put("author", commit.getAuthor());
            item.put("committedAt", commit.getCommittedAt());
            item.put("indexedAt", commit.getIndexedAt());
            item.put("fileCount", commit.getFileCount());
            item.put("chunkCount", commit.getChunkCount());
            item.put("status", commit.getStatus());
            return item;
        }).toList();
    }

    public Map<String, Object> compareCommits(String repoId, String baseSha, String headSha) {
        Map<String, String> baseFiles = store.fileHashes(repoId, baseSha);
        Map<String, String> headFiles = store.fileHashes(repoId, headSha);
        Set<String> added = new TreeSet<>(headFiles.keySet());
        added.removeAll(baseFiles.keySet());
        Set<String> removed = new TreeSet<>(baseFiles.keySet());
        removed.removeAll(headFiles.keySet());
        List<String> modified = new ArrayList<>();
        int unchanged = 0;
        for (String path : baseFiles.keySet()) {
            if (headFiles.containsKey(path)) {
                if (Objects.equals(baseFiles.get(path), headFiles.get(path))) {
                    unchanged++;
                } else {
                    modified.add(path);
                }
            }
        }

        List<Map<String, Object>> previews = new ArrayList<>();
        for (String path : modified.stream().limit(5).toList()) {
            previews.add(Map.of("path", path, "diff", simpleDiff(
                    store.getContent(baseFiles.get(path)).orElse(""),
                    store.getContent(headFiles.get(path)).orElse("")
            )));
        }

        return Map.of(
                "baseSha", baseSha,
                "headSha", headSha,
                "baseMessage", store.messageOf(repoId, baseSha),
                "headMessage", store.messageOf(repoId, headSha),
                "added", added.stream().toList(),
                "removed", removed.stream().toList(),
                "modified", modified,
                "unchanged", unchanged,
                "sharedBlobCount", sharedBlobCount(baseFiles, headFiles),
                "previews", previews
        );
    }

    public Map<String, Object> getSettings(String repoId) {
        return store.settingsView(repoId);
    }

    public Map<String, Object> saveSettings(String repoId, Boolean indexEachCommit, Integer maxCommits, String activeCommitSha) {
        Map<String, Object> current = getSettings(repoId);
        boolean each = indexEachCommit != null ? indexEachCommit : (Boolean) current.get("indexEachCommit");
        int max = maxCommits != null ? maxCommits : (Integer) current.get("maxCommits");
        String active = activeCommitSha != null ? activeCommitSha : str(current.get("activeCommitSha"));
        store.upsertSettings(repoId, each, max, active);
        if (!active.isBlank()) {
            store.findIndex(repoId).ifPresent(index -> {
                index.setActiveCommitSha(active);
                store.saveIndex(index);
            });
        }
        return getSettings(repoId);
    }

    public List<Map<String, Object>> retrieveChunks(String repoId, String question, String commitSha, int limit) {
        String resolved = resolveCommitSha(repoId, commitSha);
        if (resolved == null) {
            return List.of();
        }
        List<CommitChunk> chunks = store.listChunks(repoId, resolved);
        if (chunks.isEmpty()) {
            return List.of();
        }
        // 优先使用向量检索
        if (llm.configured() && chunks.stream().anyMatch(c -> c.getEmbedding() != null)) {
            return vectorSearch(chunks, question, limit);
        }
        // 回退到关键词匹配
        List<Map<String, Object>> rows = chunks.stream().map(chunk -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("file", chunk.getId().getFilePath());
            row.put("line", chunk.getStartLine());
            row.put("content", chunk.getContent());
            return row;
        }).toList();
        return scoreChunks(rows, question, limit);
    }

    private List<Map<String, Object>> vectorSearch(List<CommitChunk> chunks, String question, int limit) {
        // 获取问题的 embedding
        List<float[]> questionEmb = llm.embed(List.of(question));
        if (questionEmb.isEmpty()) {
            return List.of();
        }
        float[] queryVec = questionEmb.getFirst();

        // 计算余弦相似度
        List<Map.Entry<Double, CommitChunk>> scored = new ArrayList<>();
        for (CommitChunk chunk : chunks) {
            if (chunk.getEmbedding() == null) continue;
            float[] chunkVec = LlmService.bytesToFloats(chunk.getEmbedding());
            double score = LlmService.cosineSimilarity(queryVec, chunkVec);
            scored.add(Map.entry(score, chunk));
        }

        return scored.stream()
                .sorted(Map.Entry.<Double, CommitChunk>comparingByKey().reversed())
                .limit(limit)
                .map(e -> {
                    CommitChunk c = e.getValue();
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("file", c.getId().getFilePath());
                    row.put("line", c.getStartLine());
                    row.put("content", c.getContent().substring(0, Math.min(500, c.getContent().length())));
                    return row;
                })
                .toList();
    }

    private List<float[]> batchEmbed(List<String> texts, String progressKey) {
        List<float[]> all = new ArrayList<>();
        int batchSize = 50;
        for (int i = 0; i < texts.size(); i += batchSize) {
            int end = Math.min(i + batchSize, texts.size());
            progress.step(progressKey, "向量化 " + (i + 1) + "-" + end + "/" + texts.size());
            List<String> batch = texts.subList(i, end);
            List<float[]> batchResult = llm.embed(batch);
            if (batchResult.size() == batch.size()) {
                all.addAll(batchResult);
            } else {
                // 失败时填充 null
                for (int j = 0; j < batch.size(); j++) {
                    all.add(null);
                }
            }
        }
        return all;
    }

    public Map<String, Object> storageStats(String repoId) {
        return store.storageStats(repoId);
    }

    private List<Map<String, Object>> scoreChunks(List<Map<String, Object>> rows, String question, int limit) {
        List<String> tokens = KnowledgeUtils.tokenize(question);
        if (tokens.isEmpty()) {
            return List.of();
        }
        List<Map.Entry<Double, Map<String, Object>>> scored = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String content = str(row.get("content")).toLowerCase();
            String path = str(row.get("file")).toLowerCase();
            double score = 0;
            if (path.endsWith("readme.md") || path.endsWith("package.json")) {
                score += 1.5;
            }
            for (String token : tokens) {
                if (path.contains(token)) {
                    score += 5;
                }
                int idx = content.indexOf(token);
                int hits = 0;
                while (idx >= 0 && hits < 6) {
                    hits++;
                    idx = content.indexOf(token, idx + token.length());
                }
                score += hits;
            }
            if (score > 0) {
                Map<String, Object> item = new LinkedHashMap<>(row);
                item.put("content", content.substring(0, Math.min(500, content.length())));
                scored.add(Map.entry(score, item));
            }
        }
        return scored.stream()
                .sorted(Map.Entry.<Double, Map<String, Object>>comparingByKey().reversed())
                .limit(limit)
                .map(Map.Entry::getValue)
                .toList();
    }

    private String storeContent(String content) {
        return store.storeContent(content, sha256(content));
    }

    private String resolveCommitSha(String repoId, String commitSha) {
        if (commitSha != null && !commitSha.isBlank()) {
            return commitSha;
        }
        String active = str(getSettings(repoId).get("activeCommitSha"));
        if (!active.isBlank()) {
            return active;
        }
        return store.findLatestIndexedCommitSha(repoId).orElse(null);
    }
    private String fetchFileContent(String fullName, String path, String ref, String token) {
        try {
            JsonNode data = github.getContents(fullName, path, token, ref);
            if (data == null || data.isMissingNode() || !"base64".equals(data.path("encoding").asText())) {
                return null;
            }
            String encoded = data.path("content").asText("").replace("\n", "");
            if (encoded.isBlank()) {
                return null;
            }
            String text = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            return text.length() > KnowledgePolicy.MAX_FILE_BYTES ? text.substring(0, KnowledgePolicy.MAX_FILE_BYTES) : text;
        } catch (Exception ex) {
            return null;
        }
    }

    private int sharedBlobCount(Map<String, String> baseFiles, Map<String, String> headFiles) {
        Set<String> shared = new HashSet<>(baseFiles.values());
        shared.retainAll(headFiles.values());
        return shared.size();
    }

    private JsonNode requireNode(JsonNode node, String message) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            throw new IllegalStateException(message);
        }
        return node;
    }

    private String text(JsonNode node, String field) {
        return text(node, field, "");
    }

    private String text(JsonNode node, String field, String fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        String value = node.path(field).asText("");
        return value.isBlank() ? fallback : value;
    }

    private String str(Object value) {
        return str(value, "");
    }

    private String str(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    private String rootMessage(Throwable ex) {
        Throwable cur = ex;
        while (cur.getCause() != null) {
            cur = cur.getCause();
        }
        String message = cur.getMessage();
        return message == null || message.isBlank() ? cur.getClass().getSimpleName() : message;
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String simpleDiff(String oldContent, String newContent) {
        List<String> oldLines = Arrays.asList(str(oldContent).split("\n"));
        List<String> newLines = Arrays.asList(str(newContent).split("\n"));
        List<String> diff = new ArrayList<>();
        int max = Math.max(oldLines.size(), newLines.size());
        for (int i = 0; i < Math.min(max, 40); i++) {
            String oldLine = i < oldLines.size() ? oldLines.get(i) : "";
            String newLine = i < newLines.size() ? newLines.get(i) : "";
            if (!oldLine.equals(newLine)) {
                diff.add("- " + oldLine);
                diff.add("+ " + newLine);
            }
        }
        return String.join("\n", diff);
    }
}
