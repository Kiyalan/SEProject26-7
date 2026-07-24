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
        return generateAnswer(question, questionType, contexts, "code");
    }

    public String generateAnswer(String question, String questionType,
                                 List<Map<String, Object>> contexts, String intent) {
        if (!configured() || contexts.isEmpty()) {
            return buildFallback(question, questionType, contexts);
        }
        String contextText = contexts.stream()
                .map(c -> "[" + c.getOrDefault("sourceType", "code") + " | "
                        + c.get("file") + ":" + c.get("line") + "]\n" + c.get("content"))
                .reduce((a, b) -> a + "\n\n" + b)
                .orElse("");
        String intentInstruction = intent.contains("history")
                ? "历史问题必须逐条覆盖上下文中的 commit。headAdoption 只表示代码变化是否仍保留，不能据此声称 CI、构建或业务失败；证据不足时标为无法判断。"
                : intent.contains("api")
                ? "接口问题优先依据 api_spec 上下文，列出 HTTP 方法、路径、用途和鉴权；不要只列前端函数名。"
                : intent.contains("deployment")
                ? "部署问题只依据实际配置和启动脚本，区分开发启动与生产部署；缺少 Docker 或生产文档时明确指出。"
                : "优先使用结构化仓库信息和相关源码回答。";
        String systemPrompt = "你是开源仓库维护助手 RepoPilot。只能根据给定上下文回答，无法确定时明确说明。"
                + "回答使用中文，并引用相关文件路径或 commit SHA。" + intentInstruction;
        String userPrompt = "查询意图: " + intent + "\n问题类型: " + questionType
                + "\n用户问题: " + question + "\n\n上下文:\n" + contextText
                + "\n\n请直接、完整回答，不要描述自己缺少未要求的数据。";
        try {
            int maxTokens = intent.contains("history") || intent.contains("api") ? 3000 : 1800;
            return chatCompletion(systemPrompt, userPrompt, maxTokens);
        } catch (Exception ex) {
            return buildFallback(question, questionType, contexts, intent) + "\n\n（" + ex.getMessage() + "）";
        }
    }

    private String chatCompletion(String systemPrompt, String userPrompt) {
        return chatCompletion(systemPrompt, userPrompt, 1200);
    }

    private String chatCompletion(String systemPrompt, String userPrompt, int maxTokens) {
        LlmConfigService.LlmSettings llm = configService.current();
        String apiKey = llm.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("LLM 未配置");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", llm.model());
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        body.put("temperature", 0.2);
        body.put("max_tokens", maxTokens);

        JsonNode response = client.post()
                .uri(llm.baseUrl().replaceAll("/$", "") + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("HTTP-Referer", llm.httpReferer())
                .header("X-Title", llm.appTitle())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        return response.path("choices").path(0).path("message").path("content").asText("");
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
        return chat(repoId, message, contexts, "code");
    }

    public Map<String, Object> chat(String repoId, String message,
                                    List<Map<String, Object>> contexts, String intent) {
        String questionType = KnowledgeUtils.classifyQuestion(message);
        List<Map<String, Object>> citations = contexts.stream().limit(3)
                .map(c -> Map.<String, Object>of("file", c.get("file"), "line", c.get("line")))
                .toList();
        boolean llmEnabled = configured() && !contexts.isEmpty();
        String answer;
        if (contexts.isEmpty()) {
            answer = "未检索到可用知识库证据。请确认已构建 GraphRAG，或换一种问法后重试。"
                    + "（检测到意图: " + intent + "）";
        } else {
            answer = llmEnabled
                    ? generateAnswer(message, questionType, contexts, intent)
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
        SseEmitter emitter = new SseEmitter(300_000L); // 5 分钟超时
        List<Map<String, Object>> citations = contexts.stream().limit(3)
                .map(c -> Map.<String, Object>of("file", c.get("file"), "line", c.get("line")))
                .toList();
        boolean llmEnabled = configured() && !contexts.isEmpty();

        if (!llmEnabled || contexts.isEmpty()) {
            String fallback = contexts.isEmpty()
                    ? "未检索到可用知识库证据。请确认已构建 GraphRAG，或换一种问法后重试。"
                    : buildFallback(message, questionType, contexts, intent);
            try {
                emitter.send(SseEmitter.event()
                        .name("data")
                        .data(mapper.writeValueAsString(Map.of(
                                "content", fallback,
                                "questionType", questionType,
                                "citations", citations,
                                "llmEnabled", false,
                                "intent", intent,
                                "done", true
                        ))));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        streamExecutor.execute(() -> {
            try {
                // 发送元数据
                emitter.send(SseEmitter.event()
                        .name("meta")
                        .data(mapper.writeValueAsString(Map.of(
                                "questionType", questionType,
                                "citations", citations,
                                "llmEnabled", true,
                                "intent", intent
                        ))));
                streamCompletion(emitter, message, questionType, contexts, intent);
                emitter.complete();
            } catch (Exception ex) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(ex.getMessage()));
                } catch (IOException ignored) {}
                emitter.completeWithError(ex);
            }
        });

        return emitter;
    }

    private void streamCompletion(SseEmitter emitter, String question, String questionType,
                                   List<Map<String, Object>> contexts, String intent) throws Exception {
        LlmConfigService.LlmSettings llm = configService.current();
        String contextText = contexts.stream()
                .map(c -> "[" + c.getOrDefault("sourceType", "code") + " | "
                        + c.get("file") + ":" + c.get("line") + "]\n" + c.get("content"))
                .reduce((a, b) -> a + "\n\n" + b)
                .orElse("");

        String intentInstruction = intent.contains("history")
                ? "历史问题必须逐条覆盖上下文中的 commit。证据不足时标为无法判断。"
                : intent.contains("api")
                ? "接口问题优先依据 api_spec 上下文，列出 HTTP 方法、路径、用途和鉴权。"
                : intent.contains("deployment")
                ? "部署问题只依据实际配置和启动脚本，区分开发启动与生产部署。"
                : "优先使用结构化仓库信息和相关源码回答。";

        String systemPrompt = "你是开源仓库维护助手 RepoPilot。只能根据给定上下文回答，无法确定时明确说明。"
                + "回答使用中文，并引用相关文件路径或 commit SHA。" + intentInstruction;
        String userPrompt = "查询意图: " + intent + "\n问题类型: " + questionType
                + "\n用户问题: " + question + "\n\n上下文:\n" + contextText
                + "\n\n请直接、完整回答，不要描述自己缺少未要求的数据。";

        String apiKey = llm.apiKey();
        String baseUrl = llm.baseUrl().replaceAll("/$", "");
        URI uri = URI.create(baseUrl + "/chat/completions");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", llm.model());
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
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
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data("LLM API 返回 " + responseCode));
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
                        JsonNode delta = chunk.path("choices").path(0).path("delta");
                        String content = delta.path("content").asText("");
                        if (!content.isEmpty()) {
                            fullContent.append(content);
                            emitter.send(SseEmitter.event()
                                    .name("token")
                                    .data(mapper.writeValueAsString(Map.of("content", content))));
                        }
                    } catch (Exception ignored) {
                        // 跳过无法解析的行
                    }
                }
            }
            // 发送完成信号
            emitter.send(SseEmitter.event()
                    .name("done")
                    .data(mapper.writeValueAsString(Map.of("answer", fullContent.toString()))));
        } finally {
            conn.disconnect();
        }
    }

}
