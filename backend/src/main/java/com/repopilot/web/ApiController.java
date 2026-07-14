package com.repopilot.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.action.GitActionService;
import com.repopilot.config.AppProperties;
import com.repopilot.github.GitHubService;
import com.repopilot.issue.IssueService;
import com.repopilot.knowledge.KnowledgePolicy;
import com.repopilot.knowledge.KnowledgeService;
import com.repopilot.llm.LlmService;
import com.repopilot.portfolio.PortfolioService;
import com.repopilot.support.AuthSupport;
import com.repopilot.support.ProgressService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final GitHubService github;
    private final KnowledgeService knowledgeService;
    private final IssueService issueService;
    private final LlmService llmService;
    private final PortfolioService portfolioService;
    private final GitActionService gitActionService;
    private final AppProperties appProperties;
    private final ProgressService progressService;
    private final Instant startedAt = Instant.now();

    public ApiController(
            GitHubService github,
            KnowledgeService knowledgeService,
            IssueService issueService,
            LlmService llmService,
            PortfolioService portfolioService,
            GitActionService gitActionService,
            AppProperties appProperties,
            ProgressService progressService
    ) {
        this.github = github;
        this.knowledgeService = knowledgeService;
        this.issueService = issueService;
        this.llmService = llmService;
        this.portfolioService = portfolioService;
        this.gitActionService = gitActionService;
        this.appProperties = appProperties;
        this.progressService = progressService;
    }

    @GetMapping("/health")
    Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pid", ProcessHandle.current().pid());
        result.put("startedAt", startedAt.toString());
        result.put("llmConfigured", llmService.configured());
        result.put("llmModel", llmService.model());
        result.put("llmProvider", llmService.providerLabel());
        result.put("githubConfigured", isGithubConfigured());
        result.put("githubCallbackUrl", appProperties.github().callbackUrl());
        return result;
    }

    @GetMapping("/config/github")
    Map<String, Object> githubConfig() {
        return Map.of(
                "configured", isGithubConfigured(),
                "callbackUrl", appProperties.github().callbackUrl(),
                "frontendUrl", appProperties.github().frontendUrl(),
                "authorizeUrl", "/auth/github"
        );
    }

    private boolean isGithubConfigured() {
        return appProperties.github().clientId() != null && !appProperties.github().clientId().isBlank()
                && appProperties.github().clientSecret() != null && !appProperties.github().clientSecret().isBlank();
    }

    @GetMapping("/config/llm")
    Map<String, Object> llmConfig() {
        return llmService.config();
    }

    @PutMapping("/config/llm")
    Map<String, Object> updateLlmConfig(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> body
    ) {
        AuthSupport.requireToken(authorization);
        return llmService.updateConfig(body);
    }

    @PostMapping("/config/llm/test")
    Map<String, Object> testLlmConfig(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        AuthSupport.requireToken(authorization);
        return llmService.testConnection();
    }

    @GetMapping("/me")
    Map<String, Object> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        String token = AuthSupport.requireToken(authorization);
        JsonNode user = github.get("/user", token);
        return Map.of(
                "login", user.path("login").asText(),
                "name", user.path("name").isNull() ? null : user.path("name").asText(),
                "avatarUrl", user.path("avatar_url").asText()
        );
    }

    @GetMapping("/repos")
    Map<String, Object> listRepos(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "per_page", defaultValue = "30") int perPage
    ) {
        String token = AuthSupport.requireToken(authorization);
        JsonNode repos = github.get("/user/repos", token, Map.of(
                "visibility", "all",
                "affiliation", "owner,collaborator,organization_member",
                "sort", "updated",
                "per_page", perPage,
                "page", page
        ));
        if (!repos.isArray()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "GitHub 返回格式异常");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        repos.forEach(repo -> items.add(github.formatRepo(repo)));
        return Map.of("items", items, "page", page, "perPage", perPage);
    }

    @GetMapping("/repos/{repoId}")
    Map<String, Object> getRepo(
            @PathVariable String repoId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        String token = AuthSupport.requireToken(authorization);
        return github.formatRepo(github.get("/repositories/" + repoId, token));
    }

    @GetMapping("/repos/{repoId}/issues")
    Map<String, Object> listIssues(
            @PathVariable String repoId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "all") String state,
            @RequestParam(name = "per_page", defaultValue = "30") int perPage,
            @RequestParam(defaultValue = "1") int page
    ) {
        String token = AuthSupport.requireToken(authorization);
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
        issueService.onIssuesLoaded(repoId, items, token);
        return Map.of(
                "items", items,
                "total", items.size(),
                "repoFullName", fullName,
                "openIssuesCount", repo.path("open_issues_count").asInt(0),
                "state", state
        );
    }

    @GetMapping("/repos/{repoId}/issues/{issueNumber}")
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

    @GetMapping("/repos/{repoId}/knowledge/policy")
    Map<String, Object> knowledgePolicy(@RequestHeader(value = "Authorization", required = false) String authorization) {
        AuthSupport.requireToken(authorization);
        return KnowledgePolicy.overview();
    }

    @PostMapping("/repos/{repoId}/knowledge/build")
    Map<String, Object> buildKnowledge(
            @PathVariable String repoId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) Map<String, Object> body
    ) {
        String token = AuthSupport.requireToken(authorization);
        boolean indexEachCommit = body != null && Boolean.TRUE.equals(body.get("indexEachCommit"));
        int maxCommits = body != null && body.get("maxCommits") instanceof Number n ? n.intValue() : 30;
        @SuppressWarnings("unchecked")
        List<String> commitShas = body != null && body.get("commitShas") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : null;
        return knowledgeService.buildKnowledge(repoId, token, indexEachCommit, maxCommits, commitShas);
    }

    @GetMapping("/repos/{repoId}/knowledge")
    Map<String, Object> getKnowledge(
            @PathVariable String repoId,
            @RequestParam(required = false) String commit,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        AuthSupport.requireToken(authorization);
        return knowledgeService.getOverview(repoId, commit);
    }

    @GetMapping("/repos/{repoId}/knowledge/commits")
    Map<String, Object> knowledgeCommits(
            @PathVariable String repoId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        AuthSupport.requireToken(authorization);
        return Map.of("items", knowledgeService.listIndexedCommits(repoId));
    }

    @GetMapping("/repos/{repoId}/knowledge/compare")
    Map<String, Object> compareKnowledge(
            @PathVariable String repoId,
            @RequestParam String base,
            @RequestParam String head,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        AuthSupport.requireToken(authorization);
        return knowledgeService.compareCommits(repoId, base, head);
    }

    @PutMapping("/repos/{repoId}/knowledge/settings")
    Map<String, Object> updateKnowledgeSettings(
            @PathVariable String repoId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        AuthSupport.requireToken(authorization);
        Boolean indexEachCommit = body.get("indexEachCommit") instanceof Boolean b ? b : null;
        Integer maxCommits = body.get("maxCommits") instanceof Number n ? n.intValue() : null;
        String activeCommitSha = body.get("activeCommitSha") instanceof String s ? s : null;
        return knowledgeService.saveSettings(repoId, indexEachCommit, maxCommits, activeCommitSha);
    }

    @PostMapping("/issues/analyze")
    Map<String, Object> analyzeIssue(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        String token = AuthSupport.requireToken(authorization);
        String repoId = Objects.toString(body.get("repoId"), "");
        @SuppressWarnings("unchecked")
        Map<String, Object> issue = body.get("issue") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
        return issueService.analyze(repoId, issue, token);
    }

    @GetMapping("/repos/{repoId}/progress")
    Map<String, Object> repoProgress(@PathVariable String repoId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("knowledge", progressService.snapshot("knowledge:" + repoId));
        result.put("issues", progressService.snapshot("issues:" + repoId));
        return result;
    }

    @GetMapping("/progress")
    Map<String, Object> allProgress() {
        return Map.of(
                "hint", "请使用 GET /api/repos/{repoId}/progress 查询知识库与 Issue 分析进度",
                "fields", List.of("status", "progress", "message", "total", "done")
        );
    }

    @GetMapping("/issues/{issueId}/analysis")
    Map<String, Object> getIssueAnalysis(
            @PathVariable String issueId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        AuthSupport.requireToken(authorization);
        Map<String, Object> result = issueService.getAnalysis(issueId);
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该 Issue 尚未分析");
        }
        return result;
    }

    @PostMapping("/chat")
    Map<String, Object> chat(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        AuthSupport.requireToken(authorization);
        String repoId = Objects.toString(body.get("repoId"), "");
        String message = Objects.toString(body.get("message"), "");
        List<Map<String, Object>> contexts = knowledgeService.retrieveChunks(repoId, message, null, 5);
        return llmService.chat(repoId, message, contexts);
    }

    @GetMapping("/portfolio/overview")
    Map<String, Object> portfolioOverview(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(name = "max_repos", defaultValue = "50") int maxRepos
    ) {
        String token = AuthSupport.requireToken(authorization);
        return portfolioService.overview(token, maxRepos);
    }

    @PostMapping("/repos/{repoId}/actions")
    Map<String, Object> runAction(
            @PathVariable String repoId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        String token = AuthSupport.requireToken(authorization);
        String action = Objects.toString(body.get("action"), "");
        @SuppressWarnings("unchecked")
        Map<String, String> params = body.get("params") instanceof Map<?, ?> map
                ? map.entrySet().stream().collect(HashMap::new, (m, e) -> m.put(String.valueOf(e.getKey()), String.valueOf(e.getValue())), HashMap::putAll)
                : Map.of();
        return gitActionService.execute(repoId, token, action, params);
    }

    @PostMapping("/repos/{repoId}/actions/nl")
    Map<String, Object> runNlAction(
            @PathVariable String repoId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        String token = AuthSupport.requireToken(authorization);
        String command = Objects.toString(body.get("command"), "");
        return gitActionService.executeNl(repoId, token, command);
    }
}
