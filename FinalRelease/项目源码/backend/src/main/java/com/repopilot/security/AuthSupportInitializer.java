package com.repopilot.security;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class AuthSupportInitializer {

    private final JwtUtil jwtUtil;

    public AuthSupportInitializer(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @PostConstruct
    void init() {
        AuthSupport.setJwtUtil(jwtUtil);
    }
}
