package com.repopilot.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthSupportTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil("unit-test-jwt-secret-at-least-32-bytes!!", 24);
        AuthSupport.setJwtUtil(jwtUtil);
    }

    @AfterEach
    void tearDown() {
        AuthSupport.setJwtUtil(null);
    }

    // ===== main 上的核心用例 =====

    @Test
    void rejectsMissingAuthorizationHeader() {
        assertThatThrownBy(() -> AuthSupport.requireToken(null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("未登录");
    }

    @Test
    void acceptsValidJwtAndReturnsGithubToken() {
        String jwt = jwtUtil.createToken("alice", "gho_test_token");
        assertThat(AuthSupport.requireToken("Bearer " + jwt)).isEqualTo("gho_test_token");
        assertThat(AuthSupport.requireUsername("Bearer " + jwt)).isEqualTo("alice");
    }

    @Test
    void rejectsExpiredOrTamperedJwtUsernameLookup() {
        assertThatThrownBy(() -> AuthSupport.requireUsername("Bearer not-a-valid.jwt.token"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("无效或已过期");
    }

    // ===== 本分支新增的边界用例 =====

    @Test
    void requireToken_withNullJwtUtil_extractsBearerToken() {
        AuthSupport.setJwtUtil(null);
        String token = AuthSupport.requireToken("Bearer mytoken123");

        assertThat(token).isEqualTo("mytoken123");
    }

    @Test
    void requireToken_withJwtUtil_extractsGithubToken() {
        String jwt = jwtUtil.createToken("user", "github-token-abc");

        String token = AuthSupport.requireToken("Bearer " + jwt);

        assertThat(token).isEqualTo("github-token-abc");
    }

    @Test
    void requireToken_withNonJwtFormat_returnsOriginal() {
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
        String jwt = jwtUtil.createToken("testuser", "github-token");

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
        String jwt = jwtUtil.createToken("myuser", "mygithubtoken");

        Map<String, String> result = AuthSupport.resolveUser("Bearer " + jwt);

        assertThat(result.get("username")).isEqualTo("myuser");
        assertThat(result.get("githubToken")).isEqualTo("mygithubtoken");
    }

    @Test
    void resolveUser_withoutJwt_returnsUnknown() {
        Map<String, String> result = AuthSupport.resolveUser("Bearer old-token");

        assertThat(result.get("username")).isEqualTo("unknown");
        assertThat(result.get("githubToken")).isEqualTo("old-token");
    }

    @Test
    void resolveUser_withInvalidJwt_throws() {
        assertThatThrownBy(() -> AuthSupport.resolveUser("Bearer invalid.jwt.here"))
                .isInstanceOf(ResponseStatusException.class);
    }
}
