package com.repopilot.knowledge;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class KnowledgePolicy {

    private KnowledgePolicy() {}

    public static Map<String, Object> overview() {
        return Map.of(
                "required", List.of("readme", "package.json", "requirements.txt", "src/", "backend/", "docs/"),
                "recommended", List.of("*.md", "vite.config", "tsconfig", "main.py", "main.tsx"),
                "excludedDirs", List.of(".git", "node_modules", "dist", "build", ".venv", "target"),
                "storeOnly", List.of("commit_chunks（全文分块，供 RAG / Issue 检索）"),
                "displayOnly", List.of("languages 统计", "modules 概览", "commit 时间线"),
                "featureMatrix", Map.of(
                        "智能问答", Map.of("needs", List.of("README", "源码片段"), "not_needed", List.of("二进制")),
                        "Issue 分析", Map.of("needs", List.of("源码片段", "README"), "not_needed", List.of("vendor"))
                ),
                "limits", Map.of("maxFilesPerCommit", 120, "maxFileBytes", 80_000, "chunkSize", 900)
        );
    }

    public static final Set<String> SKIP_DIRS = Set.of(
            "node_modules", ".git", "dist", "build", ".next", "__pycache__",
            ".venv", "venv", "coverage", ".idea", ".vscode"
    );

    public static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".md", ".txt", ".py", ".ts", ".tsx", ".js", ".jsx", ".json",
            ".yml", ".yaml", ".toml", ".html", ".css", ".java", ".go", ".rs", ".sql", ".sh"
    );

    public static final int MAX_FILES = 120;
    public static final int MAX_FILE_BYTES = 80_000;
    public static final int CHUNK_SIZE = 900;
    public static final int CHUNK_OVERLAP = 120;
}
