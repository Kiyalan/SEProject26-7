package com.repopilot.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * KnowledgeQueryService 扩展测试
 * 
 * 测试用例覆盖:
 * - TC-010: 正常提问获取回答
 * - TC-011: GraphRAG跨文件关联问答
 */
class KnowledgeQueryServiceExtendedTest {

    private static Map<String, Object> context(String file, String sourceType, String content) {
        return Map.of(
                "file", file,
                "line", 1,
                "symbolName", file,
                "sourceType", sourceType,
                "content", content
        );
    }

    @Test
    void TC011_routesBranchQuestionsToBranchList() {
        KnowledgeService knowledge = mock(KnowledgeService.class);
        PortfolioService portfolio = mock(PortfolioService.class);
        
        when(knowledge.branchContexts(eq("repo"), eq("owner")))
                .thenReturn(List.of(context("git/branches/feature/x", "branch_list", "Feature branch")));
        
        KnowledgeQueryService service = new KnowledgeQueryService(knowledge, portfolio);
        KnowledgeQueryService.QueryResult result = service.retrieve(
                "repo", "该仓库有哪些分支？", "owner");

        assertThat(result.intent()).contains("branches");
        verify(knowledge).branchContexts("repo", "owner");
    }

    @Test
    void TC011_routesCommitQuestionsToHistory() {
        KnowledgeService knowledge = mock(KnowledgeService.class);
        PortfolioService portfolio = mock(PortfolioService.class);
        
        when(knowledge.commitHistoryContexts(eq("repo"), eq("owner"), eq(20)))
                .thenReturn(List.of(context("git/commits/abc", "commit_history", "Initial commit")));
        
        KnowledgeQueryService service = new KnowledgeQueryService(knowledge, portfolio);
        KnowledgeQueryService.QueryResult result = service.retrieve(
                "repo", "请解释这20个commit", "owner");

        assertThat(result.intent()).isEqualTo("history");
        verify(knowledge).commitHistoryContexts("repo", "owner", 20);
    }

    @Test
    void TC011_routesApiQuestionToApiSpecification() {
        KnowledgeService knowledge = mock(KnowledgeService.class);
        PortfolioService portfolio = mock(PortfolioService.class);
        
        when(knowledge.apiSpecificationContexts(eq("repo"), eq("owner"), eq(100)))
                .thenReturn(List.of(context("contract/openapi.json", "api_spec", "OpenAPI specification")));
        
        KnowledgeQueryService service = new KnowledgeQueryService(knowledge, portfolio);
        KnowledgeQueryService.QueryResult result = service.retrieve(
                "repo", "有哪些API接口？", "owner");

        assertThat(result.intent()).contains("api");
    }

    @Test
    void TC011_routesDeploymentQuestionToDeploymentHints() {
        KnowledgeService knowledge = mock(KnowledgeService.class);
        PortfolioService portfolio = mock(PortfolioService.class);
        
        when(knowledge.retrieveChunksByPathHints(anyString(), anyString(), anyString(), anyList(), anyInt()))
                .thenReturn(List.of(
                        context("Dockerfile", "code", "FROM node:18"),
                        context("docker-compose.yml", "code", "version: '3'")
                ));
        
        KnowledgeQueryService service = new KnowledgeQueryService(knowledge, portfolio);
        KnowledgeQueryService.QueryResult result = service.retrieve(
                "repo", "如何部署这个项目？", "owner");

        assertThat(result.intent()).contains("deployment");
    }

    @Test
    void TC010_routesOverviewQuestionToRepositoryOverview() {
        KnowledgeService knowledge = mock(KnowledgeService.class);
        PortfolioService portfolio = mock(PortfolioService.class);
        
        when(knowledge.repositoryOverviewContext(eq("repo"), eq("owner")))
                .thenReturn(context("README.md", "repository_overview", "Project overview"));
        when(knowledge.retrieveChunksByPathHints(anyString(), anyString(), anyString(), anyList(), anyInt()))
                .thenReturn(List.of());
        
        KnowledgeQueryService service = new KnowledgeQueryService(knowledge, portfolio);
        KnowledgeQueryService.QueryResult result = service.retrieve(
                "repo", "这个项目是做什么的？", "owner");

        assertThat(result.intent()).contains("overview");
    }

    @Test
    void TC010_handlesKnowledgeServiceExceptionGracefully() {
        KnowledgeService knowledge = mock(KnowledgeService.class);
        PortfolioService portfolio = mock(PortfolioService.class);
        
        when(knowledge.retrieveChunks(anyString(), anyString(), anyString(), any(), anyInt()))
                .thenThrow(new RuntimeException("知识库未就绪"));
        
        KnowledgeQueryService service = new KnowledgeQueryService(knowledge, portfolio);
        KnowledgeQueryService.QueryResult result = service.retrieve(
                "repo", "代码结构是什么？", "owner");

        assertThat(result.contexts()).isNotEmpty();
        assertThat(result.contexts().get(0).get("sourceType")).isEqualTo("system");
    }

    @Test
    void TC011_combinesMultipleIntentsFromCompoundQuestion() {
        KnowledgeService knowledge = mock(KnowledgeService.class);
        PortfolioService portfolio = mock(PortfolioService.class);
        
        when(knowledge.repositoryOverviewContext(eq("repo"), eq("owner")))
                .thenReturn(context("README.md", "repository_overview", "Overview"));
        when(knowledge.apiSpecificationContexts(eq("repo"), eq("owner"), eq(100)))
                .thenReturn(List.of(context("openapi.json", "api_spec", "API spec")));
        
        KnowledgeQueryService service = new KnowledgeQueryService(knowledge, portfolio);
        KnowledgeQueryService.QueryResult result = service.retrieve(
                "repo", "项目目的是什么？有哪些接口？", "owner");

        // Should combine overview and api intents
        assertThat(result.intent()).contains("overview");
        assertThat(result.intent()).contains("api");
    }

    @Test
    void TC010_defaultToCodeIntentForGeneralQuestions() {
        KnowledgeService knowledge = mock(KnowledgeService.class);
        PortfolioService portfolio = mock(PortfolioService.class);
        
        when(knowledge.retrieveChunks(eq("repo"), eq("owner"), anyString(), isNull(), eq(10)))
                .thenReturn(List.of(context("src/Code.java", "code", "Some code content")));
        
        KnowledgeQueryService service = new KnowledgeQueryService(knowledge, portfolio);
        KnowledgeQueryService.QueryResult result = service.retrieve(
                "repo", "这个函数是干什么的？", "owner");

        assertThat(result.intent()).contains("code");
    }
}
