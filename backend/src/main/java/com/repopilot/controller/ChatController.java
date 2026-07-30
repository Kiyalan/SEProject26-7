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
        String mode = Objects.toString(body.get("mode"), "auto").trim();
        if (message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        if (message.length() > 2000) {
            throw new IllegalArgumentException("message 最长 2000 字符");
        }
        KnowledgeQueryService.QueryResult query =
                queryService.retrieve(repoId, message, ownerLogin, token, mode);
        Map<String, Object> result = llmService.chat(repoId, message, query.contexts(), query.intent(),
                query.searchMode(), query.precomputedAnswer());
        return result;
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
        String mode = Objects.toString(body.get("mode"), "auto").trim();
        if (message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        if (message.length() > 2000) {
            throw new IllegalArgumentException("message 最长 2000 字符");
        }

        SseEmitter emitter = new SseEmitter(300_000L);
        String questionType = KnowledgeUtils.classifyQuestion(message);
        chatExecutor.execute(() -> {
            try {
                String statusHint = "global".equalsIgnoreCase(mode)
                        ? "正在执行 Global Search（动态社区选择 + Map-Reduce）…"
                        : "local".equalsIgnoreCase(mode)
                        ? "正在执行 Local Search（实体向量检索）…"
                        : "正在检索标准 GraphRAG 上下文…";
                llmService.sendStatus(emitter, statusHint);
                KnowledgeQueryService.QueryResult query =
                        queryService.retrieve(repoId, message, ownerLogin, token, mode);
                if ("global".equals(query.searchMode())) {
                    llmService.sendStatus(emitter, "Global Search 完成，正在输出回答…");
                } else {
                    llmService.sendStatus(emitter, "Local Search 完成，正在生成回答…");
                }
                llmService.streamInto(emitter, repoId, message, questionType, query.intent(),
                        query.contexts(), query.searchMode(), query.precomputedAnswer());
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
}
