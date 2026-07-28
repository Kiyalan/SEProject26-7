package com.repopilot.controller;

import com.repopilot.security.AuthSupport;
import com.repopilot.service.GitActionService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/repos/{repoId}/actions")
public class GitActionController {

    private final GitActionService gitActionService;

    public GitActionController(GitActionService gitActionService) {
        this.gitActionService = gitActionService;
    }

    @PostMapping
    Map<String, Object> runAction(
            @PathVariable String repoId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        String token = AuthSupport.requireToken(authorization);
        String ownerLogin = AuthSupport.requireUsername(authorization);
        String action = Objects.toString(body.get("action"), "");
        @SuppressWarnings("unchecked")
        Map<String, String> params = body.get("params") instanceof Map<?, ?> map
                ? map.entrySet().stream().collect(HashMap::new, (m, e) -> m.put(String.valueOf(e.getKey()), String.valueOf(e.getValue())), HashMap::putAll)
                : Map.of();
        return gitActionService.execute(repoId, token, action, params, ownerLogin);
    }

    @PostMapping("/nl")
    Map<String, Object> runNlAction(
            @PathVariable String repoId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        String token = AuthSupport.requireToken(authorization);
        String ownerLogin = AuthSupport.requireUsername(authorization);
        String command = Objects.toString(body.get("command"), "");
        return gitActionService.executeNl(repoId, token, command, ownerLogin);
    }
}
