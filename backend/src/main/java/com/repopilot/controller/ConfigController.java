package com.repopilot.controller;

import com.repopilot.config.AppProperties;
import com.repopilot.security.AuthSupport;
import com.repopilot.service.LlmService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final LlmService llmService;
    private final AppProperties appProperties;

    public ConfigController(LlmService llmService, AppProperties appProperties) {
        this.llmService = llmService;
        this.appProperties = appProperties;
    }

    @GetMapping("/github")
    Map<String, Object> githubConfig() {
        return Map.of(
                "configured", isGithubConfigured(),
                "callbackUrl", appProperties.github().callbackUrl(),
                "frontendUrl", appProperties.github().frontendUrl(),
                "authorizeUrl", "/auth/github"
        );
    }

    @GetMapping("/llm")
    Map<String, Object> llmConfig() {
        return llmService.config();
    }

    @PutMapping("/llm")
    Map<String, Object> updateLlmConfig(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> body
    ) {
        AuthSupport.requireToken(authorization);
        return llmService.updateConfig(body);
    }

    @PostMapping("/llm/test")
    Map<String, Object> testLlmConfig(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        AuthSupport.requireToken(authorization);
        return llmService.testConnection();
    }

    private boolean isGithubConfigured() {
        return appProperties.github().clientId() != null && !appProperties.github().clientId().isBlank()
                && appProperties.github().clientSecret() != null && !appProperties.github().clientSecret().isBlank();
    }
}
