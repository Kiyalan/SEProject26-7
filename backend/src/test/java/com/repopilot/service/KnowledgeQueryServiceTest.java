package com.repopilot.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeQueryServiceTest {

    @Test
    void routesCommitQuestionsToStructuredHistory() {
        KnowledgeService knowledge = mock(KnowledgeService.class);
        when(knowledge.commitHistoryContexts("repo", 20))
                .thenReturn(List.of(context("git/commits/abc", "commit_history")));
        KnowledgeQueryService service = new KnowledgeQueryService(knowledge);

        KnowledgeQueryService.QueryResult result =
                service.retrieve("repo", "请分别解释这20个commit，哪些后来被弃用？");

        assertThat(result.intent()).isEqualTo("history");
        assertThat(result.contexts()).hasSize(1);
        verify(knowledge).commitHistoryContexts("repo", 20);
    }

    @Test
    void combinesOverviewApiAndDeploymentSourcesForCompoundQuestion() {
        KnowledgeService knowledge = mock(KnowledgeService.class);
        when(knowledge.repositoryOverviewContext("repo"))
                .thenReturn(context("knowledge/repository-overview", "repository_overview"));
        when(knowledge.apiSpecificationContexts("repo", 100))
                .thenReturn(List.of(context("contract/openapi.json", "api_spec")));
        when(knowledge.retrieveChunksByPathHints(anyString(), anyString(), anyList(), anyInt()))
                .thenReturn(List.of(context("backend/run.ps1", "code")));
        KnowledgeQueryService service = new KnowledgeQueryService(knowledge);

        KnowledgeQueryService.QueryResult result =
                service.retrieve("repo", "项目主要目的是什么？当前接口有哪些，如何部署？");

        assertThat(result.intent()).contains("overview", "api", "deployment");
        assertThat(result.contexts())
                .extracting(row -> row.get("sourceType"))
                .contains("repository_overview", "api_spec", "code");
    }

    private static Map<String, Object> context(String file, String sourceType) {
        return Map.of(
                "file", file,
                "line", 1,
                "symbolName", file,
                "sourceType", sourceType,
                "content", "context"
        );
    }
}
