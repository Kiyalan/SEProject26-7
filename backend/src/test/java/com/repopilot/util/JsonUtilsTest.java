package com.repopilot.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonUtilsTest {

    @Test
    void toJson_withValidObject() {
        Map<String, Object> obj = Map.of("key", "value", "num", 42);
        String json = JsonUtils.toJson(obj);

        assertThat(json).contains("key");
        assertThat(json).contains("value");
    }

    @Test
    void toJson_withNullObject() {
        String json = JsonUtils.toJson(null);
        assertThat(json).isEqualTo("{}");
    }

    @Test
    void toJson_withException() {
        String json = JsonUtils.toJson(new Object() {
            @Override
            public String toString() {
                throw new RuntimeException("test");
            }
        });
        assertThat(json).isEqualTo("{}");
    }

    @Test
    void parseIntMap_withValidJson() {
        Map<String, Integer> result = JsonUtils.parseIntMap("{\"a\": 1, \"b\": 2}");
        assertThat(result).hasSize(2);
        assertThat(result.get("a")).isEqualTo(1);
        assertThat(result.get("b")).isEqualTo(2);
    }

    @Test
    void parseIntMap_withNull() {
        Map<String, Integer> result = JsonUtils.parseIntMap(null);
        assertThat(result).isEmpty();
    }

    @Test
    void parseIntMap_withInvalidJson() {
        Map<String, Integer> result = JsonUtils.parseIntMap("invalid");
        assertThat(result).isEmpty();
    }

    @Test
    void parseStringList_withValidJson() {
        List<String> result = JsonUtils.parseStringList("[\"a\", \"b\", \"c\"]");
        assertThat(result).containsExactly("a", "b", "c");
    }

    @Test
    void parseStringList_withNull() {
        List<String> result = JsonUtils.parseStringList(null);
        assertThat(result).isEmpty();
    }

    @Test
    void parseStringList_withBlank() {
        List<String> result = JsonUtils.parseStringList("   ");
        assertThat(result).isEmpty();
    }

    @Test
    void parseMapList_withValidJson() {
        List<Map<String, Object>> result = JsonUtils.parseMapList("[{\"k\": \"v\"}, {\"k2\": \"v2\"}]");
        assertThat(result).hasSize(2);
    }

    @Test
    void parseMapList_withNull() {
        List<Map<String, Object>> result = JsonUtils.parseMapList(null);
        assertThat(result).isEmpty();
    }

    @Test
    void parseObject_withValidJson() {
        Map<String, Object> result = JsonUtils.parseObject("{\"key\": \"value\"}");
        assertThat(result.get("key")).isEqualTo("value");
    }

    @Test
    void parseObject_withNull() {
        Map<String, Object> result = JsonUtils.parseObject(null);
        assertThat(result).isEmpty();
    }

    @Test
    void parseObject_withInvalidJson() {
        Map<String, Object> result = JsonUtils.parseObject("not json");
        assertThat(result).isEmpty();
    }
}
