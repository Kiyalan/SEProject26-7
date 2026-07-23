package com.repopilot.controller;

import com.repopilot.security.AuthSupport;
import com.repopilot.service.KnowledgeBuildTaskService;
import com.repopilot.service.ProgressService;
import com.repopilot.service.RepoAuthorizationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/repos")
public class RepoProgressController {

    private final ProgressService progressService;
    private final KnowledgeBuildTaskService taskService;
    private final RepoAuthorizationService authorizationService;

    public RepoProgressController(ProgressService progressService, KnowledgeBuildTaskService taskService,
                                  RepoAuthorizationService authorizationService) {
        this.progressService = progressService;
        this.taskService = taskService;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/{repoId}/progress")
    Map<String, Object> repoProgress(
            @PathVariable String repoId,
            @org.springframework.web.bind.annotation.RequestHeader(
                    value = "Authorization", required = false) String authorization
    ) {
        String token = AuthSupport.requireToken(authorization);
        String ownerLogin = AuthSupport.requireUsername(authorization);
        authorizationService.requireAccess(repoId, token);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("knowledge", progressService.snapshot("knowledge:" + repoId));
        result.put("issues", progressService.snapshot("issues:" + repoId));
        var tasks = taskService.list(repoId, 1);
        result.put("latestKnowledgeTask", tasks.isEmpty() ? null : tasks.getFirst());
        return result;
    }
}
