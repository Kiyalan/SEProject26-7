package com.repopilot.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.dto.response.GithubIssueResponse;
import com.repopilot.dto.response.GithubRepoResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GitHubClient {

    private static final String USER_AGENT = "RepoPilot-Spring/1.0";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient client = RestClient.builder()
            .baseUrl("https://api.github.com")
            .defaultHeader("Accept", "application/vnd.github+json")
            .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
            .defaultHeader("User-Agent", USER_AGENT)
            .build();

    public JsonNode get(String path, String token) {
        return get(path, token, Map.of());
    }

    public JsonNode get(String path, String token, Map<String, ?> params) {
        return exchange("GET", path, token, params, null);
    }

    public JsonNode getContents(String fullName, String filePath, String token, String ref) {
        Map<String, ?> params = ref == null || ref.isBlank() ? Map.of() : Map.of("ref", ref);
        return get("/repos/" + fullName + "/contents/" + encodePath(filePath), token, params);
    }

    public JsonNode post(String path, String token, Object body) {
        return exchange("POST", path, token, Map.of(), body);
    }

    public JsonNode put(String path, String token, Object body) {
        return exchange("PUT", path, token, Map.of(), body);
    }

    public JsonNode putContents(String fullName, String filePath, String token, Object body) {
        return put("/repos/" + fullName + "/contents/" + encodePath(filePath), token, body);
    }

    public Map<String, Object> formatRepo(JsonNode repo) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", repo.path("id").asText());
        map.put("name", repo.path("name").asText(""));
        map.put("fullName", repo.path("full_name").asText(""));
        map.put("description", repo.path("description").isNull() ? "" : repo.path("description").asText(""));
        map.put("stars", repo.path("stargazers_count").asInt(0));
        map.put("openIssues", repo.path("open_issues_count").asInt(0));
        map.put("language", repo.path("language").isNull() ? "—" : repo.path("language").asText("—"));
        map.put("lastSync", LocalDateTime.now(ZoneOffset.UTC).format(TS));
        map.put("syncStatus", "synced");
        map.put("htmlUrl", repo.path("html_url").asText(""));
        map.put("private", repo.path("private").asBoolean(false));
        map.put("defaultBranch", repo.path("default_branch").asText("main"));
        return map;
    }

    public Map<String, Object> formatIssue(JsonNode issue, String repoId) {
        List<String> labels = new ArrayList<>();
        for (JsonNode label : issue.path("labels")) {
            labels.add(label.path("name").asText(""));
        }
        String created = issue.path("created_at").asText("");
        String updated = issue.path("updated_at").asText("");
        JsonNode milestone = issue.path("milestone");
        String milestoneTitle = milestone.isMissingNode() || milestone.isNull() ? "" : milestone.path("title").asText("");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", issue.path("id").asText());
        result.put("repoId", repoId);
        result.put("number", issue.path("number").asInt());
        result.put("title", issue.path("title").asText(""));
        result.put("body", issue.path("body").isNull() ? "" : issue.path("body").asText(""));
        result.put("state", issue.path("state").asText("open"));
        result.put("author", issue.path("user").path("login").asText(""));
        result.put("createdAt", created.length() >= 10 ? created.substring(0, 10) : created);
        result.put("updatedAt", updated.length() >= 10 ? updated.substring(0, 10) : updated);
        result.put("labels", labels);
        result.put("htmlUrl", issue.path("html_url").asText(""));
        result.put("comments", issue.path("comments").asInt(0));
        result.put("milestone", milestoneTitle);
        result.put("project", milestoneTitle);
        return result;
    }

    private JsonNode exchange(String method, String path, String token, Map<String, ?> params, Object body) {
        try {
            RestClient.RequestBodySpec spec = client.method(org.springframework.http.HttpMethod.valueOf(method))
                    .uri(uriBuilder -> {
                        uriBuilder.path(path);
                        params.forEach(uriBuilder::queryParam);
                        return uriBuilder.build();
                    })
                    .header("Authorization", "Bearer " + token);
            RestClient.ResponseSpec responseSpec = body == null
                    ? spec.retrieve()
                    : spec.contentType(MediaType.APPLICATION_JSON).body(body).retrieve();
            JsonNode responseBody = responseSpec
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), this::handleError)
                    .body(JsonNode.class);
            return responseBody == null ? MAPPER.createObjectNode() : responseBody;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub 请求失败: " + ex.getMessage());
        }
    }

    private void handleError(org.springframework.http.HttpRequest request, ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        String message = extractMessage(body, status);
        if (status == 401) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "GitHub token 已失效，请重新登录");
        }
        if (status == 404) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
        }
        if (status == 403) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
    }

    private String extractMessage(String body, int status) {
        try {
            JsonNode node = MAPPER.readTree(body);
            String message = node.path("message").asText("");
            if (!message.isBlank()) {
                return message;
            }
        } catch (Exception ignored) {
        }
        return body.isBlank() ? "GitHub API 错误 (" + status + ")" : body;
    }

    public static String encodePath(String path) {
        return UriUtils.encodePath(path, StandardCharsets.UTF_8);
    }
}
