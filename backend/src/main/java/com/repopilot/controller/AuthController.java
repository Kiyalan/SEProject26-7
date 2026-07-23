package com.repopilot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.config.AppProperties;
import com.repopilot.client.GitHubClient;
import com.repopilot.security.JwtUtil;
import com.repopilot.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.view.RedirectView;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@RestController
public class AuthController {

    private final AppProperties properties;
    private final GitHubClient github;
    private final JwtUtil jwtUtil;
    private final UserService userService;
    private final RestClient oauthClient = RestClient.builder().build();
    private final Set<String> oauthStates = ConcurrentHashMap.newKeySet();
    private final SecureRandom random = new SecureRandom();

    public AuthController(AppProperties properties, GitHubClient github, JwtUtil jwtUtil, UserService userService) {
        this.properties = properties;
        this.github = github;
        this.jwtUtil = jwtUtil;
        this.userService = userService;
    }

    @GetMapping("/auth/github")
    RedirectView authGithub() {
        requireConfig();
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(random.generateSeed(12));
        oauthStates.add(state);
        String params = "client_id=" + encode(properties.github().clientId())
                + "&redirect_uri=" + encode(properties.github().callbackUrl())
                + "&scope=read:user%20repo"
                + "&state=" + encode(state);
        return new RedirectView("https://github.com/login/oauth/authorize?" + params);
    }

    @GetMapping("/auth/callback")
    RedirectView authCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error
    ) {
        requireConfig();
        String frontend = properties.github().frontendUrl();
        if (error != null) {
            return new RedirectView(frontend + "/login?error=" + encode(error));
        }
        if (code == null || state == null || !oauthStates.remove(state)) {
            return new RedirectView(frontend + "/login?error=invalid_state");
        }

        JsonNode tokenData;
        try {
            tokenData = oauthClient.post()
                    .uri("https://github.com/login/oauth/access_token")
                    .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "client_id", properties.github().clientId(),
                            "client_secret", properties.github().clientSecret(),
                            "code", code,
                            "redirect_uri", properties.github().callbackUrl()
                    ))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception ex) {
            return new RedirectView(frontend + "/login?error=" + encode("token_exchange_failed"));
        }

        String accessToken = tokenData.path("access_token").asText("");
        if (accessToken.isBlank()) {
            String errorDesc = tokenData.path("error_description").asText("token_exchange_failed");
            return new RedirectView(frontend + "/login?error=" + encode(errorDesc));
        }

        JsonNode user = github.get("/user", accessToken);
        String username = user.path("login").asText("");
        String avatarUrl = user.path("avatar_url").asText("");

        // 写入 app_users 表（首次登录自动创建），被封禁用户会在此处被拦截
        try {
            userService.findOrCreateByGithubLogin(username, avatarUrl);
        } catch (IllegalArgumentException ex) {
            return new RedirectView(frontend + "/login?error=" + encode(ex.getMessage()));
        }

        // 签发 JWT（内含 username + github_token），不再裸传 GitHub token
        String jwt = jwtUtil.createToken(username, accessToken);

        return new RedirectView(frontend + "/oauth/success?access_token=" + encode(jwt) + "&username=" + encode(username));
    }

    private void requireConfig() {
        if (properties.github().clientId() == null || properties.github().clientId().isBlank()
                || properties.github().clientSecret() == null || properties.github().clientSecret().isBlank()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "请先在 backend/.env 中配置 GITHUB_CLIENT_ID 和 GITHUB_CLIENT_SECRET");
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
