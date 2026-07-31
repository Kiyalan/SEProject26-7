package com.repopilot.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Approximate source line counts from a local working tree.
 * GitHub does not expose a total LOC field; language bytes via Linguist are different.
 */
public final class RepoLineCountService {

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "target", "dist", "build", ".idea", ".vscode",
            "coverage", ".mvn", "vendor", "__pycache__", ".node", "UIPrototype", "TechPrototype");

    private static final Set<String> SKIP_FILES = Set.of(
            "package-lock.json", "yarn.lock", "pnpm-lock.yaml");

    private RepoLineCountService() {}

    public static Map<String, Object> count(Path root) {
        Map<String, Integer> byLanguage = new LinkedHashMap<>();
        int[] totals = {0, 0}; // lines, files
        if (root == null || !Files.isDirectory(root)) {
            return result(0, 0, byLanguage);
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (SKIP_DIRS.contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if (SKIP_FILES.contains(name) || name.endsWith(".min.js") || name.endsWith(".min.css")
                            || name.endsWith(".map") || name.endsWith(".jar") || name.endsWith(".class")) {
                        return FileVisitResult.CONTINUE;
                    }
                    String language = languageOf(name);
                    if (language == null) {
                        return FileVisitResult.CONTINUE;
                    }
                    int lines = countLines(file);
                    if (lines <= 0) {
                        return FileVisitResult.CONTINUE;
                    }
                    totals[0] += lines;
                    totals[1] += 1;
                    byLanguage.merge(language, lines, Integer::sum);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // Best-effort metric for overview UI / Q&A.
        }
        return result(totals[0], totals[1], byLanguage);
    }

    private static Map<String, Object> result(int lineCount, int sourceFileCount, Map<String, Integer> byLanguage) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("lineCount", lineCount);
        row.put("sourceFileCount", sourceFileCount);
        row.put("lineCountByLanguage", byLanguage);
        row.put("lineCountNote", "本地工作区估算行数（非 GitHub 原生字段；已排除 node_modules/target 等）");
        return row;
    }

    private static int countLines(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) {
                return 0;
            }
            // Skip likely-binary
            int sample = Math.min(bytes.length, 800);
            for (int i = 0; i < sample; i++) {
                if (bytes[i] == 0) {
                    return 0;
                }
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            int lines = 1;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    lines++;
                }
            }
            if (text.endsWith("\n") && lines > 1) {
                lines--;
            }
            return lines;
        } catch (Exception ex) {
            return 0;
        }
    }

    private static String languageOf(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        return switch (lower.substring(dot + 1)) {
            case "java" -> "Java";
            case "ts" -> "TypeScript";
            case "tsx" -> "TSX";
            case "js", "jsx", "mjs", "cjs" -> "JavaScript";
            case "py" -> "Python";
            case "go" -> "Go";
            case "rs" -> "Rust";
            case "kt", "kts" -> "Kotlin";
            case "cs" -> "C#";
            case "cpp", "cc", "cxx", "c", "h", "hpp" -> "C/C++";
            case "rb" -> "Ruby";
            case "php" -> "PHP";
            case "swift" -> "Swift";
            case "scala" -> "Scala";
            case "sql" -> "SQL";
            case "sh", "bash", "zsh", "ps1" -> "Shell";
            case "yml", "yaml" -> "YAML";
            case "xml" -> "XML";
            case "json" -> "JSON";
            case "md", "markdown" -> "Markdown";
            case "css", "scss", "less" -> "CSS";
            case "html", "htm" -> "HTML";
            case "vue", "svelte" -> "Frontend";
            default -> null;
        };
    }
}
