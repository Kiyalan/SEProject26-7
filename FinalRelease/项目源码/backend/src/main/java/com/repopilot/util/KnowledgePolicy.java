package com.repopilot.util;

import java.util.List;
import java.util.Map;

public final class KnowledgePolicy {

    private KnowledgePolicy() {}

    public static Map<String, Object> overview() {
        return Map.of(
                "required", List.of("CodeWiki 支持语言源码", "README", "OpenAPI", "构建与部署配置"),
                "recommended", List.of("docs/", "测试源码", "包管理清单"),
                "excludedDirs", List.of(".git", "node_modules", "dist", "build", ".venv", "target"),
                "storeOnly", List.of("CodeWiki AST 图、Leiden 社区、LLM 社区报告、实体 Embedding（pgvector）"),
                "displayOnly", List.of("GraphRAG 统计", "JGit commit 时间线"),
                "featureMatrix", Map.of(
                        "智能问答", Map.of("needs", List.of("AST 实体图", "实体 Embedding", "社区报告"), "not_needed", List.of("二进制")),
                        "Issue 分析", Map.of("needs", List.of("GraphRAG source chunks"), "not_needed", List.of("vendor"))
                ),
                "limits", Map.of("maxFilesPerCommit", 0, "maxFileBytes", 0, "chunkSize", 0)
        );
    }
}
