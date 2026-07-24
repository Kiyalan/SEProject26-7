package com.repopilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "repopilot")
public record AppProperties(Github github, Llm llm, CodeWiki codewiki) {

    public record Github(String clientId, String clientSecret, String callbackUrl, String frontendUrl) {}

    public record Llm(String apiKey, String baseUrl, String model, String embeddingModel, String httpReferer, String appTitle) {}

    public record CodeWiki(
            String baseUrl,
            String hostRepoRoot,
            String containerRepoRoot,
            boolean includeEmbeddings,
            int connectTimeoutSeconds,
            int readTimeoutSeconds,
            int runPollSeconds,
            int runTimeoutSeconds
    ) {}
}
