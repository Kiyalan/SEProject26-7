package com.repopilot.controller;

import com.repopilot.config.AppProperties;
import com.repopilot.service.LlmService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final LlmService llmService;
    private final AppProperties appProperties;
    private final Instant startedAt = Instant.now();

    public HealthController(LlmService llmService, AppProperties appProperties) {
        this.llmService = llmService;
        this.appProperties = appProperties;
    }

    @GetMapping("/health")
    Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pid", ProcessHandle.current().pid());
        result.put("startedAt", startedAt.toString());
        result.put("llmConfigured", llmService.configured());
        result.put("llmModel", llmService.model());
        result.put("llmProvider", llmService.providerLabel());
        result.put("githubConfigured", isGithubConfigured());
        result.put("githubCallbackUrl", appProperties.github().callbackUrl());
        return result;
    }

    @GetMapping("/progress")
    Map<String, Object> allProgress() {
        return Map.of(
                "hint", "请使用 GET /api/repos/{repoId}/progress 查询知识库与 Issue 分析进度",
                "fields", List.of("status", "progress", "message", "total", "done")
        );
    }

    private boolean isGithubConfigured() {
        return appProperties.github().clientId() != null && !appProperties.github().clientId().isBlank()
                && appProperties.github().clientSecret() != null && !appProperties.github().clientSecret().isBlank();
    }
}
