package com.repopilot.service;

import com.repopilot.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmConfigServiceTest {

    private LlmConfigService service;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties(
                new AppProperties.Github("clientId", "clientSecret", "callbackUrl", "frontendUrl"),
                new AppProperties.Llm("sk-test-key", "https://api.openai.com/v1", "gpt-4", "text-embedding-3-small", "", "RepoPilot"),
                new AppProperties.Mail("smtp.test.com", 587, "user", "pass", "from@test.com"),
                new AppProperties.CodeWiki("http://localhost:8080", "/repos", "/repo", true, 10, 30, 5, 300)
        );
        service = new LlmConfigService(props);
    }

    @Test
    void current_returnsSettings() {
        var settings = service.current();

        assertThat(settings).isNotNull();
        assertThat(settings.model()).isEqualTo("gpt-4");
    }

    @Test
    void reload_loadsFromProperties() {
        service.reload();

        var settings = service.current();
        assertThat(settings).isNotNull();
    }

    @Test
    void publicView_containsAllFields() {
        Map<String, Object> view = service.publicView();

        assertThat(view).containsKey("configured");
        assertThat(view).containsKey("model");
        assertThat(view).containsKey("embeddingModel");
        assertThat(view).containsKey("baseUrl");
        assertThat(view).containsKey("provider");
        assertThat(view).containsKey("hasApiKey");
        assertThat(view).containsKey("apiKeyMasked");
        assertThat(view).containsKey("source");
    }

    @Test
    void publicView_hasApiKeyTrue() {
        Map<String, Object> view = service.publicView();

        assertThat(view.get("hasApiKey")).isEqualTo(true);
    }

    @Test
    void contractView_containsApiKey() {
        Map<String, Object> view = service.contractView();

        assertThat(view).containsKey("baseUrl");
        assertThat(view).containsKey("apiKey");
        assertThat(view).containsKey("model");
    }

    @Test
    void providerLabel_openrouter() {
        String label = service.providerLabel("https://openrouter.ai/api/v1");
        assertThat(label).isEqualTo("OpenRouter");
    }

    @Test
    void providerLabel_openai() {
        String label = service.providerLabel("https://api.openai.com/v1");
        assertThat(label).isEqualTo("OpenAI");
    }

    @Test
    void providerLabel_custom() {
        String label = service.providerLabel("https://custom.api.com");
        assertThat(label).isEqualTo("custom");
    }

    @Test
    void providerLabel_null() {
        String label = service.providerLabel(null);
        assertThat(label).isEqualTo("custom");
    }
}
