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
        String formatInstruction = "排版要求：用换行分段，便于阅读；禁止使用 Markdown 加粗（不要输出 **文字**）；"
                + "需要强调时用两个空格或「」即可，不要用星号、井号标题。";
        String statusInstruction = "若上下文含 knowledge_status 且 knowledgeBuilt=true，或含 community / community_report / entity / graph_explore，"
                + "必须明确回答「知识库已构建」；图社区与节点就是构建结果，不要因为没有「构建日志」就说未构建。";
        String intentInstruction = intent.contains("knowledge_status")
                ? "这是知识库状态问题：只依据 knowledge_status 中的 knowledgeBuilt / nodeCount / chunkCount 回答是否已构建，先给结论再列数字。"
                : intent.contains("branches")
                ? "这是分支问题：只准使用 branch_list 中的 aheadOfDefault/behindDefault/relation/littleUniqueContent 原数字，禁止改写或编造。"
                        + "「最靠前」= ahead 最大；「几乎无独有提交」= ahead=0（littleUniqueContent=true），不要说成业务上「最没用」。"
                        + "不要根据 tipMessage 臆测分支代码内容；CodeWiki 未索引非默认分支。"
                : intent.contains("portfolio")
                ? "这是多仓库问题：必须依据 portfolio 上下文列出具体仓库全名，区分「几乎没有内容/未索引」与「可能落后」。不要说无法判断，除非 portfolio 上下文缺失。"
                : intent.contains("history")
                ? "历史问题必须逐条覆盖上下文中的 commit。emptyChange=true 可视为无文件变更的空合并；是否对当前 HEAD「过时」证据不足时标为无法判断，不要编造。"
                : intent.contains("api")
                ? "接口问题优先依据 entity / community_report / relationship / api_spec 上下文，列出 HTTP 方法、路径、用途和鉴权；不要只列前端函数名。"
                : intent.contains("deployment")
                ? "部署问题只依据实际配置和启动脚本，区分开发启动与生产部署；缺少 Docker 或生产文档时明确指出。"
                : "优先使用标准 GraphRAG Local 上下文（code_window、entity、community_report、relationship）。"
                        + "若存在 code_window，必须直接引用其中的源代码回答「源码/代码/实现」类问题，不要只复述实体元数据；"
                        + "community_report 用于结构概览，不要用它替代源码。";
        String systemPrompt = "你是开源仓库维护助手 RepoPilot。只能根据给定上下文回答，无法确定时明确说明。"
                + "回答使用中文，并引用相关文件路径、符号名、社区名、分支名或 commit SHA。"
                + formatInstruction + statusInstruction + intentInstruction;
        String userPrompt = "查询意图: " + intent + "\n问题类型: " + questionType
                + "\n用户问题: " + question + "\n\n上下文:\n" + contextText
                + "\n\n请直接、完整回答，不要描述自己缺少未要求的数据。";
        try {
            int maxTokens = intent.contains("history") || intent.contains("api")
                    || intent.contains("portfolio") || intent.contains("branches") ? 3000 : 1800;
            return sanitizeAnswer(chatCompletion(systemPrompt, userPrompt, maxTokens));
        } catch (Exception ex) {
            return sanitizeAnswer(buildFallback(question, questionType, contexts, intent) + "\n\n（" + ex.getMessage() + "）");
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
        String content = response == null ? ""
                : response.path("choices").path(0).path("message").path("content").asText("").trim();
        if (content.isBlank()) {
            throw new IllegalStateException(
                    "LLM 返回了空内容（模型: " + llm.model() + "）。请检查模型是否可用，或在设置中更换模型。");
        }
        return content;
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
        return chat(repoId, message, contexts, "code");
    }

    public Map<String, Object> chat(String repoId, String message,
                                    List<Map<String, Object>> contexts, String intent) {
        return chat(repoId, message, contexts, intent, "local", "");
    }

    public Map<String, Object> chat(String repoId, String message,
                                    List<Map<String, Object>> contexts, String intent,
                                    String searchMode, String precomputedAnswer) {
        String questionType = KnowledgeUtils.classifyQuestion(message);
        List<Map<String, Object>> citations = contexts.stream().limit(3)
                .map(c -> Map.<String, Object>of("file", c.get("file"), "line", c.get("line")))
                .toList();
        boolean llmEnabled = configured() && !contexts.isEmpty();
        String answer;
        if (precomputedAnswer != null && !precomputedAnswer.isBlank()) {
            answer = sanitizeAnswer(precomputedAnswer);
            llmEnabled = true;
        } else if (contexts.isEmpty()) {
            answer = "未检索到可用知识库证据。请确认已构建 GraphRAG，或换一种问法后重试。"
                    + "（检测到意图: " + intent + "）";
        } else {
            answer = llmEnabled
                    ? generateAnswer(message, questionType, contexts, intent)
                    : buildFallback(message, questionType, contexts, intent);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", answer);
        result.put("questionType", questionType);
        result.put("citations", citations);
        result.put("llmEnabled", llmEnabled);
        result.put("intent", intent);
        result.put("searchMode", searchMode == null ? "local" : searchMode);
        return result;
    }

    public SseEmitter streamChat(String repoId, String message, String questionType,
                                  String intent, List<Map<String, Object>> contexts) {
        SseEmitter emitter = new SseEmitter(300_000L);
        streamExecutor.execute(() -> {
            try {
                streamInto(emitter, repoId, message, questionType, intent, contexts, "local", "");
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
        streamInto(emitter, repoId, message, questionType, intent, contexts, "local", "");
    }

    public void streamInto(SseEmitter emitter, String repoId, String message, String questionType,
                           String intent, List<Map<String, Object>> contexts,
                           String searchMode, String precomputedAnswer) throws Exception {
        List<Map<String, Object>> citations = contexts.stream().limit(3)
                .map(c -> Map.<String, Object>of("file", c.get("file"), "line", c.get("line")))
                .toList();
        boolean hasGlobalAnswer = precomputedAnswer != null && !precomputedAnswer.isBlank();
        boolean llmEnabled = hasGlobalAnswer || (configured() && !contexts.isEmpty());

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("questionType", questionType);
        meta.put("citations", citations);
        meta.put("llmEnabled", llmEnabled);
        meta.put("intent", intent);
        meta.put("searchMode", searchMode == null ? "local" : searchMode);

        if (hasGlobalAnswer) {
            String answer = sanitizeAnswer(precomputedAnswer);
            emitter.send(SseEmitter.event()
                    .name("meta")
                    .data(mapper.writeValueAsString(meta)));
            // Stream global reduce answer in chunks for SSE UX (no second LLM).
            int chunk = 80;
            for (int i = 0; i < answer.length(); i += chunk) {
                String part = answer.substring(i, Math.min(answer.length(), i + chunk));
                emitter.send(SseEmitter.event()
                        .name("token")
                        .data(mapper.writeValueAsString(Map.of("content", part))));
            }
            emitter.send(SseEmitter.event()
                    .name("done")
                    .data(mapper.writeValueAsString(Map.of("answer", answer))));
            emitter.complete();
            return;
        }

        if (!llmEnabled || contexts.isEmpty()) {
            String fallback = contexts.isEmpty()
                    ? "未检索到可用知识库证据。请确认已构建 GraphRAG，或换一种问法后重试。"
                    : sanitizeAnswer(buildFallback(message, questionType, contexts, intent));
            meta.put("llmEnabled", false);
            emitter.send(SseEmitter.event()
                    .name("meta")
                    .data(mapper.writeValueAsString(meta)));
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
                .data(mapper.writeValueAsString(meta)));
        streamCompletion(emitter, message, questionType, contexts, intent);
        emitter.complete();
    }

    private void streamCompletion(SseEmitter emitter, String question, String questionType,
                                   List<Map<String, Object>> contexts, String intent) throws Exception {
        LlmConfigService.LlmSettings llm = configService.current();
        String contextText = contexts.stream()
                .map(c -> "[" + c.getOrDefault("sourceType", "code") + " | "
                        + c.get("file") + ":" + c.get("line") + "]\n" + c.get("content"))
                .reduce((a, b) -> a + "\n\n" + b)
                .orElse("");

        String formatInstruction = "排版要求：用换行分段；禁止输出 Markdown 加粗（不要用 **文字**）；强调用两个空格或「」。";
        String statusInstruction = "若上下文含 knowledge_status 且 knowledgeBuilt=true，或含 community / community_report / entity / graph_explore，"
                + "必须明确「知识库已构建」；图社区与节点就是构建结果。";
        String intentInstruction = intent.contains("knowledge_status")
                ? "这是知识库状态问题：只依据 knowledge_status 回答是否已构建，先结论再列数字。"
                : intent.contains("branches")
                ? "这是分支问题：只准使用 branch_list 中的 aheadOfDefault/behindDefault/relation/littleUniqueContent 原数字，禁止改写或编造。"
                        + "「最靠前」= ahead 最大；ahead=0 只表示无独有提交，不要说成业务上「最没用」。"
                        + "不要根据 tipMessage 臆测分支代码；CodeWiki 未索引非默认分支。"
                : intent.contains("portfolio")
                ? "这是多仓库问题：必须依据 portfolio 上下文列出具体仓库全名，区分「几乎没有内容/未索引」与「可能落后」。不要说无法判断，除非 portfolio 上下文缺失。"
                : intent.contains("history")
                ? "历史问题必须逐条覆盖上下文中的 commit。emptyChange=true 可视为无文件变更的空合并；证据不足时标为无法判断。"
                : intent.contains("api")
                ? "接口问题优先依据 entity / community_report / relationship 上下文，列出 HTTP 方法、路径、用途和鉴权。"
                : intent.contains("deployment")
                ? "部署问题只依据实际配置和启动脚本，区分开发启动与生产部署。"
                : "优先使用标准 GraphRAG Local 上下文（code_window、entity、community_report、relationship）。"
                        + "若存在 code_window，必须直接引用其中的源代码回答「源码/代码/实现」类问题，不要只复述实体元数据；"
                        + "community_report 用于结构概览，不要用它替代源码。";

        String systemPrompt = "你是开源仓库维护助手 RepoPilot。只能根据给定上下文回答，无法确定时明确说明。"
                + "回答使用中文，并引用相关文件路径、符号名、社区名、分支名或 commit SHA。"
                + formatInstruction + statusInstruction + intentInstruction;
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
