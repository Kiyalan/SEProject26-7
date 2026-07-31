package com.repopilot.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

public final class JsonUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonUtils() {
    }

    public static String toJson(Object value) {
        if (value == null) {
            return "{}";
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    public static Map<String, Integer> parseIntMap(String json) {
        try {
            return MAPPER.readValue(json == null || json.isBlank() ? "{}" : json, new TypeReference<>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }

    public static List<String> parseStringList(String json) {
        try {
            return MAPPER.readValue(json == null || json.isBlank() ? "[]" : json, new TypeReference<>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }

    public static List<Map<String, Object>> parseMapList(String json) {
        try {
            return MAPPER.readValue(json == null || json.isBlank() ? "[]" : json, new TypeReference<>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }

    public static Map<String, Object> parseObject(String json) {
        try {
            return MAPPER.readValue(json == null || json.isBlank() ? "{}" : json, new TypeReference<>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }
}
