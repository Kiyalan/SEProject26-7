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
 * Chat agent: GraphRAG-seeded answer by default; optional short tool gather for
 * relationships / thin evidence; final answer is always real LLM token streaming.
 */
@Service
public class ChatAgentService {

    private static final int MAX_TOOL_ROUNDS = 2;
    private static final int MAX_TOOLS_PER_ROUND = 3;

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
            List<Map<String, Object>> contexts = maybeGatherTools(
                    repoId, ownerLogin, message, seedContexts, intent, priorUserMessages, null);
            answer = llmService.generateAnswer(message, questionType, contexts, intent, priorUserMessages);
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

        StatusSink status = msg -> {
            try {
                llmService.sendStatus(emitter, msg);
            } catch (IOException ignored) {
                // ignore
            }
        };

        List<Map<String, Object>> contexts = seedContexts;
        if (shouldGatherTools(message, intent, seedContexts)) {
            status.send("正在按需补充图关系/目标文件…");
            contexts = maybeGatherTools(repoId, ownerLogin, message, seedContexts, intent, priorUserMessages, status);
        }

        status.send("正在流式生成回答…");
        // Real LLM token stream (not fake chunking after a long tool block)
        llmService.streamAnswerTokens(emitter, message, questionType, contexts, intent, priorUserMessages);
        emitter.complete();
    }

    private boolean shouldGatherTools(String question, String intent, List<Map<String, Object>> seed) {
        String lower = question == null ? "" : question.toLowerCase();
        if (containsAny(lower, "调用者", "被谁调用", "调用了谁", "依赖", "影响范围", "caller", "callee", "impact",
                "who calls", "depends on")) {
            return true;
        }
        long graphChunks = seed.stream()
                .filter(c -> {
                    String rt = String.valueOf(c.getOrDefault("retrievalType", ""));
                    String st = String.valueOf(c.getOrDefault("sourceType", ""));
                    return "graphrag_chunk".equals(rt) || "graphrag_pack".equals(rt)
                            || ("source_code".equals(st) && !"community".equals(st));
                })
                .count();
        // Thin GraphRAG evidence → allow a short tool round
        return graphChunks < 2;
    }

    private List<Map<String, Object>> maybeGatherTools(String repoId, String ownerLogin, String question,
                                                       List<Map<String, Object>> seedContexts, String intent,
                                                       List<String> priorUserMessages, StatusSink statusSink) {
        if (!shouldGatherTools(question, intent, seedContexts)) {
            return seedContexts;
        }
        List<Map<String, Object>> working = new ArrayList<>(seedContexts);
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", agentSystemPrompt(intent)));
        messages.add(Map.of("role", "user", "content", agentUserPrompt(question, working, priorUserMessages)));

        try {
            for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
                if (statusSink != null) {
                    statusSink.send("工具补充第 " + (round + 1) + " 轮…");
                }
                JsonNode response = llmService.chatCompletionRaw(messages, chatTools(), true, 1200);
                JsonNode message = response.path("choices").path(0).path("message");
                JsonNode toolCalls = message.path("tool_calls");
                if (!toolCalls.isArray() || toolCalls.isEmpty()) {
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
                        statusSink.send("工具: " + name);
                    }
                    String result = executeTool(repoId, ownerLogin, name, argsJson);
                    if (result.length() > 10_000) {
                        result = result.substring(0, 10_000) + "\n…(truncated)";
                    }
                    Map<String, Object> ctx = new LinkedHashMap<>();
                    ctx.put("file", "tool/" + name);
                    ctx.put("line", 1);
                    ctx.put("content", result);
                    ctx.put("score", 90);
                    ctx.put("retrievalType", "tool");
                    ctx.put("sourceType", "source_code");
                    working.add(ctx);

                    Map<String, Object> toolMsg = new LinkedHashMap<>();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", id);
                    toolMsg.put("content", result);
                    messages.add(toolMsg);
                }
            }
        } catch (Exception ignored) {
            return seedContexts;
        }
        return working;
    }

    private String executeTool(String repoId, String ownerLogin, String name, String argsJson) {
        try {
            JsonNode args = mapper.readTree(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            return switch (name) {
                case "list_communities" -> knowledgeService.toolListCommunities(repoId);
                case "get_community" -> knowledgeService.toolGetCommunity(repoId,
                        textArg(args, "name_or_id", "name", "id"));
                case "list_symbols" -> knowledgeService.toolListSymbols(repoId,
                        textArg(args, "file_or_query", "query", "file"));
                case "read_file" -> knowledgeService.toolReadFile(repoId, ownerLogin,
                        textArg(args, "path", "file", "file_path"));
                case "retrieve_code" -> knowledgeService.toolRetrieveCode(repoId,
                        textArg(args, "query", "question", "q"));
                case "explore_graph" -> knowledgeService.toolExploreGraph(repoId,
                        textArg(args, "query", "question", "q"));
                case "graph_callers" -> knowledgeService.toolCallers(repoId,
                        textArg(args, "symbol", "query", "name"));
                case "graph_callees" -> knowledgeService.toolCallees(repoId,
                        textArg(args, "symbol", "query", "name"));
                case "graph_impact" -> knowledgeService.toolImpact(repoId,
                        textArg(args, "symbol", "query", "name"));
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

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static String agentSystemPrompt(String intent) {
        return "你是仓库助手 RepoPilot 的补充检索代理。主证据已来自 GraphRAG retrieve（源码片段）与少量社区摘要。"
                + "仅在证据不足或用户问调用关系/影响范围时使用工具。"
                + "优先 retrieve_code；需要完整方法签名时 read_file 只读 1-3 个目标文件，禁止全量 list_files。"
                + "调用关系用 graph_callers / graph_callees / graph_impact。"
                + "不要编造文件或方法。";
    }

    private static String agentUserPrompt(String question, List<Map<String, Object>> contexts,
                                          List<String> priorUserMessages) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户问题：\n").append(question).append("\n\n");
        if (priorUserMessages != null && !priorUserMessages.isEmpty()) {
            sb.append("对话上文：\n");
            int from = Math.max(0, priorUserMessages.size() - 3);
            for (int i = from; i < priorUserMessages.size(); i++) {
                sb.append("- ").append(priorUserMessages.get(i)).append('\n');
            }
            sb.append('\n');
        }
        sb.append("已有 GraphRAG/社区资料：\n");
        int i = 1;
        for (Map<String, Object> c : contexts) {
            if (i > 10) {
                break;
            }
            String label = String.valueOf(c.getOrDefault("file", "资料"));
            String content = String.valueOf(c.getOrDefault("content", "")).trim();
            if (content.isBlank()) {
                continue;
            }
            if (content.length() > 1500) {
                content = content.substring(0, 1500) + "\n…(truncated)";
            }
            sb.append(i++).append(". ").append(label).append('\n').append(content).append("\n\n");
        }
        sb.append("若以上已够用，不要调用工具；否则只补充必要工具结果。");
        return sb.toString();
    }

    private ArrayNode chatTools() {
        ArrayNode tools = mapper.createArrayNode();
        tools.add(tool("retrieve_code", "GraphRAG 再检索相关源码片段（首选）",
                Map.of("query", Map.of("type", "string", "description", "检索查询"))));
        tools.add(tool("get_community", "获取某个 GraphRAG 社区摘要（定位模块，不代替源码）",
                Map.of("name_or_id", Map.of("type", "string", "description", "社区名称或 id"))));
        tools.add(tool("list_communities", "列出社区名称与摘要预览", Map.of()));
        tools.add(tool("read_file", "读取 1 个目标源文件（仅当片段不够看签名/实现时）",
                Map.of("path", Map.of("type", "string", "description", "相对仓库根的文件路径"))));
        tools.add(tool("list_symbols", "按文件或关键词列出符号",
                Map.of("file_or_query", Map.of("type", "string", "description", "文件路径或符号关键词"))));
        tools.add(tool("explore_graph", "图探索文本",
                Map.of("query", Map.of("type", "string", "description", "探索查询"))));
        tools.add(tool("graph_callers", "查询谁调用了该符号（节点依赖）",
                Map.of("symbol", Map.of("type", "string", "description", "符号名或查询"))));
        tools.add(tool("graph_callees", "查询该符号调用了谁（节点依赖）",
                Map.of("symbol", Map.of("type", "string", "description", "符号名或查询"))));
        tools.add(tool("graph_impact", "查询符号变更影响范围（节点依赖）",
                Map.of("symbol", Map.of("type", "string", "description", "符号名或查询"))));
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
