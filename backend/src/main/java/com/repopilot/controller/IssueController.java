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
            @RequestParam(defaultValue = "1") int page
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
        List<Map<String, Object>> items = new ArrayList<>();
        if (issues.isArray()) {
            for (JsonNode issue : issues) {
                if (issue.has("pull_request")) {
                    continue;
                }
                items.add(github.formatIssue(issue, repoId));
            }
        }
        issueService.onIssuesLoaded(repoId, items, token, ownerLogin);
        return Map.of(
                "items", items,
                "total", items.size(),
                "repoFullName", fullName,
                "openIssuesCount", repo.path("open_issues_count").asInt(0),
                "state", state
        );
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

}
