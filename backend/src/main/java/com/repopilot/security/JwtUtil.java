package com.repopilot.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

@Component
public class JwtUtil {

    private final SecretKey key;
    private final long expirationMs;

    public JwtUtil(
            @Value("${repopilot.jwt.secret:repopilot-jwt-secret-change-in-production}") String secret,
            @Value("${repopilot.jwt.expiration-hours:168}") long expirationHours
    ) {
        // JJWT 要求 key 至少 256 bits
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, Math.min(keyBytes.length, 32));
            keyBytes = padded;
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.expirationMs = expirationHours * 3600_000L;
    }

    public String createToken(String username, String githubToken) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .claim("github_token", githubToken)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationMs))
                .signWith(key)
                .compact();
    }

    public Map<String, String> resolve(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return Map.of(
                "username", claims.getSubject(),
                "githubToken", claims.get("github_token", String.class)
        );
    }
}
