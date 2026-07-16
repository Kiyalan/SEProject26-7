package com.repopilot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.client.GitHubClient;
import com.repopilot.security.AuthSupport;
import com.repopilot.service.ProgressService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/repos")
public class RepoController {

    private final GitHubClient github;
    private final ProgressService progressService;

    public RepoController(GitHubClient github, ProgressService progressService) {
        this.github = github;
        this.progressService = progressService;
    }

    @GetMapping
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

    @GetMapping("/{repoId}")
    Map<String, Object> getRepo(
            @PathVariable String repoId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        String token = AuthSupport.requireToken(authorization);
        return github.formatRepo(github.get("/repositories/" + repoId, token));
    }

    @GetMapping("/{repoId}/progress")
    Map<String, Object> repoProgress(@PathVariable String repoId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("knowledge", progressService.snapshot("knowledge:" + repoId));
        result.put("issues", progressService.snapshot("issues:" + repoId));
        return result;
    }
}
