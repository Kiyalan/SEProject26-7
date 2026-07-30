package com.repopilot.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.security.AuthSupport;
import com.repopilot.service.KnowledgeBuildTaskService;
import com.repopilot.service.KnowledgeService;
import com.repopilot.service.ProgressService;
import com.repopilot.service.RepoAuthorizationService;
import com.repopilot.util.KnowledgePolicy;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@RestController
@RequestMapping("/api/repos/{repoId}/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final ProgressService progressService;
    private final KnowledgeBuildTaskService taskService;
    private final Executor knowledgeBuildExecutor;
    private final RepoAuthorizationService authorizationService;
    private final ObjectMapper mapper;

    public KnowledgeController(
            KnowledgeService knowledgeService,
            ProgressService progressService,
            KnowledgeBuildTaskService taskService,
            @Qualifier("knowledgeBuildExecutor") Executor knowledgeBuildExecutor,
            RepoAuthorizationService authorizationService,
            ObjectMapper mapper
    ) {
        this.knowledgeService = knowledgeService;
        this.progressService = progressService;
        this.taskService = taskService;
        this.knowledgeBuildExecutor = knowledgeBuildExecutor;
        this.authorizationService = authorizationService;
        this.mapper = mapper;
    }

    @GetMapping("/policy")
    Map<String, Object> knowledgePolicy(@PathVariable String repoId,
                                        @RequestHeader(value = "Authorization", required = false) String authorization) {
        authorize(repoId, authorization);
        return KnowledgePolicy.overview();
    }

    @PostMapping("/build")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> buildKnowledge(
            @PathVariable String repoId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) Map<String, Object> body
    ) {
        String token = AuthSupport.requireToken(authorization);
        String ownerLogin = AuthSupport.requireUsername(authorization);
        authorizationService.requireAccess(repoId, token);
        String progressKey = "knowledge:" + repoId;
        if (progressService.isRunning(progressKey)) {
            List<Map<String, Object>> latest = taskService.list(repoId, 1);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("taskId", latest.isEmpty() ? "" : latest.getFirst().get("taskId"));
            response.put("repoId", repoId);
            response.put("status", "indexing");
            response.put("message", "知识库正在构建中，请稍候");
            response.put("async", true);
            return response;
        }

        boolean indexEachCommit = false;
        int maxCommits = body != null && body.get("maxCommits") instanceof Number n ? n.intValue() : 30;
        @SuppressWarnings("unchecked")
        List<String> commitShas = body != null && body.get("commitShas") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : null;

        String taskId = taskService.create(repoId, "incremental");
        try {
            String capturedOwnerLogin = ownerLogin;
            knowledgeBuildExecutor.execute(() -> {
                try {
                    knowledgeService.buildKnowledge(repoId, capturedOwnerLogin, token, indexEachCommit, maxCommits, commitShas, taskId);
                } catch (Exception ex) {
                    // buildKnowledge 已持久化失败状态和错误明细。
                }
            });
        } catch (RejectedExecutionException ex) {
            taskService.fail(taskId, repoId, "构建队列已满，请稍后重试");
            throw new IllegalStateException("构建队列已满，请稍后重试", ex);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("taskId", taskId);
        response.put("repoId", repoId);
        response.put("status", "indexing");
        response.put("message", "已开始后台构建，请通过进度接口或页面查看状态");
        response.put("async", true);
        return response;
    }

    @GetMapping("/tasks")
    Map<String, Object> buildTasks(
            @PathVariable String repoId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        authorize(repoId, authorization);
        return Map.of("items", taskService.list(repoId, limit));
    }

    @GetMapping("/tasks/{taskId}")
    Map<String, Object> buildTask(
            @PathVariable String repoId,
            @PathVariable String taskId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        authorize(repoId, authorization);
        Map<String, Object> task = taskService.get(taskId);
        if (!repoId.equals(task.get("repoId"))) {
            throw new IllegalArgumentException("构建任务不属于该仓库");
        }
        return task;
    }

    @GetMapping("/tasks/{taskId}/errors")
    Map<String, Object> buildTaskErrors(
            @PathVariable String repoId,
            @PathVariable String taskId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        authorize(repoId, authorization);
        Map<String, Object> task = taskService.get(taskId);
        if (!repoId.equals(task.get("repoId"))) {
            throw new IllegalArgumentException("构建任务不属于该仓库");
        }
        return Map.of("items", taskService.errors(taskId));
    }

    @GetMapping("/quality")
    Map<String, Object> knowledgeQuality(
            @PathVariable String repoId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        authorize(repoId, authorization);
        return taskService.quality(repoId);
    }

    @DeleteMapping
    Map<String, Object> resetKnowledge(
            @PathVariable String repoId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        authorize(repoId, authorization);
        String ownerLogin = AuthSupport.requireUsername(authorization);
        return knowledgeService.resetKnowledge(repoId, ownerLogin);
    }

    @PostMapping("/search")
    Map<String, Object> searchKnowledge(
            @PathVariable String repoId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        authorize(repoId, authorization);
        String ownerLogin = AuthSupport.requireUsername(authorization);
        String query = String.valueOf(body.getOrDefault("query", "")).trim();
        if (query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        String commit = body.get("commitSha") instanceof String value ? value : null;
        int limit = body.get("limit") instanceof Number number ? number.intValue() : 10;
        return Map.of(
                "repoId", repoId,
                "commitSha", knowledgeService.resolveActiveCommitSha(repoId, commit),
                "items", knowledgeService.retrieveChunks(repoId, ownerLogin, query, commit, Math.min(Math.max(limit, 1), 50))
        );
    }

    @GetMapping
    Map<String, Object> getKnowledge(
            @PathVariable String repoId,
            @RequestParam(required = false) String commit,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        authorize(repoId, authorization);
        String ownerLogin = AuthSupport.requireUsername(authorization);
        return knowledgeService.getOverview(repoId, ownerLogin, commit);
    }

    @GetMapping("/commits")
    Map<String, Object> knowledgeCommits(
            @PathVariable String repoId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        authorize(repoId, authorization);
        return Map.of("items", knowledgeService.listIndexedCommits(repoId));
    }

    @GetMapping("/compare")
    Map<String, Object> compareKnowledge(
            @PathVariable String repoId,
            @RequestParam String base,
            @RequestParam String head,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        authorize(repoId, authorization);
        return knowledgeService.compareCommits(repoId, base, head);
    }

    @PutMapping("/settings")
    Map<String, Object> updateKnowledgeSettings(
            @PathVariable String repoId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        authorize(repoId, authorization);
        Boolean indexEachCommit = false;
        Integer maxCommits = body.get("maxCommits") instanceof Number n ? n.intValue() : null;
        String activeCommitSha = body.get("activeCommitSha") instanceof String s ? s : null;
        return knowledgeService.saveSettings(repoId, indexEachCommit, maxCommits, activeCommitSha);
    }

    @GetMapping("/graph/status")
    Map<String, Object> graphStatus(@PathVariable String repoId,
                                    @RequestHeader(value = "Authorization", required = false) String authorization) {
        authorize(repoId, authorization);
        return knowledgeService.graphStatus(repoId);
    }

    @GetMapping("/communities")
    Map<String, Object> communities(@PathVariable String repoId,
                                    @RequestHeader(value = "Authorization", required = false) String authorization) {
        authorize(repoId, authorization);
        String ownerLogin = AuthSupport.requireUsername(authorization);
        return Map.of("items", knowledgeService.listCommunities(repoId, ownerLogin));
    }

    @GetMapping("/graph")
    Map<String, Object> fullGraph(@PathVariable String repoId,
                                  @RequestHeader(value = "Authorization", required = false) String authorization) {
        authorize(repoId, authorization);
        String ownerLogin = AuthSupport.requireUsername(authorization);
        return knowledgeService.fullGraph(repoId, ownerLogin);
    }

    @GetMapping("/graph/search")
    Map<String, Object> graphSearchQuery(@PathVariable String repoId, @RequestParam Map<String, String> query,
                                         @RequestHeader(value = "Authorization", required = false) String authorization) {
        authorize(repoId, authorization);
        String ownerLogin = AuthSupport.requireUsername(authorization);
        return knowledgeService.graphSearch(repoId, ownerLogin, mapper.valueToTree(query));
    }

    @PostMapping("/wiki/generate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> generateWiki(@PathVariable String repoId,
                                     @RequestBody(required = false) Map<String, Object> body,
                                     @RequestParam(defaultValue = "en") String language,
                                     @RequestHeader(value = "Authorization", required = false) String authorization) {
        authorize(repoId, authorization);
        String ownerLogin = AuthSupport.requireUsername(authorization);
        Map<String, Object> request = new LinkedHashMap<>(body == null ? Map.of() : body);
        request.putIfAbsent("language", language);
        try {
            String capturedOwnerLogin = ownerLogin;
            knowledgeBuildExecutor.execute(() -> {
                try {
                    knowledgeService.generateWiki(repoId, capturedOwnerLogin, mapper.valueToTree(request));
                    knowledgeService.clearWikiError(repoId);
                } catch (Exception ex) {
                    knowledgeService.setWikiError(repoId, rootMessage(ex));
                }
            });
        } catch (RejectedExecutionException ex) {
            throw new IllegalStateException("Wiki 生成队列已满，请稍后重试", ex);
        }
        return Map.of(
                "status", "queued",
                "language", language,
                "message", "CodeWiki Wiki 已进入后台生成队列"
        );
    }

    @GetMapping("/wiki")
    Map<String, Object> readWiki(@PathVariable String repoId,
                                 @RequestParam(defaultValue = "en") String language,
                                 @RequestHeader(value = "Authorization", required = false) String authorization) {
        authorize(repoId, authorization);
        String ownerLogin = AuthSupport.requireUsername(authorization);
        return knowledgeService.readWiki(repoId, ownerLogin, language);
    }

    private String authorize(String repoId, String authorization) {
        String token = AuthSupport.requireToken(authorization);
        authorizationService.requireAccess(repoId, token);
        return token;
    }

    private static String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
