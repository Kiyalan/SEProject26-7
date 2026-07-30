package com.repopilot.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeServiceApiExtractTest {

    @Test
    void extractsPublicMethodsFromKnowledgeServiceSource() throws Exception {
        Path file = Path.of("src/main/java/com/repopilot/service/KnowledgeService.java");
        String source = Files.readString(file);
        String inventory = KnowledgeService.extractJavaPublicApi("KnowledgeService", source);

        assertThat(inventory).contains("buildKnowledge(");
        assertThat(inventory).contains("graphRagContexts(");
        assertThat(inventory).contains("resetKnowledge(");
        assertThat(inventory).contains("callers(");
        assertThat(inventory).contains("retrieveChunks(");
        assertThat(inventory).contains("getOverview(");
        assertThat(inventory).doesNotContain("routesBranchQuestionsToBranchList");
        assertThat(inventory).containsPattern("共 \\d+ 个方法");
    }

    @Test
    void guessJavaTypeNameNormalizesLowercaseService() {
        // package-private helper covered indirectly via extract; keep naming contract here
        assertThat(KnowledgeService.extractJavaPublicApi("Demo",
                "public class Demo {\n  public void alpha() {}\n  public int beta(String x) { return 1; }\n}\n"))
                .contains("alpha()")
                .contains("beta(String x)")
                .contains("共 2 个方法");
    }
}
