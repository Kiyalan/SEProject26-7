package com.repopilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.client.GitHubClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.Executor;

@Service
public class GitActionService {

    private static final Pattern BRANCH_PATTERN = Pattern.compile("(?:创建|新建|create)\\s*(?:分支|branch)\\s*[`'\"]?([\\w./-]+)", Pattern.CASE_INSENSITIVE);

    private final GitHubClient github;
    private final KnowledgeService knowledgeService;
    private final KnowledgeBuildTaskService taskService;
    private final Executor knowledgeBuildExecutor;
    private final RepoAuthorizationService authorizationService;

    public GitActionService(GitHubClient github, KnowledgeService knowledgeService,
                            KnowledgeBuildTaskService taskService,
                            @Qualifier("knowledgeBuildExecutor") Executor knowledgeBuildExecutor,
                            RepoAuthorizationService authorizationService) {
        this.github = github;
        this.knowledgeService = knowledgeService;
        this.taskService = taskService;
        this.knowledgeBuildExecutor = knowledgeBuildExecutor;
        this.authorizationService = authorizationService;
    }

    public Map<String, Object> execute(String repoId, String token, String action, Map<String, String> params, String ownerLogin) {
        authorizationService.requireAccess(repoId, token);
        return switch (action) {
            case "sync_knowledge" -> enqueueKnowledgeSync(repoId, token, ownerLogin);
            case "create_branch" -> createBranch(repoId, token, params);
            case "commit_file" -> commitFile(repoId, token, params);
            case "create_pr" -> createPullRequest(repoId, token, params);
            default -> throw new IllegalStateException("未知操作: " + action);
        };
    }

    private Map<String, Object> enqueueKnowledgeSync(String repoId, String token, String ownerLogin) {
        String taskId = taskService.create(repoId, "incremental");
        knowledgeBuildExecutor.execute(() -> {
            try {
                knowledgeService.buildKnowledge(repoId, ownerLogin, token, false, 30, null, taskId);
            } catch (Exception ignored) {
                // KnowledgeService records task failure details.
            }
        });
        return Map.of("taskId", taskId, "repoId", repoId, "status", "queued", "async", true);
    }

    public Map<String, Object> executeNl(String repoId, String token, String command, String ownerLogin) {
        ParsedCommand parsed = parseNl(command);
        if ("unknown".equals(parsed.action())) {
            return Map.of(
                    "success", false,
                    "message", "无法理解该命令。试试：「同步知识库」「创建分支 feature/demo」「提交 README.md：更新说明」"
            );
        }
        try {
            Map<String, Object> result = execute(repoId, token, parsed.action(), parsed.params(), ownerLogin);
            String message = switch (parsed.action()) {
                case "sync_knowledge" -> "知识库同步任务已提交";
                case "create_branch" -> "分支 " + parsed.params().getOrDefault("branch", "") + " 已创建";
                case "commit_file" -> "已提交 " + parsed.params().getOrDefault("path", "");
                case "create_pr" -> "PR 已创建";
                default -> "操作完成";
            };
            return Map.of("success", true, "action", parsed.action(), "message", message, "result", result);
        } catch (Exception ex) {
            return Map.of("success", false, "message", ex.getMessage());
        }
    }

    private Map<String, Object> createBranch(String repoId, String token, Map<String, String> params) {
        String branch = params.getOrDefault("branch", "");
        if (branch.isBlank()) {
            throw new IllegalStateException("请提供分支名");
        }
        JsonNode repo = github.get("/repositories/" + repoId, token);
        String fullName = repo.path("full_name").asText();
        String base = params.getOrDefault("from", repo.path("default_branch").asText("main"));
        JsonNode ref = github.get("/repos/" + fullName + "/git/ref/heads/" + base, token);
        String sha = ref.path("object").path("sha").asText();
        JsonNode result = github.post("/repos/" + fullName + "/git/refs", token, Map.of("ref", "refs/heads/" + branch, "sha", sha));
        return Map.of("action", "create_branch", "branch", branch, "from", base, "result", result);
    }

    private Map<String, Object> commitFile(String repoId, String token, Map<String, String> params) {
        String path = params.getOrDefault("path", "");
        if (path.isBlank()) {
            throw new IllegalStateException("请提供文件路径");
        }
        String content = params.getOrDefault("content", "Updated via RepoPilot at " + Instant.now());
        String message = params.getOrDefault("message", "Update " + path);
        JsonNode repo = github.get("/repositories/" + repoId, token);
        String fullName = repo.path("full_name").asText();
        String branch = params.getOrDefault("branch", repo.path("default_branch").asText("main"));
        String encoded = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));

        Map<String, Object> body = new HashMap<>();
        body.put("message", message);
        body.put("content", encoded);
        body.put("branch", branch);
        try {
            JsonNode existing = github.getContents(fullName, path, token, branch);
            if (existing.has("sha")) {
                body.put("sha", existing.path("sha").asText());
            }
        } catch (Exception ignored) {
        }

        JsonNode result = github.putContents(fullName, path, token, body);
        return Map.of(
                "action", "commit_file",
                "path", path,
                "branch", branch,
                "commit", result.path("commit").path("sha").asText("")
        );
    }

    private Map<String, Object> createPullRequest(String repoId, String token, Map<String, String> params) {
        String head = params.getOrDefault("head", "");
        if (head.isBlank()) {
            throw new IllegalStateException("请提供源分支名");
        }
        JsonNode repo = github.get("/repositories/" + repoId, token);
        String fullName = repo.path("full_name").asText();
        String base = params.getOrDefault("base", repo.path("default_branch").asText("main"));
        JsonNode result = github.post("/repos/" + fullName + "/pulls", token, Map.of(
                "title", params.getOrDefault("title", "RepoPilot PR"),
                "head", head,
                "base", base,
                "body", params.getOrDefault("body", "Created via RepoPilot.")
        ));
        return Map.of(
                "action", "create_pr",
                "title", params.getOrDefault("title", "RepoPilot PR"),
                "url", result.path("html_url").asText(""),
                "number", result.path("number").asInt(0)
        );
    }

    private ParsedCommand parseNl(String command) {
        String text = command.trim().toLowerCase();
        if (text.contains("同步") || text.contains("索引") || text.contains("sync") || text.contains("重建")) {
            return new ParsedCommand("sync_knowledge", Map.of());
        }
        Matcher branchMatch = BRANCH_PATTERN.matcher(command);
        boolean hasBranchPattern = branchMatch.find();
        if (hasBranchPattern || (text.contains("分支") && text.contains("创建"))) {
            String name = hasBranchPattern ? branchMatch.group(1) : command.replaceAll(".*分支\\s*", "").trim();
            if (name.isBlank()) {
                name = "repopilot-" + Instant.now().getEpochSecond();
            }
            return new ParsedCommand("create_branch", Map.of("branch", name));
        }
        if (text.contains("提交") || text.contains("commit") || text.contains("push")) {
            Matcher pathMatch = Pattern.compile("([\\w./-]+\\.\\w+)").matcher(command);
            String path = pathMatch.find() ? pathMatch.group(1) : "README.md";
            Matcher contentMatch = Pattern.compile("[:：]\\s*(.+)$", Pattern.DOTALL).matcher(command);
            String content = contentMatch.find() ? contentMatch.group(1).trim() : "Updated via RepoPilot";
            return new ParsedCommand("commit_file", Map.of("path", path, "content", content, "message", "Update " + path));
        }
        if (text.contains("pr") || text.contains("pull request")) {
            String title = command.replaceAll("(?i).*(?:pr|pull request)[:：\\s]*", "").trim();
            if (title.isBlank()) {
                title = "RepoPilot automated PR";
            }
            return new ParsedCommand("create_pr", Map.of("title", title, "head", "", "body", "Created via RepoPilot."));
        }
        return new ParsedCommand("unknown", Map.of());
    }

    private record ParsedCommand(String action, Map<String, String> params) {}
}
