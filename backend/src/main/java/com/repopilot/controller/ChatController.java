package com.repopilot.controller;

import com.repopilot.security.AuthSupport;
import com.repopilot.service.KnowledgeQueryService;
import com.repopilot.service.LlmService;
import com.repopilot.service.RepoAuthorizationService;
import com.repopilot.util.KnowledgeUtils;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final KnowledgeQueryService queryService;
    private final LlmService llmService;
    private final RepoAuthorizationService authorizationService;

    public ChatController(KnowledgeQueryService queryService, LlmService llmService,
                          RepoAuthorizationService authorizationService) {
        this.queryService = queryService;
        this.llmService = llmService;
        this.authorizationService = authorizationService;
    }

    @PostMapping("/chat")
    Map<String, Object> chat(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        String token = AuthSupport.requireToken(authorization);
        String repoId = Objects.toString(body.get("repoId"), "");
        authorizationService.requireAccess(repoId, token);
        String message = Objects.toString(body.get("message"), "").trim();
        if (message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        if (message.length() > 2000) {
            throw new IllegalArgumentException("message 最长 2000 字符");
        }
        KnowledgeQueryService.QueryResult query = queryService.retrieve(repoId, message);
        return llmService.chat(repoId, message, query.contexts(), query.intent());
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter chatStream(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        String token = AuthSupport.requireToken(authorization);
        String repoId = Objects.toString(body.get("repoId"), "");
        authorizationService.requireAccess(repoId, token);
        String message = Objects.toString(body.get("message"), "").trim();
        if (message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        if (message.length() > 2000) {
            throw new IllegalArgumentException("message 最长 2000 字符");
        }
        KnowledgeQueryService.QueryResult query = queryService.retrieve(repoId, message);
        String questionType = KnowledgeUtils.classifyQuestion(message);
        return llmService.streamChat(repoId, message, questionType, query.intent(), query.contexts());
    }
}
