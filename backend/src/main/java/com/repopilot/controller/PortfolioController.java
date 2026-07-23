package com.repopilot.controller;

import com.repopilot.security.AuthSupport;
import com.repopilot.service.PortfolioService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/portfolio")
public class PortfolioController {

    private final PortfolioService portfolioService;

    public PortfolioController(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    @GetMapping("/overview")
    Map<String, Object> portfolioOverview(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(name = "max_repos", defaultValue = "50") int maxRepos
    ) {
        String token = AuthSupport.requireToken(authorization);
        String ownerLogin = AuthSupport.requireUsername(authorization);
        return portfolioService.overview(token, ownerLogin, maxRepos);
    }
}
