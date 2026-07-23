package com.repopilot.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.config.AppProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

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

    public RepoResponse register(String path, String name) {
        return post("/api/repos", new RegisterRepoRequest(path, name, "local"), RepoResponse.class, "register");
    }

    public JsonNode listRepos() {
        return get("/api/repos", JsonNode.class, "list_repos");
    }

    public RunResponse analyze(String repoId) {
        return post(repo(repoId) + "/analyze", Map.of("name_communities", false),
                RunResponse.class, "analyze");
    }

    public RunResponse run(String repoId, String runId) {
        return get(repo(repoId) + "/runs/" + runId, RunResponse.class, "poll_run");
    }

    public JsonNode awaitRun(String repoId, String runId) {
        Instant deadline = Instant.now().plusSeconds(Math.max(1, properties.runTimeoutSeconds()));
        while (Instant.now().isBefore(deadline)) {
            RunResponse current = run(repoId, runId);
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
            try {
                Thread.sleep(Math.max(1, properties.runPollSeconds()) * 1000L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new CodeWikiException("poll_run", "等待 CodeWiki 分析时任务被中断", true, ex);
            }
        }
        throw new CodeWikiException("poll_run", "CodeWiki 分析超时", true, null);
    }

    public JsonNode update(String repoId) {
        return post(repo(repoId) + "/update",
                new UpdateRequest(true, false, false), JsonNode.class, "update");
    }

    public JsonNode buildGraph(String repoId) {
        return post(repo(repoId) + "/graphrag/build",
                new GraphBuildRequest(properties.includeEmbeddings()), JsonNode.class, "graphrag_build");
    }

    public JsonNode retrieve(String repoId, String query, int maxHops) {
        return post(repo(repoId) + "/graphrag/retrieve",
                new RetrieveRequest(query, maxHops, properties.includeEmbeddings()),
                JsonNode.class, "graphrag_retrieve");
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
     * 重试包装：连接断开/EOF 等瞬时错误最多重试 3 次，间隔 5-30 秒递增。
     */
    private <T> T executeWithRetry(java.util.function.Supplier<T> action, String operation) {
        int maxRetries = 3;
        long waitMs = 5000;
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
                try {
                    Thread.sleep(waitMs * (attempt + 1));
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
                              JsonNode stats, JsonNode error) {
        public String resolvedId() {
            return run_id != null && !run_id.isBlank() ? run_id : (id == null ? "" : id);
        }

        public String errorText(String fallback) {
            if (error == null || error.isNull()) return fallback;
            return error.isTextual() ? error.asText(fallback) : error.toString();
        }
    }
    public record UpdateRequest(boolean refresh_chunks, boolean name_communities, boolean regenerate_wiki) {}
    public record GraphBuildRequest(boolean include_embeddings) {}
    public record RetrieveRequest(String query, int max_hops, boolean include_embeddings) {}
}
