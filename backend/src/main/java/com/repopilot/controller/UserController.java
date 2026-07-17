package com.repopilot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.client.GitHubClient;
import com.repopilot.security.AuthSupport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class UserController {

    private final GitHubClient github;

    public UserController(GitHubClient github) {
        this.github = github;
    }

    @GetMapping({"/api/user/profile", "/api/me"})
    Map<String, Object> profile(@RequestHeader(value = "Authorization", required = false) String authorization) {
        String token = AuthSupport.requireToken(authorization);
        JsonNode user = github.get("/user", token);
        return Map.of(
                "login", user.path("login").asText(),
                "name", user.path("name").isNull() ? null : user.path("name").asText(),
                "avatarUrl", user.path("avatar_url").asText()
        );
    }
}
