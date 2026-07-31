package com.repopilot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.client.GitHubClient;
import com.repopilot.security.AuthSupport;
import com.repopilot.service.IssueService;
import com.repopilot.service.RepoAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
public class IssueController {

    private final GitHubClient github;
    private final IssueService issueService;
    private final RepoAuthorizationService authorizationService;

    public IssueController(GitHubClient github, IssueService issueService,
                           RepoAuthorizationService authorizationService) {
        this.github = github;
        this.issueService = issueService;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/api/repos/{repoId}/issues")
    Map<String, Object> listIssues(
            @PathVariable String repoId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "all") String state,
            @RequestParam(name = "per_page", defaultValue = "30") int perPage,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "true") boolean hideGarbled,
            @RequestParam(defaultValue = "true") boolean hideDuplicateTitles
    ) {
        String token = AuthSupport.requireToken(authorization);
        String ownerLogin = AuthSupport.requireUsername(authorization);
        JsonNode repo = github.get("/repositories/" + repoId, token);
        String fullName = repo.path("full_name").asText();
        JsonNode issues = github.get("/repos/" + fullName + "/issues", token, Map.of(
                "state", List.of("open", "closed", "all").contains(state) ? state : "all",
                "per_page", perPage,
                "page", page,
                "sort", "updated",
                "direction", "desc"
        ));
        List<Map<String, Object>> rawItems = new ArrayList<>();
        if (issues.isArray()) {
            for (JsonNode issue : issues) {
                if (issue.has("pull_request")) {
                    continue;
                }
                rawItems.add(github.formatIssue(issue, repoId));
            }
        }
        int rawTotal = rawItems.size();
        List<Map<String, Object>> items = issueService.filterAndAnnotate(rawItems, hideGarbled, hideDuplicateTitles);
        issueService.onIssuesLoaded(repoId, items, token, ownerLogin);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", items.size());
        result.put("rawTotal", rawTotal);
        result.put("filteredOut", Math.max(0, rawTotal - items.size()));
        result.put("repoFullName", fullName);
        result.put("openIssuesCount", repo.path("open_issues_count").asInt(0));
        result.put("state", state);
        result.put("hideGarbled", hideGarbled);
        result.put("hideDuplicateTitles", hideDuplicateTitles);
        result.put("typeLabels", issueService.typeLabels());
        return result;
    }

    @GetMapping("/api/repos/{repoId}/issues/{issueNumber}")
    Map<String, Object> getIssue(
            @PathVariable String repoId,
            @PathVariable int issueNumber,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        String token = AuthSupport.requireToken(authorization);
        JsonNode repo = github.get("/repositories/" + repoId, token);
        String fullName = repo.path("full_name").asText();
        JsonNode issue = github.get("/repos/" + fullName + "/issues/" + issueNumber, token);
        if (issue.has("pull_request")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该编号对应 Pull Request，不是 Issue");
        }
        return github.formatIssue(issue, repoId);
    }

    @PostMapping("/api/issues/analyze")
    Map<String, Object> analyzeIssue(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        String token = AuthSupport.requireToken(authorization);
        String ownerLogin = AuthSupport.requireUsername(authorization);
        String repoId = Objects.toString(body.get("repoId"), "");
        authorizationService.requireAccess(repoId, token);
        @SuppressWarnings("unchecked")
        Map<String, Object> issue = body.get("issue") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
        boolean force = Boolean.TRUE.equals(body.get("force"))
                || "true".equalsIgnoreCase(Objects.toString(body.get("force"), ""));
        return issueService.analyze(repoId, issue, token, force, ownerLogin);
    }

    @GetMapping("/api/repos/{repoId}/issue-analyses")
    Map<String, Object> listAnalyses(
            @PathVariable String repoId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        String token = AuthSupport.requireToken(authorization);
        authorizationService.requireAccess(repoId, token);
        List<Map<String, Object>> items = issueService.listAnalyses(repoId);
        return Map.of("repoId", repoId, "items", items, "total", items.size());
    }

    /** Explicitly post suggested reply to GitHub Issue comments. */
    @PostMapping("/api/issues/reply")
    Map<String, Object> postReply(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        String token = AuthSupport.requireToken(authorization);
        String ownerLogin = AuthSupport.requireUsername(authorization);
        String repoId = Objects.toString(body.get("repoId"), "");
        authorizationService.requireAccess(repoId, token);
        @SuppressWarnings("unchecked")
        Map<String, Object> issue = body.get("issue") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
        return issueService.postSuggestedReply(repoId, issue, token, ownerLogin);
    }

    /** Bulk post replies for analyzed unreplied issues. */
    @PostMapping("/api/issues/reply-all")
    Map<String, Object> postReplyAll(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        String token = AuthSupport.requireToken(authorization);
        String ownerLogin = AuthSupport.requireUsername(authorization);
        String repoId = Objects.toString(body.get("repoId"), "");
        authorizationService.requireAccess(repoId, token);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> issues = body.get("issues") instanceof List<?> list
                ? list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList()
                : List.of();
        return issueService.postRepliesBulk(repoId, issues, token, ownerLogin);
    }

    /** Email maintainer a digest of suggested replies for selected issues. */
    @PostMapping("/api/issues/notify-replies")
    Map<String, Object> notifyReplies(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        String token = AuthSupport.requireToken(authorization);
        String ownerLogin = AuthSupport.requireUsername(authorization);
        String repoId = Objects.toString(body.get("repoId"), "");
        authorizationService.requireAccess(repoId, token);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> issues = body.get("issues") instanceof List<?> list
                ? list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList()
                : List.of();
        if (issues.isEmpty() && body.get("issue") instanceof Map<?, ?> one) {
            issues = List.of((Map<String, Object>) one);
        }
        return issueService.emailReplyDigest(repoId, issues, token, ownerLogin);
    }
}
