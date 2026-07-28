package com.repopilot.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.security.AuthSupport;
import com.repopilot.service.KnowledgeService;
import com.repopilot.service.RepoAuthorizationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Compatibility routes for the existing graph contract. KnowledgeController also
 * exposes the /knowledge/graph variants.
 */
@RestController
@RequestMapping("/api/repos/{repoId}/graph")
public class KnowledgeGraphController {
    private final KnowledgeService knowledge;
    private final RepoAuthorizationService authorization;
    private final ObjectMapper mapper;

    public KnowledgeGraphController(KnowledgeService knowledge,
                                    RepoAuthorizationService authorization,
                                    ObjectMapper mapper) {
        this.knowledge = knowledge;
        this.authorization = authorization;
        this.mapper = mapper;
    }

    @GetMapping("/callers")
    Map<String, Object> callers(@PathVariable String repoId, @RequestParam Map<String, String> query,
                                @RequestHeader(value = "Authorization", required = false) String header) {
        authorize(repoId, header);
        String ownerLogin = AuthSupport.requireUsername(header);
        return knowledge.callers(repoId, ownerLogin, mapper.valueToTree(query));
    }

    @GetMapping("/callees")
    Map<String, Object> callees(@PathVariable String repoId, @RequestParam Map<String, String> query,
                                @RequestHeader(value = "Authorization", required = false) String header) {
        authorize(repoId, header);
        String ownerLogin = AuthSupport.requireUsername(header);
        return knowledge.callees(repoId, ownerLogin, mapper.valueToTree(query));
    }

    @GetMapping("/impact")
    Map<String, Object> impact(@PathVariable String repoId, @RequestParam Map<String, String> query,
                               @RequestHeader(value = "Authorization", required = false) String header) {
        authorize(repoId, header);
        String ownerLogin = AuthSupport.requireUsername(header);
        return knowledge.impact(repoId, ownerLogin, mapper.valueToTree(query));
    }

    @PostMapping("/explore")
    Map<String, Object> explore(@PathVariable String repoId, @RequestBody Map<String, Object> body,
                                @RequestHeader(value = "Authorization", required = false) String header) {
        authorize(repoId, header);
        String ownerLogin = AuthSupport.requireUsername(header);
        return knowledge.explore(repoId, ownerLogin, mapper.valueToTree(body));
    }

    @PostMapping("/affected")
    Map<String, Object> affected(@PathVariable String repoId, @RequestBody Map<String, Object> body,
                                 @RequestHeader(value = "Authorization", required = false) String header) {
        authorize(repoId, header);
        String ownerLogin = AuthSupport.requireUsername(header);
        return knowledge.affected(repoId, ownerLogin, mapper.valueToTree(body));
    }

    private void authorize(String repoId, String header) {
        authorization.requireAccess(repoId, AuthSupport.requireToken(header));
    }
}
