package com.repopilot.support;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class AuthSupport {

    private AuthSupport() {}

    public static String requireToken(String authorization) {
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
