package com.repopilot.client;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.config.AppProperties;

@Component
public class CodeWikiClient {
    private final RestClient http;
    private final ObjectMapper mapper;
    private final AppProperties.CodeWiki properties;

    public CodeWikiClient(RestClient.Builder builder, ObjectMapper mapper, AppProperties properties) {
        this.mapper = mapper;
        this.properties = properties.codewiki();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(Math.max(1, this.properties.connectTimeoutSeconds())));
        factory.setReadTimeout(Duration.ofSeconds(Math.max(1, this.properties.readTimeoutSeconds())));
        this.http = builder.baseUrl(stripTrailingSlash(this.properties.baseUrl()))
                .requestFactory(factory).build();
    }

    public HealthResponse health() {
        return get("/api/health", HealthResponse.class, "health");
    }

    /** True when CodeWiki /api/health responds with status=ok. */
    public boolean healthOk() {
        try {
            HealthResponse h = health();
            return h != null && "ok".equalsIgnoreCase(h.status());
        } catch (Exception ex) {
            return false;
        }
    }

    public RepoResponse register(String path, String name) {
        return post("/api/repos", new RegisterRepoRequest(path, name, "local"), RepoResponse.class, "register");
    }

    public JsonNode listRepos() {
        return get("/api/repos", JsonNode.class, "list_repos");
    }

    public RunResponse analyze(String repoId) {
        // Standard GraphRAG: LLM names/summarizes Leiden communities on the AST entity graph.
        return post(repo(repoId) + "/analyze", Map.of("name_communities", true),
                RunResponse.class, "analyze");
    }

    public JsonNode listRuns(String repoId) {
        return get(repo(repoId) + "/runs", JsonNode.class, "list_runs");
    }

    public void deleteRepo(String repoId) {
        executeWithRetry(() -> {
            http.delete().uri(repo(repoId)).retrieve().toBodilessEntity();
            return Boolean.TRUE;
        }, "delete_repo");
    }

    /**
     * If CodeWiki still has a zombie "running" analysis (common after container restart),
     * delete + re-register is the only reliable way to unlock POST /analyze.
     */
    public boolean hasZombieRunningRun(String repoId) {
        try {
            JsonNode runs = listRuns(repoId);
            if (runs == null || !runs.isArray()) return false;
            Instant now = Instant.now();
            for (JsonNode run : runs) {
                String status = run.path("status").asText("").toLowerCase();
                if (!status.equals("running") && !status.equals("queued") && !status.equals("pending")) {
                    continue;
                }
                Instant started = parseInstant(run.path("started_at").asText(""));
                // Analyze workers die with the container; anything still "running"
                // after a few minutes with an empty graph is almost always a zombie.
                if (started == null) {
                    return true;
                }
                long ageSec = Duration.between(started, now).getSeconds();
                int progress = progressFromRunNode(run);
                // >10 minutes still running: worker almost certainly dead after typical restarts
                if (ageSec >= 600) {
                    return true;
                }
                // Brand-new runs with zero progress for >90s after start are also stuck
                if (ageSec >= 90 && progress <= 0) {
                    return true;
                }
            }
            return false;
        } catch (CodeWikiException ex) {
            return isNotFoundMessage(ex.getMessage());
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            try {
                return java.time.OffsetDateTime.parse(value).toInstant();
            } catch (Exception ignoredAgain) {
                return null;
            }
        }
    }

    public RunResponse run(String repoId, String runId) {
        return get(repo(repoId) + "/runs/" + runId, RunResponse.class, "poll_run");
    }

    public JsonNode awaitRun(String repoId, String runId) {
        return awaitRun(repoId, runId, null);
    }

    public JsonNode awaitRun(String repoId, String runId,
                             java.util.function.BiConsumer<Integer, Integer> progressCallback) {
        Instant deadline = Instant.now().plusSeconds(Math.max(1, properties.runTimeoutSeconds()));
        Instant start = Instant.now();
        int pollCount = 0;
        int consecutiveFailures = 0;
        int lastProgress = -1;
        int unchangedPolls = 0;
        while (Instant.now().isBefore(deadline)) {
            try {
                RunResponse current = run(repoId, runId);
                consecutiveFailures = 0;
                String status = current.status() == null ? "" : current.status().toLowerCase();
                if (status.equals("completed") || status.equals("complete") || status.equals("succeeded")
                        || status.equals("success") || status.equals("ready") || status.equals("done")) {
                    return mapper.valueToTree(current);
                }
                if (status.equals("failed") || status.equals("error") || status.equals("cancelled")) {
                    throw new CodeWikiException("poll_run",
                            "CodeWiki 分析失败: " + current.errorText(status),
                            false, null);
                }
                int progress = current.progressHint();
                if (progress == lastProgress) {
                    unchangedPolls++;
                } else {
                    unchangedPolls = 0;
                    lastProgress = progress;
                }
                // ~2 minutes frozen progress (24 * 5s) → zombie worker
                if (unchangedPolls >= 24 && Duration.between(start, Instant.now()).toSeconds() >= 120) {
                    throw new CodeWikiException("poll_run",
                            "CodeWiki 分析进度长时间无变化（容器可能已重启，后台任务已死），请重新构建",
                            true, null);
                }
            } catch (CodeWikiException e) {
                if (!e.retryable()) throw e;
                if (isNotFoundMessage(e.getMessage())) {
                    throw new CodeWikiException("poll_run",
                            "CodeWiki 分析任务已丢失（容器可能已重启），请重新构建知识库", true, e);
                }
                consecutiveFailures++;
                if (consecutiveFailures > 15) {
                    throw new CodeWikiException("poll_run",
                            "CodeWiki 连续 " + consecutiveFailures + " 次轮询失败，服务可能不可用", true, e);
                }
                if (progressCallback != null) {
                    int elapsedSec = (int) Duration.between(start, Instant.now()).getSeconds();
                    progressCallback.accept(pollCount, elapsedSec);
                }
                long waitSec = Math.min(30, (long) Math.pow(2, Math.min(consecutiveFailures, 5)));
                try {
                    Thread.sleep(waitSec * 1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                continue;
            }
            pollCount++;
            if (progressCallback != null) {
                int elapsedSec = (int) Duration.between(start, Instant.now()).getSeconds();
                progressCallback.accept(pollCount, elapsedSec);
            }
            try {
                Thread.sleep(Math.max(1, properties.runPollSeconds()) * 1000L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new CodeWikiException("poll_run", "等待 CodeWiki 分析时任务被中断", true, ex);
            }
        }
        throw new CodeWikiException("poll_run", "CodeWiki 分析超时", true, null);
    }

    private static boolean isNotFoundMessage(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        return lower.contains("404") || lower.contains("not found");
    }

    private static int progressFromRunNode(JsonNode run) {
        if (run == null) return 0;
        int total = run.path("scanned_count").asInt(0)
                + run.path("parsed_file_count").asInt(0)
                + run.path("node_count").asInt(0)
                + run.path("chunk_count").asInt(0);
        JsonNode stats = run.path("stats");
        if (stats.isObject()) {
            total += stats.path("scanned_count").asInt(0)
                    + stats.path("parsed_file_count").asInt(0)
                    + stats.path("node_count").asInt(0);
            JsonNode progress = stats.path("progress");
            if (progress.isObject()) {
                total += progress.path("completed").asInt(0)
                        + progress.path("scanned").asInt(0)
                        + progress.path("parsed_files").asInt(0)
                        + progress.path("symbols").asInt(0);
            }
        }
        return total;
    }

    public JsonNode update(String repoId) {
        return post(repo(repoId) + "/update",
                new UpdateRequest(true, false, false), JsonNode.class, "update");
    }

    public JsonNode buildGraph(String repoId) {
        return buildGraph(repoId, properties.includeEmbeddings());
    }

    public JsonNode buildGraph(String repoId, boolean includeEmbeddings) {
        return post(repo(repoId) + "/graphrag/build",
                new GraphBuildRequest(includeEmbeddings), JsonNode.class, "graphrag_build");
    }

    /**
     * Standard GraphRAG index: entity description embeddings on the AST graph
     * (not pure source-chunk retrieval). Forces embeddings when configured.
     */
    public JsonNode buildStandardGraph(String repoId) {
        return post(repo(repoId) + "/graphrag/build-standard",
                Map.of("include_embeddings", true, "max_entities", 4000),
                JsonNode.class, "graphrag_build_standard");
    }

    public JsonNode nameCommunities(String repoId, int maxCommunities) {
        return post(repo(repoId) + "/communities/name",
                Map.of("max_communities", Math.max(1, maxCommunities)),
                JsonNode.class, "communities_name");
    }

    public JsonNode localSearch(String repoId, String query, int maxHops) {
        return post(repo(repoId) + "/graphrag/local-search",
                Map.of("query", query, "max_hops", maxHops, "top_k", 20),
                JsonNode.class, "graphrag_local_search");
    }

    public JsonNode globalSearch(String repoId, String query, int level, boolean dynamicSelection) {
        return post(repo(repoId) + "/graphrag/global-search",
                Map.of(
                        "query", query,
                        "level", Math.max(0, level),
                        "map_batch_size", 4,
                        "dynamic_selection", dynamicSelection,
                        "max_map_batches", 8
                ),
                JsonNode.class, "graphrag_global_search");
    }

    public JsonNode retrieve(String repoId, String query, int maxHops) {
        return post(repo(repoId) + "/graphrag/retrieve",
                new RetrieveRequest(query, maxHops, properties.includeEmbeddings()),
                JsonNode.class, "graphrag_retrieve");
    }

    /** Full GraphRAG Q&A (communities + graph + sources). Requires CodeWiki LLM for synthesis. */
    public JsonNode ask(String repoId, String question, int maxHops) {
        return post(repo(repoId) + "/ask",
                new AskRequest(question, "graph_rag", maxHops, true, true),
                JsonNode.class, "ask");
    }

    public JsonNode communities(String repoId) {
        return get(repo(repoId) + "/communities", JsonNode.class, "communities");
    }

    /** Full AST/GraphRAG graph (nodes + edges + communities) from CodeWiki. */
    public JsonNode fullGraph(String repoId) {
        return get(repo(repoId) + "/graph", JsonNode.class, "graph_full");
    }

    public JsonNode graphStatus(String repoId) {
        return get(repo(repoId) + "/graph/status", JsonNode.class, "graph_status");
    }

    public JsonNode files(String repoId) {
        return get(repo(repoId) + "/files", JsonNode.class, "files");
    }

    public JsonNode graphSearch(String repoId, JsonNode body) {
        return getWithQuery(repo(repoId) + "/graph/search", body, "graph_search");
    }

    public JsonNode callers(String repoId, JsonNode body) {
        return getWithQuery(repo(repoId) + "/graph/callers", body, "graph_callers");
    }

    public JsonNode callees(String repoId, JsonNode body) {
        return getWithQuery(repo(repoId) + "/graph/callees", body, "graph_callees");
    }

    public JsonNode impact(String repoId, JsonNode body) {
        return getWithQuery(repo(repoId) + "/graph/impact", body, "graph_impact");
    }

    public JsonNode explore(String repoId, JsonNode body) {
        return post(repo(repoId) + "/graph/explore", body, JsonNode.class, "graph_explore");
    }

    public JsonNode affected(String repoId, JsonNode body) {
        return post(repo(repoId) + "/graph/affected", body, JsonNode.class, "graph_affected");
    }

    public JsonNode generateWiki(String repoId, JsonNode body) {
        String language = body == null ? "en" : body.path("language").asText("en");
        String query = "?language=" + language.replaceAll("[^A-Za-z0-9_-]", "");
        post(repo(repoId) + "/wiki/catalog" + query, Map.of(), JsonNode.class, "wiki_catalog");
        return post(repo(repoId) + "/wiki/pages/generate" + query,
                Map.of(), JsonNode.class, "wiki_generate");
    }

    public JsonNode readWiki(String repoId, String language) {
        String safeLanguage = language == null ? "en" : language.replaceAll("[^A-Za-z0-9_-]", "");
        return get(repo(repoId) + "/wiki?language=" + safeLanguage, JsonNode.class, "wiki_read");
    }

    private String repo(String repoId) {
        return "/api/repos/" + repoId;
    }

    private <T> T get(String path, Class<T> type, String operation) {
        return executeWithRetry(() -> http.get().uri(path).retrieve().body(type), operation);
    }

    private <T> T post(String path, Object body, Class<T> type, String operation) {
        return executeWithRetry(() -> http.post().uri(path).body(body).retrieve().body(type), operation);
    }

    /**
     * 重试包装：连接断开/EOF 等瞬时错误最多重试 5 次，间隔 8-64 秒递增。
     * CodeWiki 容器重启约需 20-40 秒，5 次重试最多等待 120 秒足够覆盖。
     */
    private <T> T executeWithRetry(java.util.function.Supplier<T> action, String operation) {
        int maxRetries = 5;
        long waitMs = 8000;
        RestClientException lastEx = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return action.get();
            } catch (RestClientException ex) {
                lastEx = ex;
                boolean retryable = isRetryable(ex);
                if (!retryable || attempt == maxRetries) {
                    throw mapped(operation, ex);
                }
                long backoff = waitMs * (attempt + 1);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw mapped(operation, ex);
                }
            }
        }
        throw mapped(operation, lastEx);
    }

    /**
     * 判断是否应该重试：连接断开、EOF、HTTP 502/503/504
     */
    private boolean isRetryable(Exception ex) {
        String message = (ex.getMessage() == null ? "" : ex.getMessage()).toLowerCase();
        // 连接级别的错误
        if (message.contains("unexpected end of file") ||
            message.contains("connection reset") ||
            message.contains("broken pipe") ||
            message.contains("timeout") ||
            message.contains("connect timed out")) {
            return true;
        }
        // HTTP 5xx
        if (ex instanceof RestClientResponseException response) {
            int code = response.getStatusCode().value();
            return code == 502 || code == 503 || code == 504;
        }
        // 网络 I/O 异常
        Throwable cause = ex.getCause();
        while (cause != null) {
            String causeMsg = (cause.getMessage() == null ? "" : cause.getMessage()).toLowerCase();
            if (causeMsg.contains("unexpected end of file") ||
                causeMsg.contains("connection reset") ||
                causeMsg.contains("broken pipe")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private JsonNode getWithQuery(String path, JsonNode parameters, String operation) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromPath(path);
        if (parameters != null && parameters.isObject()) {
            parameters.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isNull() && !entry.getValue().isContainerNode()) {
                    String name = "query".equals(entry.getKey()) ? "q" : entry.getKey();
                    uri.queryParam(name, entry.getValue().asText());
                }
            });
        }
        return get(uri.build().encode().toUriString(), JsonNode.class, operation);
    }

    private CodeWikiException mapped(String operation, RestClientException ex) {
        if (ex instanceof RestClientResponseException response) {
            String detail = response.getResponseBodyAsString();
            try {
                JsonNode body = mapper.readTree(detail);
                detail = text(body, "detail", text(body, "message", detail));
            } catch (Exception ignored) {
            }
            return new CodeWikiException(operation,
                    "CodeWiki " + operation + " 请求失败 (" + response.getStatusCode().value() + "): " + detail,
                    response.getStatusCode().is5xxServerError() || response.getStatusCode().value() == 429, ex);
        }
        return new CodeWikiException(operation, "CodeWiki " + operation + " 不可用: " + ex.getMessage(), true, ex);
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) return "http://codewiki:8000";
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (node == null) return fallback;
        String value = node.path(field).asText("");
        return value.isBlank() ? fallback : value;
    }

    public record HealthResponse(String status, String version) {}
    public record RegisterRepoRequest(String path, String name, String source_type) {}
    public record RepoResponse(String id, String repo_id, String name, JsonNode repository) {
        public String resolvedId() {
            if (id != null && !id.isBlank()) return id;
            if (repo_id != null && !repo_id.isBlank()) return repo_id;
            return repository == null ? "" : repository.path("id").asText("");
        }
    }
    public record RunResponse(String run_id, String id, String repo_id, String status,
                              Integer scanned_count, Integer parsed_file_count,
                              Integer node_count, Integer chunk_count,
                              JsonNode stats, JsonNode error) {
        public String resolvedId() {
            return run_id != null && !run_id.isBlank() ? run_id : (id == null ? "" : id);
        }

        public String errorText(String fallback) {
            if (error == null || error.isNull()) return fallback;
            return error.isTextual() ? error.asText(fallback) : error.toString();
        }

        public int progressHint() {
            int total = nz(scanned_count) + nz(parsed_file_count) + nz(node_count) + nz(chunk_count);
            if (stats != null && stats.isObject()) {
                total += stats.path("scanned_count").asInt(0)
                        + stats.path("parsed_file_count").asInt(0)
                        + stats.path("node_count").asInt(0);
                JsonNode progress = stats.path("progress");
                if (progress.isObject()) {
                    total += progress.path("completed").asInt(0)
                            + progress.path("scanned").asInt(0)
                            + progress.path("parsed_files").asInt(0)
                            + progress.path("symbols").asInt(0)
                            + progress.path("nodes").asInt(0);
                }
            }
            return total;
        }

        private static int nz(Integer value) {
            return value == null ? 0 : Math.max(0, value);
        }
    }
    public record UpdateRequest(boolean refresh_chunks, boolean name_communities, boolean regenerate_wiki) {}
    public record GraphBuildRequest(boolean include_embeddings) {}
    public record RetrieveRequest(String query, int max_hops, boolean include_embeddings) {}
    public record AskRequest(String question, String mode, int max_hops, boolean include_sources, boolean include_graph) {}
}
