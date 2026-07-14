package com.repopilot.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.regex.Pattern;

public final class KnowledgeUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_./-]*|[\\u4e00-\\u9fff]{2,}");

    private KnowledgeUtils() {}

    public static boolean shouldSkipPath(String path) {
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if (KnowledgePolicy.SKIP_DIRS.contains(parts[i]) || parts[i].startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    public static boolean isTextFile(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith("dockerfile")) {
            return true;
        }
        for (String ext : KnowledgePolicy.TEXT_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    public static int filePriority(String path, int size) {
        String lower = path.toLowerCase();
        String name = lower.contains("/") ? lower.substring(lower.lastIndexOf('/') + 1) : lower;
        int rank;
        if (name.startsWith("readme")) {
            rank = 0;
        } else if (Set.of("package.json", "requirements.txt", "main.py", "main.tsx").contains(name)) {
            rank = 1;
        } else if (lower.startsWith("src/") || lower.startsWith("backend/") || lower.startsWith("app/")) {
            rank = 2;
        } else if (lower.startsWith("docs/")) {
            rank = 3;
        } else {
            rank = 4;
        }
        return rank * 1_000_000 + size;
    }

    public static String detectLanguage(String path) {
        Map<String, String> mapping = Map.of(
                ".py", "Python", ".ts", "TypeScript", ".tsx", "TypeScript",
                ".js", "JavaScript", ".jsx", "JavaScript", ".md", "Markdown",
                ".json", "JSON", ".java", "Java", ".go", "Go", ".rs", "Rust"
        );
        String lower = path.toLowerCase();
        for (var entry : mapping.entrySet()) {
            if (lower.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    public static List<Map<String, Object>> chunkText(String content, String filePath) {
        List<Map<String, Object>> chunks = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return chunks;
        }
        int start = 0;
        int chunkIndex = 0;
        while (start < content.length()) {
            int end = Math.min(content.length(), start + KnowledgePolicy.CHUNK_SIZE);
            String piece = content.substring(start, end);
            int lineNo = content.substring(0, start).split("\n", -1).length;
            chunks.add(Map.of(
                    "file_path", filePath,
                    "chunk_index", chunkIndex,
                    "content", piece,
                    "start_line", lineNo
            ));
            chunkIndex++;
            if (end >= content.length()) {
                break;
            }
            start = Math.max(end - KnowledgePolicy.CHUNK_OVERLAP, start + 1);
        }
        return chunks;
    }

    public static List<Map<String, Object>> buildTree(List<String> paths) {
        Map<String, Object> root = new TreeMap<>();
        for (String path : paths.stream().sorted().toList()) {
            String[] parts = path.split("/");
            Map<String, Object> cursor = root;
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                @SuppressWarnings("unchecked")
                Map<String, Object> node = (Map<String, Object>) cursor.computeIfAbsent(part, k -> new LinkedHashMap<>());
                node.putIfAbsent("__path__", String.join("/", Arrays.copyOfRange(parts, 0, i + 1)));
                if (i == parts.length - 1) {
                    node.put("__is_file__", true);
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> children = (Map<String, Object>) node.computeIfAbsent("__children__", k -> new LinkedHashMap<>());
                cursor = children;
            }
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (var entry : root.entrySet()) {
            nodes.add(toNode((Map<String, Object>) entry.getValue(), entry.getKey()));
        }
        return nodes;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toNode(Map<String, Object> node, String name) {
        boolean isFile = Boolean.TRUE.equals(node.get("__is_file__"));
        String fullPath = (String) node.getOrDefault("__path__", name);
        Map<String, Object> childrenDict = (Map<String, Object>) node.getOrDefault("__children__", Map.of());
        List<Map<String, Object>> children = new ArrayList<>();
        for (var entry : childrenDict.entrySet()) {
            children.add(toNode((Map<String, Object>) entry.getValue(), entry.getKey()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", fullPath);
        result.put("title", name);
        result.put("type", isFile ? "file" : (fullPath.split("/").length <= 1 ? "folder" : "module"));
        result.put("children", children.isEmpty() ? null : children);
        return result;
    }

    public static List<Map<String, Object>> extractModules(List<FileRow> files) {
        Map<String, List<FileRow>> buckets = new TreeMap<>();
        for (FileRow file : files) {
            if (!"file".equals(file.fileType())) {
                continue;
            }
            String top = file.path().contains("/") ? file.path().split("/")[0] : file.path();
            buckets.computeIfAbsent(top, k -> new ArrayList<>()).add(file);
        }
        List<Map<String, Object>> modules = new ArrayList<>();
        for (var entry : buckets.entrySet()) {
            Set<String> langs = new TreeSet<>();
            for (FileRow row : entry.getValue()) {
                if (row.language() != null) {
                    langs.add(row.language());
                }
            }
            modules.add(Map.of(
                    "name", entry.getKey(),
                    "desc", "包含 " + entry.getValue().size() + " 个已索引文件",
                    "files", entry.getValue().size(),
                    "deps", langs.stream().limit(5).toList()
            ));
            if (modules.size() >= 12) {
                break;
            }
        }
        return modules;
    }

    public static Map<String, Integer> extractLanguageStats(List<FileRow> files) {
        Map<String, Integer> stats = new HashMap<>();
        for (FileRow file : files) {
            if (!"file".equals(file.fileType())) {
                continue;
            }
            String lang = file.language() == null ? "Other" : file.language();
            stats.merge(lang, 1, Integer::sum);
        }
        return stats.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), LinkedHashMap::putAll);
    }

    public static String extractRepoSummary(String fullName, Map<String, String> fileMap, List<Map<String, Object>> modules) {
        Optional<String> readme = fileMap.entrySet().stream()
                .filter(e -> e.getKey().toLowerCase().endsWith("readme.md") || e.getKey().toLowerCase().contains("readme"))
                .map(Map.Entry::getValue)
                .findFirst();
        if (readme.isPresent()) {
            String preview = readme.get().replaceAll("\\s+", " ").trim();
            if (preview.length() > 360) {
                preview = preview.substring(0, 360);
            }
            return fullName + "：" + preview;
        }
        if (!modules.isEmpty()) {
            String names = modules.stream().limit(6).map(m -> (String) m.get("name")).reduce((a, b) -> a + "、" + b).orElse("");
            return fullName + " 已索引主要模块：" + names + "。";
        }
        return fullName + " 已完成基础目录与文件索引。";
    }

    public static List<String> extractDependencies(Map<String, String> fileMap) {
        Set<String> deps = new TreeSet<>();
        String packageJson = fileMap.get("package.json");
        if (packageJson != null) {
            try {
                JsonNode data = MAPPER.readTree(packageJson);
                for (String key : List.of("dependencies", "devDependencies")) {
                    JsonNode node = data.path(key);
                    if (node.isObject()) {
                        node.fieldNames().forEachRemaining(deps::add);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        String requirements = fileMap.getOrDefault("requirements.txt", fileMap.get("backend/requirements.txt"));
        if (requirements != null) {
            for (String line : requirements.split("\n")) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    deps.add(line.split("[<>=!]")[0].trim());
                }
            }
        }
        return deps.stream().limit(20).toList();
    }

    public static String classifyQuestion(String question) {
        String q = question.toLowerCase();
        if (q.matches(".*(什么|是什么|what).*")) {
            return "what";
        }
        if (q.matches(".*(哪里|在哪|where|路径|文件).*")) {
            return "where";
        }
        if (q.matches(".*(如何|怎么|怎样|how).*")) {
            return "how";
        }
        return "what";
    }

    public static List<String> tokenize(String question) {
        Set<String> stopWords = Set.of("什么", "如何", "怎么", "哪里", "项目", "代码", "the", "and", "for");
        var matcher = TOKEN_PATTERN.matcher(question.toLowerCase());
        List<String> tokens = new ArrayList<>();
        while (matcher.find()) {
            String token = matcher.group();
            if (!stopWords.contains(token) && token.length() > 1) {
                tokens.add(token);
            }
        }
        return tokens.stream().distinct().toList();
    }

    public record FileRow(String path, String fileType, int size, String language, String content) {}
}
