package com.repopilot.controller;

import com.repopilot.security.AuthSupport;
import com.repopilot.service.KnowledgeService;
import com.repopilot.util.KnowledgePolicy;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/repos/{repoId}/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @GetMapping("/policy")
    Map<String, Object> knowledgePolicy(@RequestHeader(value = "Authorization", required = false) String authorization) {
        AuthSupport.requireToken(authorization);
        return KnowledgePolicy.overview();
    }

    @PostMapping("/build")
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

    @GetMapping
    Map<String, Object> getKnowledge(
            @PathVariable String repoId,
            @RequestParam(required = false) String commit,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        AuthSupport.requireToken(authorization);
        return knowledgeService.getOverview(repoId, commit);
    }

    @GetMapping("/commits")
    Map<String, Object> knowledgeCommits(
            @PathVariable String repoId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        AuthSupport.requireToken(authorization);
        return Map.of("items", knowledgeService.listIndexedCommits(repoId));
    }

    @GetMapping("/compare")
    Map<String, Object> compareKnowledge(
            @PathVariable String repoId,
            @RequestParam String base,
            @RequestParam String head,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        AuthSupport.requireToken(authorization);
        return knowledgeService.compareCommits(repoId, base, head);
    }

    @PutMapping("/settings")
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
}
