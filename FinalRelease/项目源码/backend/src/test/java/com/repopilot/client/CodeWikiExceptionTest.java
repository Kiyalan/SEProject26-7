package com.repopilot.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeWikiExceptionTest {

    @Test
    void constructor_setsAllFields() {
        RuntimeException cause = new RuntimeException("original");
        CodeWikiException ex = new CodeWikiException("register", "Failed to register", false, cause);

        assertThat(ex.getMessage()).isEqualTo("Failed to register");
        assertThat(ex.operation()).isEqualTo("register");
        assertThat(ex.retryable()).isFalse();
        assertThat(ex.getCause()).isEqualTo(cause);
    }

    @Test
    void constructor_withNullCause() {
        CodeWikiException ex = new CodeWikiException("health", "Health check failed", true, null);

        assertThat(ex.getMessage()).isEqualTo("Health check failed");
        assertThat(ex.operation()).isEqualTo("health");
        assertThat(ex.retryable()).isTrue();
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void defaultMessage() {
        CodeWikiException ex = new CodeWikiException("analyze", "Analysis failed", false, null);
        assertThat(ex.getMessage()).contains("Analysis failed");
    }
}
