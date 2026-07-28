package com.repopilot.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeQueryServiceTest {

    @Test
    void routesCommitQuestionsToStructuredHistory() {
        KnowledgeService knowledge = mock(KnowledgeService.class);
        PortfolioService portfolio = mock(PortfolioService.class);
        when(knowledge.commitHistoryContexts(eq("repo"), eq("owner"), eq(20)))
                .thenReturn(List.of(context("git/commits/abc", "commit_history")));
        KnowledgeQueryService service = new KnowledgeQueryService(knowledge, portfolio);

        KnowledgeQueryService.QueryResult result =
                service.retrieve("repo", "请分别解释这20个commit，哪些后来被弃用？", "owner");

        assertThat(result.intent()).isEqualTo("history");
        assertThat(result.contexts()).hasSize(1);
        verify(knowledge).commitHistoryContexts("repo", "owner", 20);
    }

    @Test
    void routesBranchQuestionsToBranchList() {
        KnowledgeService knowledge = mock(KnowledgeService.class);
        PortfolioService portfolio = mock(PortfolioService.class);
        when(knowledge.branchContexts(eq("repo"), eq("owner")))
                .thenReturn(List.of(context("git/branches/feature/x", "branch_list")));
        KnowledgeQueryService service = new KnowledgeQueryService(knowledge, portfolio);

        KnowledgeQueryService.QueryResult result = service.retrieve(
                "repo", "该仓库有哪些分支，各自大致在做什么？哪些分支几乎没有内容？", "owner");

        assertThat(result.intent()).contains("branches");
        assertThat(result.contexts())
                .extracting(row -> row.get("sourceType"))
                .contains("branch_list");
        verify(knowledge).branchContexts("repo", "owner");
    }

    @Test
    void routesMultiRepoQuestionsToPortfolio() {
        KnowledgeService knowledge = mock(KnowledgeService.class);
        PortfolioService portfolio = mock(PortfolioService.class);
        when(portfolio.overview(eq("token"), eq("owner"), eq(50))).thenReturn(Map.of(
                "summary", Map.of("repoCount", 1, "indexedCount", 0, "totalIndexedFiles", 0, "totalChunks", 0),
                "repos", List.of(Map.of(
                        "fullName", "acme/empty",
                        "stars", 0,
                        "language", "Java",
                        "pushedAt", "2024-01-01",
                        "knowledge", Map.of("indexed", false, "fileCount", 0, "chunkCount", 0)
                ))
        ));
        KnowledgeQueryService service = new KnowledgeQueryService(knowledge, portfolio);

        KnowledgeQueryService.QueryResult result = service.retrieve(
                "repo", "哪些仓库是几乎没有内容的？哪些与main几乎无关或版本落后很多？",
                "owner", "token");

        assertThat(result.intent()).contains("portfolio");
        assertThat(result.contexts())
                .extracting(row -> row.get("sourceType"))
                .contains("portfolio");
    }

    @Test
    void combinesOverviewApiAndDeploymentSourcesForCompoundQuestion() {
        KnowledgeService knowledge = mock(KnowledgeService.class);
        PortfolioService portfolio = mock(PortfolioService.class);
        when(knowledge.repositoryOverviewContext(eq("repo"), eq("owner")))
                .thenReturn(context("knowledge/repository-overview", "repository_overview"));
        when(knowledge.apiSpecificationContexts(eq("repo"), eq("owner"), eq(100)))
                .thenReturn(List.of(context("contract/openapi.json", "api_spec")));
        when(knowledge.retrieveChunksByPathHints(anyString(), anyString(), anyString(), anyList(), anyInt()))
                .thenReturn(List.of(context("backend/run.ps1", "code")));
        KnowledgeQueryService service = new KnowledgeQueryService(knowledge, portfolio);

        KnowledgeQueryService.QueryResult result =
                service.retrieve("repo", "项目主要目的是什么？当前接口有哪些，如何部署？", "owner");

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
                "content", "meaningful context payload for " + file
        );
    }
}
