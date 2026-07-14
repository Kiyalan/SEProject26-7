package com.repopilot.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.github.GitHubService;
import com.repopilot.support.ProgressService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class KnowledgeService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final GitHubService github;
    private final ProgressService progress;

    public KnowledgeService(JdbcTemplate jdbc, GitHubService github, ProgressService progress) {
        this.jdbc = jdbc;
        this.github = github;
        this.progress = progress;
    }

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

            upsertRepoIndex(repoId, fullName, branch, "indexing");
            upsertRepoSettings(repoId, indexEachCommit, maxCommits);
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

            jdbc.update(
                    """
                    UPDATE repo_index SET indexed_at=?, file_count=?, chunk_count=?, status='ready',
                    summary=?, languages=?, readme_path=?, commit_sha=?, topics=?, license_name=?,
                    readme_preview=?, active_commit_sha=? WHERE repo_id=?
                    """,
                    latest.get("indexedAt"), latest.get("fileCount"), latest.get("chunkCount"),
                    latest.get("summary"), latest.get("languages"), latest.get("readmePath"),
                    latestSha.substring(0, Math.min(12, latestSha.length())),
                    toJson(topics), licenseName, latest.get("readmePreview"), latestSha, repoId
            );
            jdbc.update("UPDATE repo_index_settings SET active_commit_sha=? WHERE repo_id=?", latestSha, repoId);
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
            jdbc.update("UPDATE repo_index SET status='idle' WHERE repo_id=? AND status='indexing'", repoId);
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
        for (int i = 0; i < selected.size(); i++) {
            String path = selected.get(i);
            progress.step(progressKey, "commit " + commitIndex + "/" + commitTotal + " 文件 " + (i + 1) + "/" + selected.size());
            String content = fetchFileContent(fullName, path, commitSha, token);
            if (content == null) {
                continue;
            }
            files.add(new KnowledgeUtils.FileRow(path, "file", content.length(), KnowledgeUtils.detectLanguage(path), content));
            fileMap.put(path, content);
        }

        jdbc.update("DELETE FROM commit_files WHERE commit_sha=?", commitSha);
        jdbc.update("DELETE FROM commit_chunks WHERE commit_sha=?", commitSha);

        int chunkCount = 0;
        for (KnowledgeUtils.FileRow file : files) {
            String hash = storeContent(file.content());
            jdbc.update(
                    "INSERT INTO commit_files (repo_id, commit_sha, path, content_hash, file_type, size, language) VALUES (?,?,?,?,?,?,?)",
                    repoId, commitSha, file.path(), hash, "file", file.size(), file.language()
            );
            for (Map<String, Object> chunk : KnowledgeUtils.chunkText(file.content(), file.path())) {
                jdbc.update(
                        "INSERT INTO commit_chunks (repo_id, commit_sha, file_path, chunk_index, content, start_line) VALUES (?,?,?,?,?,?)",
                        repoId, commitSha, chunk.get("file_path"), chunk.get("chunk_index"), chunk.get("content"), chunk.get("start_line")
                );
                chunkCount++;
            }
        }

        List<Map<String, Object>> modules = KnowledgeUtils.extractModules(files);
        Map<String, Integer> languages = KnowledgeUtils.extractLanguageStats(files);
        String readmePath = fileMap.keySet().stream().filter(p -> p.toLowerCase().contains("readme")).findFirst().orElse("");
        String readmePreview = readmePath.isEmpty() ? "" : fileMap.get(readmePath).substring(0, Math.min(800, fileMap.get(readmePath).length()));
        String summary = KnowledgeUtils.extractRepoSummary(fullName, fileMap, modules);

        upsertRepoCommit(repoId, commitSha, parentSha, message, author, committedAt, indexedAt, "ready",
                summary, toJson(languages), readmePath, readmePreview, files.size(), chunkCount);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("commitSha", commitSha);
        result.put("message", message);
        result.put("fileCount", files.size());
        result.put("chunkCount", chunkCount);
        result.put("summary", summary);
        result.put("languages", toJson(languages));
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
        Map<String, Object> index = queryOne("SELECT * FROM repo_index WHERE repo_id=?", repoId);
        if (index == null && resolved == null) {
            return emptyOverview(repoId);
        }
        if (resolved == null) {
            return emptyOverview(repoId);
        }
        Map<String, Object> commitRow = queryOne("SELECT * FROM repo_commits WHERE repo_id=? AND commit_sha=?", repoId, resolved);
        if (commitRow == null) {
            return emptyOverview(repoId);
        }
        return overviewFromCommit(index, commitRow, repoId, resolved);
    }

    private Map<String, Object> overviewFromCommit(Map<String, Object> index, Map<String, Object> commitRow, String repoId, String commitSha) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT path, file_type, size, language, content_hash FROM commit_files WHERE commit_sha=? ORDER BY path",
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("path", rs.getString("path"));
                    row.put("file_type", rs.getString("file_type"));
                    row.put("size", rs.getInt("size"));
                    row.put("language", rs.getString("language"));
                    row.put("content_hash", rs.getString("content_hash"));
                    return row;
                },
                commitSha
        );

        List<KnowledgeUtils.FileRow> files = rows.stream()
                .filter(r -> "file".equals(r.get("file_type")))
                .map(r -> new KnowledgeUtils.FileRow(str(r.get("path")), "file", (Integer) r.get("size"), str(r.get("language")), null))
                .toList();

        Map<String, String> fileMap = new HashMap<>();
        for (Map<String, Object> row : rows) {
            if (!"file".equals(row.get("file_type"))) {
                continue;
            }
            String content = getContent(str(row.get("content_hash")));
            if (content != null) {
                fileMap.put(str(row.get("path")), content.substring(0, Math.min(500, content.length())));
            }
        }

        List<String> paths = rows.stream().map(r -> str(r.get("path"))).toList();
        List<Map<String, Object>> indexedFiles = files.stream().limit(40)
                .map(f -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("path", f.path());
                    item.put("size", f.size());
                    item.put("language", f.language() == null || f.language().isBlank() ? "—" : f.language());
                    return item;
                }).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repoId", repoId);
        result.put("fullName", index == null ? "" : str(index.get("full_name")));
        result.put("status", str(commitRow.get("status"), "ready"));
        result.put("indexedAt", str(commitRow.get("indexed_at")));
        result.put("fileCount", commitRow.get("file_count"));
        result.put("chunkCount", commitRow.get("chunk_count"));
        result.put("summary", str(commitRow.get("summary")));
        result.put("languages", parseJsonMap(str(commitRow.get("languages"))));
        result.put("readmePath", str(commitRow.get("readme_path")));
        result.put("readmePreview", str(commitRow.get("readme_preview")));
        result.put("commitSha", commitSha);
        result.put("shortSha", commitSha.substring(0, Math.min(12, commitSha.length())));
        result.put("topics", index == null ? List.of() : parseJsonList(str(index.get("topics"))));
        result.put("license", index == null ? "" : str(index.get("license_name")));
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
        return jdbc.query(
                """
                SELECT commit_sha, parent_sha, message, author, committed_at, indexed_at,
                       file_count, chunk_count, status FROM repo_commits
                WHERE repo_id=? ORDER BY committed_at DESC
                """,
                (rs, rowNum) -> {
                    String sha = rs.getString("commit_sha");
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("commitSha", sha);
                    item.put("shortSha", sha == null ? "" : sha.substring(0, Math.min(12, sha.length())));
                    item.put("parentSha", rs.getString("parent_sha"));
                    item.put("message", rs.getString("message"));
                    item.put("author", rs.getString("author"));
                    item.put("committedAt", rs.getString("committed_at"));
                    item.put("indexedAt", rs.getString("indexed_at"));
                    item.put("fileCount", rs.getInt("file_count"));
                    item.put("chunkCount", rs.getInt("chunk_count"));
                    item.put("status", rs.getString("status"));
                    return item;
                },
                repoId
        );
    }

    public Map<String, Object> compareCommits(String repoId, String baseSha, String headSha) {
        Map<String, String> baseFiles = fileHashes(repoId, baseSha);
        Map<String, String> headFiles = fileHashes(repoId, headSha);
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
            previews.add(Map.of("path", path, "diff", simpleDiff(getContent(baseFiles.get(path)), getContent(headFiles.get(path)))));
        }

        return Map.of(
                "baseSha", baseSha,
                "headSha", headSha,
                "baseMessage", messageOf(repoId, baseSha),
                "headMessage", messageOf(repoId, headSha),
                "added", added.stream().toList(),
                "removed", removed.stream().toList(),
                "modified", modified,
                "unchanged", unchanged,
                "sharedBlobCount", sharedBlobCount(baseFiles, headFiles),
                "previews", previews
        );
    }

    public Map<String, Object> getSettings(String repoId) {
        Map<String, Object> row = queryOne("SELECT * FROM repo_index_settings WHERE repo_id=?", repoId);
        String active = jdbc.query("SELECT active_commit_sha FROM repo_index WHERE repo_id=?", rs -> rs.next() ? rs.getString(1) : "", repoId);
        if (row == null) {
            return Map.of("indexEachCommit", false, "maxCommits", 30, "activeCommitSha", str(active));
        }
        return Map.of(
                "indexEachCommit", Boolean.TRUE.equals(row.get("index_each_commit")) || Objects.equals(row.get("index_each_commit"), 1),
                "maxCommits", ((Number) row.getOrDefault("max_commits", 30)).intValue(),
                "activeCommitSha", !str(active).isBlank() ? active : str(row.get("active_commit_sha"))
        );
    }

    public Map<String, Object> saveSettings(String repoId, Boolean indexEachCommit, Integer maxCommits, String activeCommitSha) {
        Map<String, Object> current = getSettings(repoId);
        boolean each = indexEachCommit != null ? indexEachCommit : (Boolean) current.get("indexEachCommit");
        int max = maxCommits != null ? maxCommits : (Integer) current.get("maxCommits");
        String active = activeCommitSha != null ? activeCommitSha : str(current.get("activeCommitSha"));
        upsertRepoSettings(repoId, each, max, active);
        if (!active.isBlank()) {
            jdbc.update("UPDATE repo_index SET active_commit_sha=? WHERE repo_id=?", active, repoId);
        }
        return getSettings(repoId);
    }

    public List<Map<String, Object>> retrieveChunks(String repoId, String question, String commitSha, int limit) {
        String resolved = resolveCommitSha(repoId, commitSha);
        if (resolved == null) {
            return List.of();
        }
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT file_path, chunk_index, content, start_line FROM commit_chunks WHERE repo_id=? AND commit_sha=?",
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("file", rs.getString("file_path"));
                    row.put("line", rs.getInt("start_line"));
                    row.put("content", rs.getString("content"));
                    return row;
                },
                repoId, resolved
        );
        return scoreChunks(rows, question, limit);
    }

    public Map<String, Object> storageStats(String repoId) {
        return Map.of(
                "indexedCommits", count("SELECT COUNT(*) FROM repo_commits WHERE repo_id=?", repoId),
                "uniqueFileBlobs", count("SELECT COUNT(DISTINCT content_hash) FROM commit_files WHERE repo_id=?", repoId),
                "uniqueChunkBlobs", count("SELECT COUNT(*) FROM commit_chunks WHERE repo_id=?", repoId),
                "totalBlobBytes", 0,
                "fileReferences", count("SELECT COUNT(*) FROM commit_files WHERE repo_id=? AND file_type='file'", repoId)
        );
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

    private void upsertRepoIndex(String repoId, String fullName, String branch, String status) {
        if (jdbc.update("UPDATE repo_index SET full_name=?, default_branch=?, status=? WHERE repo_id=?", fullName, branch, status, repoId) == 0) {
            jdbc.update("INSERT INTO repo_index (repo_id, full_name, default_branch, status) VALUES (?,?,?,?)", repoId, fullName, branch, status);
        }
    }

    private void upsertRepoSettings(String repoId, boolean indexEachCommit, int maxCommits) {
        upsertRepoSettings(repoId, indexEachCommit, maxCommits, "");
    }

    private void upsertRepoSettings(String repoId, boolean indexEachCommit, int maxCommits, String activeCommitSha) {
        if (jdbc.update(
                "UPDATE repo_index_settings SET index_each_commit=?, max_commits=?, active_commit_sha=? WHERE repo_id=?",
                indexEachCommit, maxCommits, activeCommitSha, repoId) == 0) {
            jdbc.update(
                    "INSERT INTO repo_index_settings (repo_id, index_each_commit, max_commits, active_commit_sha) VALUES (?,?,?,?)",
                    repoId, indexEachCommit, maxCommits, activeCommitSha
            );
        }
    }

    private void upsertRepoCommit(String repoId, String commitSha, String parentSha, String message, String author,
                                  String committedAt, String indexedAt, String status, String summary, String languages,
                                  String readmePath, String readmePreview, int fileCount, int chunkCount) {
        if (jdbc.update(
                """
                UPDATE repo_commits SET parent_sha=?, message=?, author=?, committed_at=?, indexed_at=?, status=?,
                summary=?, languages=?, readme_path=?, readme_preview=?, file_count=?, chunk_count=?
                WHERE repo_id=? AND commit_sha=?
                """,
                parentSha, message, author, committedAt, indexedAt, status, summary, languages,
                readmePath, readmePreview, fileCount, chunkCount, repoId, commitSha) == 0) {
            jdbc.update(
                    """
                    INSERT INTO repo_commits (repo_id, commit_sha, parent_sha, message, author, committed_at, indexed_at,
                    status, summary, languages, readme_path, readme_preview, file_count, chunk_count)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    repoId, commitSha, parentSha, message, author, committedAt, indexedAt, status, summary, languages,
                    readmePath, readmePreview, fileCount, chunkCount
            );
        }
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

    private String storeContent(String content) {
        String hash = sha256(content);
        if (count("SELECT COUNT(*) FROM file_contents WHERE content_hash=?", hash) == 0) {
            jdbc.update("INSERT INTO file_contents (content_hash, content) VALUES (?,?)", hash, content);
        }
        return hash;
    }

    private String getContent(String hash) {
        if (hash == null || hash.isBlank()) {
            return null;
        }
        List<String> rows = jdbc.query("SELECT content FROM file_contents WHERE content_hash=?", (rs, rowNum) -> rs.getString(1), hash);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Map<String, String> fileHashes(String repoId, String commitSha) {
        Map<String, String> map = jdbc.query(
                "SELECT path, content_hash FROM commit_files WHERE repo_id=? AND commit_sha=? AND file_type='file'",
                rs -> {
                    Map<String, String> result = new HashMap<>();
                    while (rs.next()) {
                        result.put(rs.getString("path"), rs.getString("content_hash"));
                    }
                    return result;
                },
                repoId, commitSha
        );
        return map == null ? Map.of() : map;
    }

    private String resolveCommitSha(String repoId, String commitSha) {
        if (commitSha != null && !commitSha.isBlank()) {
            return commitSha;
        }
        String active = str(getSettings(repoId).get("activeCommitSha"));
        if (!active.isBlank()) {
            return active;
        }
        List<String> rows = jdbc.query(
                "SELECT commit_sha FROM repo_commits WHERE repo_id=? AND file_count>0 ORDER BY committed_at DESC LIMIT 1",
                (rs, rowNum) -> rs.getString(1),
                repoId
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Map<String, Object> queryOne(String sql, Object... args) {
        return jdbc.query(sql, rs -> rs.next() ? mapRow(rs) : null, args);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private String messageOf(String repoId, String commitSha) {
        return jdbc.query(
                "SELECT message FROM repo_commits WHERE repo_id=? AND commit_sha=?",
                rs -> rs.next() ? str(rs.getString("message")) : "",
                repoId, commitSha
        );
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

    private Map<String, Object> mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> map = new LinkedHashMap<>();
        var meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            map.put(meta.getColumnName(i).toLowerCase(), rs.getObject(i));
        }
        return map;
    }

    private String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private Map<String, Integer> parseJsonMap(String json) {
        try {
            return MAPPER.readValue(json == null || json.isBlank() ? "{}" : json, new TypeReference<>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private List<String> parseJsonList(String json) {
        try {
            return MAPPER.readValue(json == null || json.isBlank() ? "[]" : json, new TypeReference<>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }
}
