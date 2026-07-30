package com.repopilot.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.repopilot.util.KnowledgeUtils;

/**
 * Community-first chat agent: LLM may call GraphRAG/community/file tools, then answer.
 */
@Service
public class ChatAgentService {

    private static final int MAX_TOOL_ROUNDS = 6;
    private static final int MAX_TOOLS_PER_ROUND = 4;

    private final KnowledgeService knowledgeService;
    private final LlmService llmService;
    private final ObjectMapper mapper;

    public ChatAgentService(KnowledgeService knowledgeService, LlmService llmService, ObjectMapper mapper) {
        this.knowledgeService = knowledgeService;
        this.llmService = llmService;
        this.mapper = mapper;
    }

    public Map<String, Object> chat(String repoId, String ownerLogin, String message,
                                    List<Map<String, Object>> seedContexts, String intent,
                                    List<String> priorUserMessages) {
        String questionType = KnowledgeUtils.classifyQuestion(message);
        List<Map<String, Object>> citations = seedContexts.stream().limit(5)
                .map(c -> Map.<String, Object>of("file", c.get("file"), "line", c.get("line")))
                .toList();
        boolean llmEnabled = llmService.configured() && !seedContexts.isEmpty();
        String answer;
        if (seedContexts.isEmpty()) {
            answer = "未检索到可用资料。请确认已构建知识库，或换一种问法后重试。";
        } else if (!llmEnabled) {
            answer = llmService.generateAnswer(message, questionType, seedContexts, intent, priorUserMessages);
        } else {
            answer = answerWithTools(repoId, ownerLogin, message, seedContexts, intent, priorUserMessages, null);
        }
        return Map.of(
                "answer", answer,
                "questionType", questionType,
                "citations", citations,
                "llmEnabled", llmEnabled,
                "intent", intent
        );
    }

    public void streamInto(SseEmitter emitter, String repoId, String ownerLogin, String message,
                           String questionType, String intent, List<Map<String, Object>> seedContexts,
                           List<String> priorUserMessages) throws Exception {
        List<Map<String, Object>> citations = seedContexts.stream().limit(5)
                .map(c -> Map.<String, Object>of("file", c.get("file"), "line", c.get("line")))
                .toList();
        boolean llmEnabled = llmService.configured() && !seedContexts.isEmpty();
        emitter.send(SseEmitter.event()
                .name("meta")
                .data(mapper.writeValueAsString(Map.of(
                        "questionType", questionType,
                        "citations", citations,
                        "llmEnabled", llmEnabled,
                        "intent", intent
                ))));

        if (!llmEnabled || seedContexts.isEmpty()) {
            String fallback = seedContexts.isEmpty()
                    ? "未检索到可用资料。请确认已构建知识库，或换一种问法后重试。"
                    : llmService.generateAnswer(message, questionType, seedContexts, intent, priorUserMessages);
            emitter.send(SseEmitter.event()
                    .name("token")
                    .data(mapper.writeValueAsString(Map.of("content", fallback))));
            emitter.send(SseEmitter.event()
                    .name("done")
                    .data(mapper.writeValueAsString(Map.of("answer", fallback))));
            emitter.complete();
            return;
        }

        String answer = answerWithTools(repoId, ownerLogin, message, seedContexts, intent, priorUserMessages,
                status -> {
                    try {
                        llmService.sendStatus(emitter, status);
                    } catch (IOException ignored) {
                        // ignore status failures
                    }
                });
        // Stream final answer in small chunks for UI parity with token events
        int step = 48;
        for (int i = 0; i < answer.length(); i += step) {
            String part = answer.substring(i, Math.min(answer.length(), i + step));
            emitter.send(SseEmitter.event()
                    .name("token")
                    .data(mapper.writeValueAsString(Map.of("content", part))));
        }
        emitter.send(SseEmitter.event()
                .name("done")
                .data(mapper.writeValueAsString(Map.of("answer", answer))));
        emitter.complete();
    }

    private String answerWithTools(String repoId, String ownerLogin, String question,
                                   List<Map<String, Object>> seedContexts, String intent,
                                   List<String> priorUserMessages, StatusSink statusSink) {
        List<Map<String, Object>> workingContexts = new ArrayList<>(seedContexts);
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", agentSystemPrompt(intent)));
        messages.add(Map.of("role", "user", "content", agentUserPrompt(question, workingContexts, priorUserMessages)));

        try {
            for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
                if (statusSink != null) {
                    statusSink.send("正在调用工具检索社区/源码（第 " + (round + 1) + " 轮）…");
                }
                JsonNode response = llmService.chatCompletionRaw(messages, chatTools(), true, 1800);
                JsonNode message = response.path("choices").path(0).path("message");
                JsonNode toolCalls = message.path("tool_calls");
                if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                    String content = message.path("content").asText("").trim();
                    if (!content.isBlank()) {
                        return LlmService.sanitizeAnswer(content);
                    }
                    break;
                }

                ObjectNode assistantMsg = mapper.createObjectNode();
                assistantMsg.put("role", "assistant");
                if (message.hasNonNull("content")) {
                    assistantMsg.put("content", message.path("content").asText(""));
                } else {
                    assistantMsg.putNull("content");
                }
                assistantMsg.set("tool_calls", toolCalls);
                @SuppressWarnings("unchecked")
                Map<String, Object> assistantMap = mapper.convertValue(assistantMsg, Map.class);
                messages.add(assistantMap);

                int used = 0;
                for (JsonNode call : toolCalls) {
                    if (used++ >= MAX_TOOLS_PER_ROUND) {
                        break;
                    }
                    String id = call.path("id").asText("tool_" + used);
                    String name = call.path("function").path("name").asText("");
                    String argsJson = call.path("function").path("arguments").asText("{}");
                    if (statusSink != null) {
                        statusSink.send("工具: " + name + " …");
                    }
                    String result = executeTool(repoId, ownerLogin, name, argsJson);
                    if (result.length() > 12_000) {
                        result = result.substring(0, 12_000) + "\n…(truncated)";
                    }
                    // Keep tool outputs in the answer context pool for citations / final fallback.
                    Map<String, Object> ctx = new LinkedHashMap<>();
                    ctx.put("file", "tool/" + name);
                    ctx.put("line", 1);
                    ctx.put("content", result);
                    ctx.put("score", 95);
                    ctx.put("retrievalType", "tool");
                    ctx.put("sourceType", "source_code");
                    workingContexts.add(ctx);

                    Map<String, Object> toolMsg = new LinkedHashMap<>();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", id);
                    toolMsg.put("content", result);
                    messages.add(toolMsg);
                }
            }

            if (statusSink != null) {
                statusSink.send("正在根据社区与源码生成回答…");
            }
            // Final synthesis without tools — grounded on seed + tool results
            return LlmService.sanitizeAnswer(
                    llmService.generateAnswer(question, KnowledgeUtils.classifyQuestion(question),
                            workingContexts, intent, priorUserMessages));
        } catch (Exception ex) {
            // Models without tool support fall back to seeded contexts.
            return LlmService.sanitizeAnswer(
                    llmService.generateAnswer(question, KnowledgeUtils.classifyQuestion(question),
                            seedContexts, intent, priorUserMessages));
        }
    }

    private String executeTool(String repoId, String ownerLogin, String name, String argsJson) {
        try {
            JsonNode args = mapper.readTree(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            return switch (name) {
                case "list_communities" -> knowledgeService.toolListCommunities(repoId);
                case "get_community" -> knowledgeService.toolGetCommunity(repoId,
                        textArg(args, "name_or_id", "name", "id"));
                case "list_files" -> knowledgeService.toolListFiles(repoId);
                case "list_symbols" -> knowledgeService.toolListSymbols(repoId,
                        textArg(args, "file_or_query", "query", "file"));
                case "read_file" -> knowledgeService.toolReadFile(repoId, ownerLogin,
                        textArg(args, "path", "file", "file_path"));
                case "retrieve_code" -> knowledgeService.toolRetrieveCode(repoId,
                        textArg(args, "query", "question", "q"));
                case "explore_graph" -> knowledgeService.toolExploreGraph(repoId,
                        textArg(args, "query", "question", "q"));
                default -> "未知工具: " + name;
            };
        } catch (Exception ex) {
            return "工具执行失败 (" + name + "): " + ex.getMessage();
        }
    }

    private static String textArg(JsonNode args, String... keys) {
        for (String key : keys) {
            String value = args.path(key).asText("").trim();
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String agentSystemPrompt(String intent) {
        return "你是仓库助手 RepoPilot 的检索代理。你可以调用工具，基于 GraphRAG 社区定位模块，再下钻到具体源码/符号。"
                + "策略：先 list_communities 或 get_community 建立地图；再 list_files / list_symbols / read_file / retrieve_code 获取具体方法与实现。"
                + "列出文件/方法时必须来自工具返回或参考资料，禁止编造。"
                + "方法可以尽量列全。最终用中文直接回答用户；不要使用 Markdown 加粗；不要复述工具名或内部标签。"
                + (intent != null && intent.contains("overview")
                ? "这是概览类问题：先用社区概括结构，再引用关键文件。"
                : "若用户问方法/实现，必须 read_file 或 list_symbols 后再答。");
    }

    private static String agentUserPrompt(String question, List<Map<String, Object>> contexts,
                                          List<String> priorUserMessages) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户问题（完整原文）：\n").append(question).append("\n\n");
        if (priorUserMessages != null && !priorUserMessages.isEmpty()) {
            sb.append("对话上文：\n");
            int from = Math.max(0, priorUserMessages.size() - 3);
            for (int i = from; i < priorUserMessages.size(); i++) {
                sb.append("- ").append(priorUserMessages.get(i)).append('\n');
            }
            sb.append('\n');
        }
        sb.append("已预检索的参考资料（可先用，不够再调工具）：\n");
        int i = 1;
        for (Map<String, Object> c : contexts) {
            if (i > 12) {
                break;
            }
            String label = String.valueOf(c.getOrDefault("file", "资料"));
            String content = String.valueOf(c.getOrDefault("content", "")).trim();
            if (content.isBlank()) {
                continue;
            }
            if (content.length() > 1800) {
                content = content.substring(0, 1800) + "\n…(truncated)";
            }
            sb.append(i++).append(". ").append(label).append('\n').append(content).append("\n\n");
        }
        sb.append("请按需调用工具补充社区/源码证据，然后给出最终答案。");
        return sb.toString();
    }

    private ArrayNode chatTools() {
        ArrayNode tools = mapper.createArrayNode();
        tools.add(tool("list_communities", "列出该仓库全部 GraphRAG 社区名称与摘要预览", Map.of()));
        tools.add(tool("get_community", "获取某个社区的完整摘要与 FILES/符号提示",
                Map.of("name_or_id", Map.of("type", "string", "description", "社区名称或 id"))));
        tools.add(tool("list_files", "列出图谱中的仓库文件路径（用于对齐社区与真实文件清单）", Map.of()));
        tools.add(tool("list_symbols", "按文件或关键词列出类/函数/方法符号",
                Map.of("file_or_query", Map.of("type", "string", "description", "文件路径或符号关键词"))));
        tools.add(tool("read_file", "读取仓库本地源码，并尽量抽取公开 API 清单",
                Map.of("path", Map.of("type", "string", "description", "相对仓库根的文件路径"))));
        tools.add(tool("retrieve_code", "GraphRAG 检索相关源码片段",
                Map.of("query", Map.of("type", "string", "description", "检索查询"))));
        tools.add(tool("explore_graph", "图探索：相关文件/节点关系文本",
                Map.of("query", Map.of("type", "string", "description", "探索查询"))));
        return tools;
    }

    private ObjectNode tool(String name, String description, Map<String, Object> properties) {
        ObjectNode fn = mapper.createObjectNode();
        fn.put("name", name);
        fn.put("description", description);
        ObjectNode parameters = mapper.createObjectNode();
        parameters.put("type", "object");
        parameters.set("properties", mapper.valueToTree(properties));
        ArrayNode required = mapper.createArrayNode();
        for (String key : properties.keySet()) {
            required.add(key);
        }
        if (!required.isEmpty()) {
            parameters.set("required", required);
        }
        fn.set("parameters", parameters);
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");
        tool.set("function", fn);
        return tool;
    }

    @FunctionalInterface
    private interface StatusSink {
        void send(String message);
    }
}
