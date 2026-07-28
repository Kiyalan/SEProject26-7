package com.repopilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.client.GitHubClient;
import org.springframework.stereotype.Service;

@Service
public class RepoAuthorizationService {
    private final GitHubClient github;

    public RepoAuthorizationService(GitHubClient github) {
        this.github = github;
    }

    public JsonNode requireAccess(String repoId, String token) {
        JsonNode repo = github.get("/repositories/" + repoId, token);
        if (repo == null || repo.isMissingNode() || repo.path("id").asText("").isBlank()) {
            throw new IllegalArgumentException("无权访问该仓库");
        }
        return repo;
    }
}
