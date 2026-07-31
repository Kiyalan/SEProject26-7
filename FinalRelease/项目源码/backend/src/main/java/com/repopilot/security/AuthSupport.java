package com.repopilot.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

public final class AuthSupport {

    private static volatile JwtUtil jwtUtil;

    private AuthSupport() {}

    /** 由 Spring 在启动时注入（通过 AuthSupportInitializer） */
    public static void setJwtUtil(JwtUtil util) {
        jwtUtil = util;
    }

    /** 提取 token（兼容旧的 GitHub raw token 和新 JWT） */
    public static String requireToken(String authorization) {
        String token = extractBearer(authorization);
        // 如果是 JWT，从中提取 github_token
        if (jwtUtil != null && token.contains(".")) {
            try {
                return jwtUtil.resolve(token).get("githubToken");
            } catch (Exception ignored) {
                // 不是 JWT（可能是旧格式的 GitHub token），直接返回原值
            }
        }
        return token;
    }

    /** 从 token 中提取 GitHub 用户名 */
    public static String requireUsername(String authorization) {
        String token = extractBearer(authorization);
        if (jwtUtil != null && token.contains(".")) {
            try {
                return jwtUtil.resolve(token).get("username");
            } catch (Exception ex) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "token 无效或已过期");
            }
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请重新登录（需要 JWT token）");
    }

    /** 同时提取用户名和 GitHub token */
    public static Map<String, String> resolveUser(String authorization) {
        String token = extractBearer(authorization);
        if (jwtUtil != null && token.contains(".")) {
            try {
                return jwtUtil.resolve(token);
            } catch (Exception ex) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "token 无效或已过期");
            }
        }
        // 兼容旧的 GitHub raw token（用于过渡期）
        return Map.of("username", "unknown", "githubToken", token);
    }

    private static String extractBearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录，请先使用 GitHub 授权");
        }
        String token = authorization.substring(7).trim();
        if (token.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录，请先使用 GitHub 授权");
        }
        return token;
    }
}
