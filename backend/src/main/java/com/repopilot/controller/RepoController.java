package com.repopilot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.client.GitHubClient;
import com.repopilot.security.AuthSupport;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/repos")
public class RepoController {

    private final GitHubClient github;

    public RepoController(GitHubClient github) {
        this.github = github;
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
}
