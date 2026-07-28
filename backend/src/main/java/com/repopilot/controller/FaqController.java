package com.repopilot.controller;

import com.repopilot.security.AuthSupport;
import com.repopilot.service.FaqService;
import com.repopilot.service.RepoAuthorizationService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/repos/{repoId}/faq")
public class FaqController {

    private final FaqService faqService;
    private final RepoAuthorizationService authorizationService;

    public FaqController(FaqService faqService, RepoAuthorizationService authorizationService) {
        this.faqService = faqService;
        this.authorizationService = authorizationService;
    }

    @GetMapping
    Map<String, Object> list(
            @PathVariable String repoId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        String ownerLogin = AuthSupport.requireUsername(authorization);
        authorize(repoId, authorization);
        return faqService.list(repoId);
    }

    @PostMapping("/generate")
    Map<String, Object> generate(
            @PathVariable String repoId,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        String ownerLogin = AuthSupport.requireUsername(authorization);
        authorize(repoId, authorization);
        int maxItems = body != null && body.get("maxItems") instanceof Number n ? n.intValue() : 12;
        return faqService.generate(repoId, ownerLogin, maxItems);
    }

    @GetMapping("/export")
    Map<String, Object> export(
            @PathVariable String repoId,
            @RequestParam(defaultValue = "markdown") String format,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        String ownerLogin = AuthSupport.requireUsername(authorization);
        authorize(repoId, authorization);
        return faqService.export(repoId, format);
    }

    private void authorize(String repoId, String authorization) {
        String token = AuthSupport.requireToken(authorization);
        authorizationService.requireAccess(repoId, token);
    }
}
