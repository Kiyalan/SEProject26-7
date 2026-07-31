package com.repopilot.security;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 安全性测试
 * 
 * 测试用例覆盖:
 * - TC-401: 敏感配置管理
 * - TC-402: GitHub API合规性
 */
class SecurityTest {

    @Test
    void TC401_tokenGeneration_shouldGenerateValidTokens() {
        JwtUtil jwtUtil = new JwtUtil("test-secret-key-for-testing-purposes", 24);
        String token = jwtUtil.createToken("user123", "github-token-xyz");
        
        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).hasSize(3); // JWT format: header.payload.signature
    }

    @Test
    void TC401_tokenGeneration_shouldIncludeUserInfo() {
        JwtUtil jwtUtil = new JwtUtil("test-secret-key-for-testing-purposes", 24);
        String username = "user123";
        String token = jwtUtil.createToken(username, "github-token");
        
        Map<String, String> resolved = jwtUtil.resolve(token);
        assertThat(resolved.get("username")).isEqualTo(username);
    }

    @Test
    void TC401_tokenGeneration_shouldIncludeGithubToken() {
        JwtUtil jwtUtil = new JwtUtil("test-secret-key-for-testing-purposes", 24);
        String githubToken = "gho_specific_token";
        String token = jwtUtil.createToken("user123", githubToken);
        
        Map<String, String> resolved = jwtUtil.resolve(token);
        assertThat(resolved.get("githubToken")).isEqualTo(githubToken);
    }

    @Test
    void TC402_jwt_shouldFollowStandardFormat() {
        JwtUtil jwtUtil = new JwtUtil("test-secret-key-for-testing-purposes", 24);
        String token = jwtUtil.createToken("testUser", "github-token");
        
        // JWT standard: Base64URL(header).Base64URL(payload).Base64URL(signature)
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        
        // Each part should be Base64URL encoded
        for (String part : parts) {
            assertThat(part).matches("[A-Za-z0-9_-]+");
        }
    }

    @Test
    void TC401_passwordHandling_shouldNotExposeInToken() {
        JwtUtil jwtUtil = new JwtUtil("test-secret-key-for-testing-purposes", 24);
        String token = jwtUtil.createToken("userWithPassword", "github-token");
        
        // Token should not contain password-like strings
        assertThat(token).doesNotContain("password");
        assertThat(token).doesNotContain("secret");
    }

    @Test
    void TC401_tokenValidation_shouldRejectInvalidTokens() {
        JwtUtil jwtUtil = new JwtUtil("test-secret-key-for-testing-purposes", 24);
        
        // Invalid tokens should throw exception
        assertThatThrownBy(() -> jwtUtil.resolve("invalid.token.here"))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jwtUtil.resolve("justastring"))
                .isInstanceOf(Exception.class);
    }

    @Test
    void TC402_token_shouldBeSigned() {
        JwtUtil jwtUtil = new JwtUtil("test-secret-key-for-testing-purposes", 24);
        String token = jwtUtil.createToken("user123", "github-token");
        
        // Token should be resolvable (signed correctly)
        Map<String, String> resolved = jwtUtil.resolve(token);
        assertThat(resolved.get("username")).isEqualTo("user123");
    }

    @Test
    void TC401_inputValidation_shouldHandleEmptyInputs() {
        JwtUtil jwtUtil = new JwtUtil("test-secret-key-for-testing-purposes", 24);
        
        // Empty inputs should still produce a token
        String token = jwtUtil.createToken("", "");
        assertThat(token).isNotBlank();
    }

    @Test
    void TC402_tokenGeneration_shouldBeConsistentForSameUser() {
        JwtUtil jwtUtil = new JwtUtil("test-secret-key-for-testing-purposes", 24);
        
        // Both tokens should be valid for same user
        String token1 = jwtUtil.createToken("user123", "token1");
        String token2 = jwtUtil.createToken("user123", "token2");
        
        assertThat(jwtUtil.resolve(token1).get("username")).isEqualTo("user123");
        assertThat(jwtUtil.resolve(token2).get("username")).isEqualTo("user123");
    }
}
