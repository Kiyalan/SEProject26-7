package com.repopilot.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CodeWikiClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void healthResponse_recordCreation() {
        CodeWikiClient.HealthResponse response = new CodeWikiClient.HealthResponse("ok", "v1.0");
        assertThat(response.status()).isEqualTo("ok");
        assertThat(response.version()).isEqualTo("v1.0");
    }

    @Test
    void registerRepoRequest_recordCreation() {
        CodeWikiClient.RegisterRepoRequest request = new CodeWikiClient.RegisterRepoRequest("owner/repo", "test-repo", "local");
        assertThat(request.path()).isEqualTo("owner/repo");
        assertThat(request.name()).isEqualTo("test-repo");
        assertThat(request.source_type()).isEqualTo("local");
    }

    @Test
    void repoResponse_recordCreation() {
        CodeWikiClient.RepoResponse response = new CodeWikiClient.RepoResponse("repo123", "repo123", "test-repo", mapper.createObjectNode());
        assertThat(response.id()).isEqualTo("repo123");
        assertThat(response.repo_id()).isEqualTo("repo123");
        assertThat(response.name()).isEqualTo("test-repo");
        assertThat(response.resolvedId()).isEqualTo("repo123");
    }

    @Test
    void runResponse_recordCreation() {
        CodeWikiClient.RunResponse response = new CodeWikiClient.RunResponse(
                "run123", "run123", "repo123", "completed",
                100, 50, 200, 150, mapper.createObjectNode(), mapper.createObjectNode());
        assertThat(response.run_id()).isEqualTo("run123");
        assertThat(response.status()).isEqualTo("completed");
        assertThat(response.progressHint()).isEqualTo(500);
    }

    @Test
    void updateRequest_recordCreation() {
        CodeWikiClient.UpdateRequest request = new CodeWikiClient.UpdateRequest(true, false, false);
        assertThat(request.refresh_chunks()).isTrue();
        assertThat(request.name_communities()).isFalse();
        assertThat(request.regenerate_wiki()).isFalse();
    }

    @Test
    void graphBuildRequest_recordCreation() {
        CodeWikiClient.GraphBuildRequest request = new CodeWikiClient.GraphBuildRequest(true);
        assertThat(request.include_embeddings()).isTrue();
    }

    @Test
    void retrieveRequest_recordCreation() {
        CodeWikiClient.RetrieveRequest request = new CodeWikiClient.RetrieveRequest("test query", 3, true);
        assertThat(request.query()).isEqualTo("test query");
        assertThat(request.max_hops()).isEqualTo(3);
        assertThat(request.include_embeddings()).isTrue();
    }
}
