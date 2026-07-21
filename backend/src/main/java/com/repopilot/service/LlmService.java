package com.repopilot.service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.util.KnowledgeUtils;

@Service
public class LlmService {

    private final LlmConfigService configService;
    private final RestClient client;

    public LlmService(LlmConfigService configService) {
        this.configService = configService;
        this.client = RestClient.builder().build();
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
        if (!configured() || contexts.isEmpty()) {
            return buildFallback(question, questionType, contexts);
        }
        String contextText = contexts.stream()
                .map(c -> "[" + c.get("file") + ":" + c.get("line") + "]\n" + c.get("content"))
                .reduce((a, b) -> a + "\n\n" + b)
                .orElse("");
        String systemPrompt = "你是开源仓库维护助手 RepoPilot。只能根据给定上下文回答，无法确定时明确说明。回答使用中文，并引用相关文件路径。";
        String userPrompt = "问题类型: " + questionType + "\n用户问题: " + question + "\n\n上下文:\n" + contextText + "\n\n请给出简洁、可执行的回答。";
        try {
            return chatCompletion(systemPrompt, userPrompt);
        } catch (Exception ex) {
            return buildFallback(question, questionType, contexts) + "\n\n（" + ex.getMessage() + "）";
        }
    }

    private String chatCompletion(String systemPrompt, String userPrompt) {
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
        body.put("max_tokens", 1200);

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
        if (contexts.isEmpty()) {
            return "知识库中未找到相关内容。请先在「知识库」页面为该仓库执行索引构建。";
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
        String questionType = KnowledgeUtils.classifyQuestion(message);
        List<Map<String, Object>> citations = contexts.stream().limit(3)
                .map(c -> Map.<String, Object>of("file", c.get("file"), "line", c.get("line")))
                .toList();
        boolean llmEnabled = configured() && !contexts.isEmpty();
        String answer = llmEnabled
                ? generateAnswer(message, questionType, contexts)
                : buildFallback(message, questionType, contexts);
        return Map.of(
                "answer", answer,
                "questionType", questionType,
                "citations", citations,
                "llmEnabled", llmEnabled
        );
    }

    public String summarizeCode(String filePath, String content, String language) {
        if (!configured()) return "";
        int maxLen = 2000;
        String truncated = content.length() > maxLen ? content.substring(0, maxLen) + "\n... (truncated)" : content;
        String prompt = "请用一句简洁的中文（不超过60字）描述以下代码文件的功能和用途：" +
                "\n文件路径：" + filePath + "\n语言：" + language + "\n\n```\n" + truncated + "\n```";
        try {
            return chatCompletion("你是代码分析助手，只输出一句简洁的功能描述，不要解释。", prompt).trim();
        } catch (Exception e) {
            return "";
        }
    }

    public String summarizeModule(String moduleName, List<String> fileSummaries) {
        if (!configured() || fileSummaries.isEmpty()) return "";
        String joined = String.join("\n", fileSummaries.stream().limit(20).toList());
        String prompt = "以下是模块「" + moduleName + "」下各文件的摘要，请用一段中文（不超过200字）概括该模块的整体功能和架构角色：\n\n" + joined;
        try {
            return chatCompletion("你是代码分析助手，请简洁概括模块功能。", prompt).trim();
        } catch (Exception e) {
            return "";
        }
    }

    public List<float[]> embed(List<String> texts) {
        if (!configured() || texts.isEmpty()) return List.of();
        LlmConfigService.LlmSettings llm = configService.current();
        String apiKey = llm.apiKey();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", llm.embeddingModel());
        body.put("input", texts);

        try {
            JsonNode response = client.post()
                    .uri(llm.baseUrl().replaceAll("/$", "") + "/embeddings")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("HTTP-Referer", llm.httpReferer())
                    .header("X-Title", llm.appTitle())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            List<float[]> result = new ArrayList<>();
            for (JsonNode item : response.path("data")) {
                JsonNode emb = item.path("embedding");
                float[] vec = new float[emb.size()];
                for (int i = 0; i < emb.size(); i++) {
                    vec[i] = (float) emb.get(i).asDouble();
                }
                result.add(vec);
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    public static byte[] floatsToBytes(float[] floats) {
        ByteBuffer buffer = ByteBuffer.allocate(floats.length * 4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        for (float f : floats) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }

    public static float[] bytesToFloats(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        float[] floats = new float[bytes.length / 4];
        for (int i = 0; i < floats.length; i++) {
            floats[i] = buffer.getFloat();
        }
        return floats;
    }

    public static double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB) + 1e-10);
    }
}
