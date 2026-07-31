package com.repopilot.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeServicePrioritizeTest {

    @Test
    void prioritizeLocalContextsKeepsCodeWindowsWhenTruncating() {
        List<Map<String, Object>> contexts = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            contexts.add(row("entity/" + i, "entity"));
        }
        contexts.add(row("community/a", "community_report"));
        contexts.add(row("rel", "relationship"));
        contexts.add(row("src/BankAccount.java", "code_window"));
        contexts.add(row("src/Other.java", "code_window"));

        List<Map<String, Object>> selected = KnowledgeService.prioritizeLocalContexts(contexts, 16);

        assertThat(selected).hasSize(16);
        assertThat(selected.get(0).get("sourceType")).isEqualTo("code_window");
        assertThat(selected.get(1).get("sourceType")).isEqualTo("code_window");
        assertThat(selected.get(2).get("sourceType")).isEqualTo("relationship");
        assertThat(selected.stream().filter(r -> "code_window".equals(r.get("sourceType"))).count())
                .isEqualTo(2);
        assertThat(selected.stream().anyMatch(r -> "relationship".equals(r.get("sourceType")))).isTrue();
        // With bound=16, community_report may be dropped after code/relationship/entity fill.
        assertThat(selected.stream().filter(r -> "entity".equals(r.get("sourceType"))).count())
                .isEqualTo(13);
    }

    private static Map<String, Object> row(String file, String sourceType) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("file", file);
        map.put("line", 1);
        map.put("sourceType", sourceType);
        map.put("content", "content for " + file);
        return map;
    }
}
