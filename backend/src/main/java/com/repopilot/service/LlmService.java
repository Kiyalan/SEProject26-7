package com.repopilot.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.util.KnowledgeUtils;

@Service
public class LlmService {

    private final LlmConfigService configService;
    private final RestClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();

    public LlmService(LlmConfigService configService) {
        this.configService = configService;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(15));
        factory.setReadTimeout(Duration.ofSeconds(120));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public boolean configured() {
        String key = configService.current().apiKey();
        return key != null && !key.isBlank();
    }

    public String model() {
        return configService.current().model();
    }

    public String embeddingModel() {
        return configService.current().embeddingModel();
    }

    public String baseUrl() {
        return configService.current().baseUrl();
    }

    public String providerLabel() {
        return configService.providerLabel(baseUrl());
    }

    public Map<String, Object> config() {
        return configService.publicView();
    }

    public Map<String, Object> contractConfig() {
        return configService.contractView();
    }

    public Map<String, Object> updateConfig(Map<String, Object> body) {
        return configService.update(body);
    }

    public Map<String, Object> updateContractConfig(Map<String, Object> body) {
        return configService.updateContract(body);
    }

    public Map<String, Object> testConnection() {
        if (!configured()) {
            throw new IllegalStateException("请先填写 LLM API Key");
        }
        String answer = chatCompletion(
                "你是连通性测试助手。",
                "请只回复 OK 两个字母，不要输出其它内容。"
        );
        String normalized = answer == null ? "" : answer.trim();
        boolean ok = normalized.equalsIgnoreCase("ok") || !normalized.isBlank();
        return Map.of(
                "success", ok,
                "message", ok ? "LLM 连接成功" : "LLM 已响应，但返回内容异常",
                "sample", normalized.length() > 120 ? normalized.substring(0, 120) : normalized
        );
    }

    public String generateAnswer(String question, String questionType, List<Map<String, Object>> contexts) {
        return generateAnswer(question, questionType, contexts, "code", List.of());
    }

    public String generateAnswer(String question, String questionType,
                                 List<Map<String, Object>> contexts, String intent) {
        return generateAnswer(question, questionType, contexts, intent, List.of());
    }

    public String generateAnswer(String question, String questionType,
                                 List<Map<String, Object>> contexts, String intent,
                                 List<String> priorUserMessages) {
        if (!configured() || contexts.isEmpty()) {
            return buildFallback(question, questionType, contexts);
        }
        PromptPair prompts = buildAnswerPrompts(question, contexts, intent, priorUserMessages);
        try {
            int maxTokens = intent.contains("history") || intent.contains("api")
                    || intent.contains("portfolio") || intent.contains("branches")
                    || intent.contains("overview") ? 3000 : 1800;
            return sanitizeAnswer(chatCompletion(prompts.system(), prompts.user(), maxTokens));
        } catch (Exception ex) {
            return sanitizeAnswer(buildFallback(question, questionType, contexts, intent) + "\n\n（" + ex.getMessage() + "）");
        }
    }

    /** Shared prompts for sync + streaming chat. Always includes the full original question. */
    private PromptPair buildAnswerPrompts(String question, List<Map<String, Object>> contexts, String intent,
                                          List<String> priorUserMessages) {
        String contextText = formatContextsForPrompt(contexts);
        String system = "你是仓库助手 RepoPilot。根据参考资料直接回答用户问题。"
                + "必须围绕用户问题的完整原文作答：先给结论，再列关键要点。"
                + "只用资料中的事实；不确定就明确说不确定，不要编造。"
                + "优先依据 GraphRAG 检索到的源码片段作答；社区摘要只用于模块定位。"
                + "仅当片段不足以列出签名时，才使用已附带的少量目标文件源码/API 清单。"
                + "列出类/方法时，只能依据对应源码片段或文件中真实出现的签名；禁止把单元测试方法名当成业务 API。"
                + "用中文分段表述；不要使用 Markdown 加粗；不要复述资料编号、内部标签或系统实现细节。"
                + briefIntentHint(intent);
        StringBuilder user = new StringBuilder();
        user.append("用户问题（完整原文）：\n").append(question).append("\n\n");
        if (priorUserMessages != null && !priorUserMessages.isEmpty()) {
            user.append("对话上文（仅用于理解「该类/它/上述」等指代）：\n");
            int from = Math.max(0, priorUserMessages.size() - 3);
            for (int i = from; i < priorUserMessages.size(); i++) {
                user.append("- ").append(priorUserMessages.get(i)).append('\n');
            }
            user.append('\n');
        }
        user.append("参考资料：\n").append(contextText)
                .append("\n\n请直接回答上面的用户问题，只输出答案正文。");
        return new PromptPair(system, user.toString());
    }

    private static String formatContextsForPrompt(List<Map<String, Object>> contexts) {
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (Map<String, Object> c : contexts) {
            String label = String.valueOf(c.getOrDefault("file", "资料")).trim();
            if (label.startsWith("codewiki/")) {
                label = label.substring("codewiki/".length());
            }
            if (label.startsWith("knowledge/")) {
                label = label.substring("knowledge/".length());
            }
            String content = String.valueOf(c.getOrDefault("content", "")).trim();
            if (content.isBlank()) {
                continue;
            }
            sb.append(index++).append(". ").append(label).append('\n').append(content).append("\n\n");
        }
        return sb.toString().trim();
    }

    private static String briefIntentHint(String intent) {
        if (intent == null || intent.isBlank()) {
            return "";
        }
        if (intent.contains("overview")) {
            return "这是项目概览类问题：先用 GraphRAG 社区概括模块地图，再引用 README/关键源码说明目的与主链路；不要只堆社区名称而不解释。";
        }
        if (intent.contains("knowledge_status")) {
            return "这是知识库是否就绪的问题：直接给出是否已构建及简要规模。";
        }
        if (intent.contains("branches")) {
            return "这是分支问题：只用资料中的 ahead/behind 等对比数字，勿臆测未索引分支的代码内容。";
        }
        if (intent.contains("portfolio")) {
            return "这是多仓库问题：列出具体仓库名，区分未索引与内容很少。";
        }
        if (intent.contains("history")) {
            return "这是提交历史问题：逐条依据资料中的 commit；证据不足就标明无法判断。";
        }
        if (intent.contains("api")) {
            return "这是接口问题：尽量给出方法、路径与用途；有控制器源码时依据源码。";
        }
        if (intent.contains("deployment")) {
            return "这是启动/部署问题：依据实际配置与脚本，区分开发启动与生产部署。";
        }
        return "若资料含「公开 API 清单」或类源码正文，列方法时必须完整覆盖清单；"
                + "禁止只根据其他类的调用点拼凑方法子集，也禁止把 *Test 方法当成业务 API。"
                + "不得声称「无法查看源码」——若资料中已有源码/清单，直接据此作答。";
    }

    private record PromptPair(String system, String user) {}

    private String chatCompletion(String systemPrompt, String userPrompt) {
        return chatCompletion(systemPrompt, userPrompt, 1200);
    }

    private String chatCompletion(String systemPrompt, String userPrompt, int maxTokens) {
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        );
        JsonNode response = chatCompletionRaw(messages, null, false, maxTokens);
        String content = response.path("choices").path(0).path("message").path("content").asText("").trim();
        if (content.isBlank()) {
            LlmConfigService.LlmSettings llm = configService.current();
            throw new IllegalStateException(
                    "LLM 返回了空内容（模型: " + llm.model() + "）。请检查模型是否可用，或在设置中更换模型。");
        }
        return content;
    }

    /**
     * Low-level chat/completions. When {@code tools} is non-null, requests tool calling.
     * Returns the raw JSON response (caller reads message / tool_calls).
     */
    public JsonNode chatCompletionRaw(List<Map<String, Object>> messages,
                                      com.fasterxml.jackson.databind.node.ArrayNode tools,
                                      boolean enableTools, int maxTokens) {
        LlmConfigService.LlmSettings llm = configService.current();
        String apiKey = llm.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("LLM 未配置");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", llm.model());
        body.put("messages", messages);
        body.put("temperature", 0.2);
        body.put("max_tokens", Math.max(256, maxTokens));
        if (enableTools && tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }

        JsonNode response;
        try {
            response = client.post()
                    .uri(llm.baseUrl().replaceAll("/$", "") + "/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("HTTP-Referer", llm.httpReferer())
                    .header("X-Title", llm.appTitle())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(status -> status.isError(), (req, res) -> {
                        String errBody = new String(res.getBody().readAllBytes());
                        throw new IllegalStateException(formatLlmHttpError(res.getStatusCode().value(), errBody, llm.model()));
                    })
                    .body(JsonNode.class);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("LLM 请求失败: " + rootMessage(ex), ex);
        }

        if (response != null && response.hasNonNull("error")) {
            throw new IllegalStateException(formatLlmApiError(response.path("error"), llm.model()));
        }
        if (response == null) {
            throw new IllegalStateException("LLM 返回空响应（模型: " + llm.model() + "）");
        }
        return response;
    }

    private static String formatLlmHttpError(int status, String body, String model) {
        String detail = body == null ? "" : body.trim();
        if (detail.length() > 500) {
            detail = detail.substring(0, 500) + "…";
        }
        if (status == 404 && detail.toLowerCase().contains("unavailable for free")) {
            return "模型 " + model + " 的免费版已下线（HTTP 404）。请在设置中改为可用免费模型，"
                    + "例如 openai/gpt-oss-20b:free。详情: " + detail;
        }
        return "LLM API HTTP " + status + "（模型: " + model + "）: " + detail;
    }

    private static String formatLlmApiError(JsonNode error, String model) {
        String message = error.path("message").asText(error.toString());
        if (message.toLowerCase().contains("unavailable for free")) {
            return "模型 " + model + " 免费版不可用。请改为 openai/gpt-oss-20b:free 或其他可用模型。原始错误: " + message;
        }
        return "LLM 错误（模型: " + model + "）: " + message;
    }

    private static String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public String buildFallback(String question, String questionType, List<Map<String, Object>> contexts) {
        return buildFallback(question, questionType, contexts, "code");
    }

    public String buildFallback(String question, String questionType,
                                List<Map<String, Object>> contexts, String intent) {
        if (contexts.isEmpty()) {
            return "知识库中未找到相关内容。请先在「知识库」页面为该仓库执行索引构建。";
        }
        if (intent.contains("history")) {
            StringBuilder history = new StringBuilder("已索引的 commit 历史如下：");
            for (Map<String, Object> item : contexts) {
                history.append("\n\n### ").append(item.get("symbolName")).append("\n")
                        .append(item.get("content"));
            }
            history.append("\n\n注：留存状态不等同于 CI 或构建成功状态。");
            return history.toString();
        }
        if (intent.contains("api")) {
            StringBuilder endpoints = new StringBuilder("从 OpenAPI/Controller 索引中找到以下接口：");
            for (Map<String, Object> item : contexts.stream().limit(100).toList()) {
                endpoints.append("\n\n- ").append(item.getOrDefault("symbolName", item.get("file")))
                        .append("\n").append(item.get("content"));
            }
            return endpoints.toString();
        }
        String lead = switch (questionType) {
            case "where" -> "根据路径检索，以下位置可能与问题相关：";
            case "how" -> "结合仓库中的代码与文档，可参考以下片段：";
            default -> "根据仓库索引，这个项目主要包含以下内容：";
        };
        StringBuilder sb = new StringBuilder(lead);
        for (Map<String, Object> item : contexts.stream().limit(3).toList()) {
            String preview = item.get("content").toString().replace("\n", " ");
            if (preview.length() > 180) {
                preview = preview.substring(0, 180);
            }
            sb.append("\n- `").append(item.get("file")).append("`（约第 ").append(item.get("line")).append(" 行）：").append(preview);
        }
        sb.append("\n\n如需 LLM 增强回答，请配置 LLM_API_KEY。");
        return sb.toString();
    }

    public Map<String, Object> chat(String repoId, String message, List<Map<String, Object>> contexts) {
        return chat(repoId, message, contexts, "code", List.of());
    }

    public Map<String, Object> chat(String repoId, String message,
                                    List<Map<String, Object>> contexts, String intent) {
        return chat(repoId, message, contexts, intent, List.of());
    }

    public Map<String, Object> chat(String repoId, String message,
                                    List<Map<String, Object>> contexts, String intent,
                                    List<String> priorUserMessages) {
        String questionType = KnowledgeUtils.classifyQuestion(message);
        List<Map<String, Object>> citations = contexts.stream().limit(3)
                .map(c -> Map.<String, Object>of("file", c.get("file"), "line", c.get("line")))
                .toList();
        boolean llmEnabled = configured() && !contexts.isEmpty();
        String answer;
        if (contexts.isEmpty()) {
            answer = "未检索到可用资料。请确认已构建知识库，或换一种问法后重试。";
        } else {
            answer = llmEnabled
                    ? generateAnswer(message, questionType, contexts, intent, priorUserMessages)
                    : buildFallback(message, questionType, contexts, intent);
        }
        return Map.of(
                "answer", answer,
                "questionType", questionType,
                "citations", citations,
                "llmEnabled", llmEnabled,
                "intent", intent
        );
    }

    public SseEmitter streamChat(String repoId, String message, String questionType,
                                  String intent, List<Map<String, Object>> contexts) {
        SseEmitter emitter = new SseEmitter(300_000L);
        streamExecutor.execute(() -> {
            try {
                streamInto(emitter, repoId, message, questionType, intent, contexts, List.of());
            } catch (Exception ex) {
                try {
                    sendError(emitter, ex.getMessage());
                } catch (Exception ignored) {}
                emitter.completeWithError(ex);
            }
        });
        return emitter;
    }

    public void sendStatus(SseEmitter emitter, String message) throws IOException {
        emitter.send(SseEmitter.event()
                .name("status")
                .data(mapper.writeValueAsString(Map.of("message", message))));
    }

    public void sendError(SseEmitter emitter, String message) throws IOException {
        emitter.send(SseEmitter.event()
                .name("error")
                .data(mapper.writeValueAsString(Map.of(
                        "message", message == null || message.isBlank() ? "问答失败" : message))));
    }

    /** Drive an already-opened SSE emitter (retrieve may have run in caller). */
    public void streamInto(SseEmitter emitter, String repoId, String message, String questionType,
                           String intent, List<Map<String, Object>> contexts) throws Exception {
        streamInto(emitter, repoId, message, questionType, intent, contexts, List.of());
    }

    public void streamInto(SseEmitter emitter, String repoId, String message, String questionType,
                           String intent, List<Map<String, Object>> contexts,
                           List<String> priorUserMessages) throws Exception {
        List<Map<String, Object>> citations = contexts.stream().limit(3)
                .map(c -> Map.<String, Object>of("file", c.get("file"), "line", c.get("line")))
                .toList();
        boolean llmEnabled = configured() && !contexts.isEmpty();

        if (!llmEnabled || contexts.isEmpty()) {
            String fallback = contexts.isEmpty()
                    ? "未检索到可用资料。请确认已构建知识库，或换一种问法后重试。"
                    : sanitizeAnswer(buildFallback(message, questionType, contexts, intent));
            emitter.send(SseEmitter.event()
                    .name("meta")
                    .data(mapper.writeValueAsString(Map.of(
                            "questionType", questionType,
                            "citations", citations,
                            "llmEnabled", false,
                            "intent", intent
                    ))));
            emitter.send(SseEmitter.event()
                    .name("token")
                    .data(mapper.writeValueAsString(Map.of("content", fallback))));
            emitter.send(SseEmitter.event()
                    .name("done")
                    .data(mapper.writeValueAsString(Map.of("answer", fallback))));
            emitter.complete();
            return;
        }

        emitter.send(SseEmitter.event()
                .name("meta")
                .data(mapper.writeValueAsString(Map.of(
                        "questionType", questionType,
                        "citations", citations,
                        "llmEnabled", true,
                        "intent", intent
                ))));
        streamCompletion(emitter, message, questionType, contexts, intent, priorUserMessages);
        emitter.complete();
    }

    /**
     * Stream answer tokens only (no meta/done/complete). Used after tool-gather so the UI
     * gets real LLM streaming instead of a blocked fake chunk dump.
     */
    public void streamAnswerTokens(SseEmitter emitter, String question, String questionType,
                                   List<Map<String, Object>> contexts, String intent,
                                   List<String> priorUserMessages) throws Exception {
        if (!configured() || contexts == null || contexts.isEmpty()) {
            String fallback = contexts == null || contexts.isEmpty()
                    ? "未检索到可用资料。请确认已构建知识库，或换一种问法后重试。"
                    : sanitizeAnswer(buildFallback(question, questionType, contexts, intent));
            emitter.send(SseEmitter.event()
                    .name("token")
                    .data(mapper.writeValueAsString(Map.of("content", fallback))));
            return;
        }
        streamCompletion(emitter, question, questionType, contexts, intent, priorUserMessages);
    }

    private void streamCompletion(SseEmitter emitter, String question, String questionType,
                                   List<Map<String, Object>> contexts, String intent,
                                   List<String> priorUserMessages) throws Exception {
        LlmConfigService.LlmSettings llm = configService.current();
        PromptPair prompts = buildAnswerPrompts(question, contexts, intent, priorUserMessages);

        String apiKey = llm.apiKey();
        String baseUrl = llm.baseUrl().replaceAll("/$", "");
        URI uri = URI.create(baseUrl + "/chat/completions");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", llm.model());
        body.put("messages", List.of(
                Map.of("role", "system", "content", prompts.system()),
                Map.of("role", "user", "content", prompts.user())
        ));
        body.put("temperature", 0.2);
        body.put("max_tokens", 3000);
        body.put("stream", true);

        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("HTTP-Referer", llm.httpReferer());
        conn.setRequestProperty("X-Title", llm.appTitle());
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(120_000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(mapper.writeValueAsBytes(body));
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            String errBody = "";
            try (BufferedReader errReader = new BufferedReader(new InputStreamReader(
                    conn.getErrorStream() == null ? conn.getInputStream() : conn.getErrorStream()))) {
                StringBuilder sb = new StringBuilder();
                String errLine;
                while ((errLine = errReader.readLine()) != null) {
                    sb.append(errLine);
                }
                errBody = sb.toString();
            } catch (Exception ignored) {
                // keep empty
            }
            sendError(emitter, formatLlmHttpError(responseCode, errBody, llm.model()));
            return;
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            StringBuilder fullContent = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    String data = line.substring(6);
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    try {
                        JsonNode chunk = mapper.readTree(data);
                        if (chunk.has("error")) {
                            sendError(emitter, formatLlmApiError(chunk.path("error"), llm.model()));
                            return;
                        }
                        JsonNode delta = chunk.path("choices").path(0).path("delta");
                        String content = delta.path("content").asText("");
                        if (!content.isEmpty()) {
                            fullContent.append(content);
                            emitter.send(SseEmitter.event()
                                    .name("token")
                                    .data(mapper.writeValueAsString(Map.of("content", content))));
                        }
                    } catch (IOException io) {
                        throw io;
                    } catch (Exception ignored) {
                        // 跳过无法解析的行
                    }
                }
            }
            if (fullContent.isEmpty()) {
                sendError(emitter, "LLM 返回了空内容（模型: " + llm.model()
                        + "）。请确认模型可用，推荐 openai/gpt-oss-20b:free");
                return;
            }
            String cleaned = sanitizeAnswer(fullContent.toString());
            emitter.send(SseEmitter.event()
                    .name("done")
                    .data(mapper.writeValueAsString(Map.of("answer", cleaned))));
        } finally {
            conn.disconnect();
        }
    }

    /** Strip Markdown bold markers; keep newlines for readable plain text. */
    static String sanitizeAnswer(String text) {
        if (text == null || text.isBlank()) {
            return text == null ? "" : text;
        }
        String out = text.replace("**", "");
        // collapse accidental leftover single emphasis runs like *word* used as bold
        out = out.replaceAll("(?m)^#{1,6}\\s+", "");
        return out.trim();
    }

}
