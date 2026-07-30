package com.repopilot.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthSupportTest {

    @AfterEach
    void cleanup() {
        AuthSupport.setJwtUtil(null);
    }

    @Test
    void requireToken_withNullJwtUtil_extractsBearerToken() {
        AuthSupport.setJwtUtil(null);
        String token = AuthSupport.requireToken("Bearer mytoken123");

        assertThat(token).isEqualTo("mytoken123");
    }

    @Test
    void requireToken_withJwtUtil_extractsGithubToken() {
        JwtUtil jwtUtil = new JwtUtil("test-secret-key-that-is-long-enough", 168);
        String jwt = jwtUtil.createToken("user", "github-token-abc");
        AuthSupport.setJwtUtil(jwtUtil);

        String token = AuthSupport.requireToken("Bearer " + jwt);

        assertThat(token).isEqualTo("github-token-abc");
    }

    @Test
    void requireToken_withNonJwtFormat_returnsOriginal() {
        JwtUtil jwtUtil = new JwtUtil("test-secret-key-that-is-long-enough", 168);
        AuthSupport.setJwtUtil(jwtUtil);

        String token = AuthSupport.requireToken("Bearer old-format-token");

        assertThat(token).isEqualTo("old-format-token");
    }

    @Test
    void requireToken_withoutBearerPrefix_throws() {
        assertThatThrownBy(() -> AuthSupport.requireToken("mytoken"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void requireToken_withEmptyToken_throws() {
        assertThatThrownBy(() -> AuthSupport.requireToken("Bearer "))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void requireToken_withNull_throws() {
        assertThatThrownBy(() -> AuthSupport.requireToken(null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void requireUsername_withValidJwt() {
        JwtUtil jwtUtil = new JwtUtil("test-secret-key-that-is-long-enough", 168);
        String jwt = jwtUtil.createToken("testuser", "github-token");
        AuthSupport.setJwtUtil(jwtUtil);

        String username = AuthSupport.requireUsername("Bearer " + jwt);

        assertThat(username).isEqualTo("testuser");
    }

    @Test
    void requireUsername_withoutJwt_throws() {
        assertThatThrownBy(() -> AuthSupport.requireUsername("Bearer old-token"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void resolveUser_withValidJwt_returnsMap() {
        JwtUtil jwtUtil = new JwtUtil("test-secret-key-that-is-long-enough", 168);
        String jwt = jwtUtil.createToken("myuser", "mygithubtoken");
        AuthSupport.setJwtUtil(jwtUtil);

        Map<String, String> result = AuthSupport.resolveUser("Bearer " + jwt);

        assertThat(result.get("username")).isEqualTo("myuser");
        assertThat(result.get("githubToken")).isEqualTo("mygithubtoken");
    }

    @Test
    void resolveUser_withoutJwt_returnsUnknown() {
        JwtUtil jwtUtil = new JwtUtil("test-secret-key-that-is-long-enough", 168);
        AuthSupport.setJwtUtil(jwtUtil);

        Map<String, String> result = AuthSupport.resolveUser("Bearer old-token");

        assertThat(result.get("username")).isEqualTo("unknown");
        assertThat(result.get("githubToken")).isEqualTo("old-token");
    }

    @Test
    void resolveUser_withInvalidJwt_throws() {
        JwtUtil jwtUtil = new JwtUtil("test-secret-key-that-is-long-enough", 168);
        AuthSupport.setJwtUtil(jwtUtil);

        assertThatThrownBy(() -> AuthSupport.resolveUser("Bearer invalid.jwt.here"))
                .isInstanceOf(ResponseStatusException.class);
    }
}
