package com.repopilot.util;

public final class KnowledgeUtils {

    private KnowledgeUtils() {
    }

    public static String classifyQuestion(String question) {
        String normalized = question == null ? "" : question.toLowerCase();
        if (normalized.matches(".*(在哪|哪里|where|位置|文件).*")) {
            return "where";
        }
        if (normalized.matches(".*(如何|怎么|怎样|how).*")) {
            return "how";
        }
        return "what";
    }
}
