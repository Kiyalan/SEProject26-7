package com.repopilot.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmServiceTest {

    @Test
    void testConnectionFailsClearlyWhenApiKeyMissing() {
        LlmConfigService config = mock(LlmConfigService.class);
        when(config.current()).thenReturn(new LlmConfigService.LlmSettings(
                "", "https://openrouter.ai/api/v1", "openai/gpt-oss-20b:free",
                "openai/text-embedding-3-small", "http://localhost:5173", "RepoPilot"));
        LlmService llm = new LlmService(config);

        assertThat(llm.configured()).isFalse();
        assertThatThrownBy(llm::testConnection)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("API Key");
    }
}
