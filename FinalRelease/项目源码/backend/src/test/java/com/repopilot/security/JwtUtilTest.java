package com.repopilot.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtUtilTest {

    @Test
    void createToken_createsValidJwt() {
        JwtUtil util = new JwtUtil("test-secret-key-that-is-long-enough", 168);

        String token = util.createToken("testuser", "github-token-123");

        assertThat(token).isNotNull();
        assertThat(token).contains(".");
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    void resolve_returnsUsernameAndGithubToken() {
        JwtUtil util = new JwtUtil("test-secret-key-that-is-long-enough", 168);
        String token = util.createToken("testuser", "github-token-123");

        var result = util.resolve(token);

        assertThat(result.get("username")).isEqualTo("testuser");
        assertThat(result.get("githubToken")).isEqualTo("github-token-123");
    }

    @Test
    void createToken_shortSecret_padsKey() {
        JwtUtil util = new JwtUtil("short", 168);

        String token = util.createToken("user", "token");

        assertThat(token).isNotNull();
        assertThat(util.resolve(token).get("username")).isEqualTo("user");
    }

    @Test
    void resolve_withInvalidToken_throws() {
        JwtUtil util = new JwtUtil("test-secret-key-that-is-long-enough", 168);

        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> {
            util.resolve("invalid.token.here");
        });
    }
}
