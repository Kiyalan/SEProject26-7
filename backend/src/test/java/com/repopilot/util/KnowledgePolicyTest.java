package com.repopilot.util;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgePolicyTest {

    @Test
    void overview_returnsCompletePolicy() {
        Map<String, Object> policy = KnowledgePolicy.overview();

        assertThat(policy).containsKey("required");
        assertThat(policy).containsKey("recommended");
        assertThat(policy).containsKey("excludedDirs");
        assertThat(policy).containsKey("storeOnly");
        assertThat(policy).containsKey("displayOnly");
        assertThat(policy).containsKey("featureMatrix");
        assertThat(policy).containsKey("limits");
    }

    @Test
    void overview_requiredContainsCodeWikiSupport() {
        Map<String, Object> policy = KnowledgePolicy.overview();
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) policy.get("required");

        assertThat(required).contains("CodeWiki 支持语言源码");
        assertThat(required).contains("README");
    }

    @Test
    void overview_excludedDirsContainsCommonDirs() {
        Map<String, Object> policy = KnowledgePolicy.overview();
        @SuppressWarnings("unchecked")
        List<String> excludedDirs = (List<String>) policy.get("excludedDirs");

        assertThat(excludedDirs).contains(".git", "node_modules", "target");
    }

    @Test
    void overview_featureMatrixContainsIssueAnalysis() {
        Map<String, Object> policy = KnowledgePolicy.overview();
        @SuppressWarnings("unchecked")
        Map<String, Map<String, ?>> featureMatrix = (Map<String, Map<String, ?>>) policy.get("featureMatrix");

        assertThat(featureMatrix).containsKey("智能问答");
        assertThat(featureMatrix).containsKey("Issue 分析");
    }
}
