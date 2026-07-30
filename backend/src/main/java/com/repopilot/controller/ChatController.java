package com.repopilot.controller;

import com.repopilot.security.AuthSupport;
import com.repopilot.service.KnowledgeQueryService;
import com.repopilot.service.LlmService;
import com.repopilot.service.RepoAuthorizationService;
import com.repopilot.util.KnowledgeUtils;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final KnowledgeQueryService queryService;
    private final LlmService llmService;
    private final RepoAuthorizationService authorizationService;
    private final ExecutorService chatExecutor = Executors.newCachedThreadPool();

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
        String ownerLogin = AuthSupport.requireUsername(authorization);
        String message = Objects.toString(body.get("message"), "").trim();
        if (message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        if (message.length() > 2000) {
            throw new IllegalArgumentException("message 最长 2000 字符");
        }
        List<String> history = priorUserMessages(body);
        KnowledgeQueryService.QueryResult query =
                queryService.retrieve(repoId, message, ownerLogin, token, history);
        return llmService.chat(repoId, message, query.contexts(), query.intent(), history);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter chatStream(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        String token = AuthSupport.requireToken(authorization);
        String repoId = Objects.toString(body.get("repoId"), "");
        authorizationService.requireAccess(repoId, token);
        String ownerLogin = AuthSupport.requireUsername(authorization);
        String message = Objects.toString(body.get("message"), "").trim();
        if (message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        if (message.length() > 2000) {
            throw new IllegalArgumentException("message 最长 2000 字符");
        }
        List<String> history = priorUserMessages(body);

        // Return SSE immediately; retrieve used to block the HTTP thread before any event,
        // which made the UI sit on "responding" with no feedback (often CodeWiki/LLM latency).
        SseEmitter emitter = new SseEmitter(300_000L);
        String questionType = KnowledgeUtils.classifyQuestion(message);
        chatExecutor.execute(() -> {
            try {
                llmService.sendStatus(emitter, "正在检索相关资料…");
                KnowledgeQueryService.QueryResult query =
                        queryService.retrieve(repoId, message, ownerLogin, token, history);
                llmService.sendStatus(emitter, "正在生成回答…");
                llmService.streamInto(emitter, repoId, message, questionType, query.intent(),
                        query.contexts(), history);
            } catch (Exception ex) {
                try {
                    llmService.sendError(emitter, ex.getMessage() == null ? "问答失败" : ex.getMessage());
                } catch (Exception ignored) {
                    // ignore
                }
                emitter.completeWithError(ex);
            }
        });
        return emitter;
    }

    @SuppressWarnings("unchecked")
    private static List<String> priorUserMessages(Map<String, Object> body) {
        Object raw = body.get("history");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            String text = Objects.toString(item, "").trim();
            if (!text.isBlank()) {
                out.add(text.length() > 500 ? text.substring(0, 500) : text);
            }
            if (out.size() >= 5) {
                break;
            }
        }
        return out;
    }
}
