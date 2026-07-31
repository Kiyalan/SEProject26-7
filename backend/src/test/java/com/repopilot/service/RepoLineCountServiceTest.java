package com.repopilot.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RepoLineCountServiceTest {

    @TempDir
    Path temp;

    @Test
    void countsSourceLinesAndSkipsHeavyDirs() throws Exception {
        Files.writeString(temp.resolve("Main.java"), "class Main {\n  // hi\n}\n");
        Files.writeString(temp.resolve("readme.md"), "# title\n\npara\n");
        Path ignored = temp.resolve("node_modules");
        Files.createDirectories(ignored);
        Files.writeString(ignored.resolve("huge.js"), "a\n".repeat(500));

        Map<String, Object> stats = RepoLineCountService.count(temp);

        assertThat(stats.get("lineCount")).isEqualTo(6);
        assertThat(stats.get("sourceFileCount")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        Map<String, Integer> byLang = (Map<String, Integer>) stats.get("lineCountByLanguage");
        assertThat(byLang.get("Java")).isEqualTo(3);
        assertThat(byLang.get("Markdown")).isEqualTo(3);
    }
}
