package com.repopilot.controller;

import com.repopilot.security.AuthSupport;
import com.repopilot.service.KnowledgeService;
import com.repopilot.service.LlmService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final KnowledgeService knowledgeService;
    private final LlmService llmService;

    public ChatController(KnowledgeService knowledgeService, LlmService llmService) {
        this.knowledgeService = knowledgeService;
        this.llmService = llmService;
    }

    @PostMapping("/chat")
    Map<String, Object> chat(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        AuthSupport.requireToken(authorization);
        String repoId = Objects.toString(body.get("repoId"), "");
        String message = Objects.toString(body.get("message"), "");
        List<Map<String, Object>> contexts = knowledgeService.retrieveChunks(repoId, message, null, 5);
        return llmService.chat(repoId, message, contexts);
    }
}
