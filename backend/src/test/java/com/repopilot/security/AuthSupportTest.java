package com.repopilot.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

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
}
