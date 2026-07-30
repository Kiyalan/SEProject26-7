package com.repopilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.client.GitHubClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepoAuthorizationServiceTest {

    @Mock
    private GitHubClient gitHubClient;

    private RepoAuthorizationService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        service = new RepoAuthorizationService(gitHubClient);
        objectMapper = new ObjectMapper();
    }

    @Test
    void requireAccess_withValidRepo_returnsJsonNode() {
        JsonNode mockRepo = objectMapper.createObjectNode()
                .put("id", 123456)
                .put("full_name", "owner/repo");
        when(gitHubClient.get("/repositories/owner/repo", "token")).thenReturn(mockRepo);

        JsonNode result = service.requireAccess("owner/repo", "token");

        assertThat(result).isNotNull();
        assertThat(result.path("id").asText()).isEqualTo("123456");
    }

    @Test
    void requireAccess_withNullRepo_throws() {
        when(gitHubClient.get("/repositories/invalid", "token")).thenReturn(null);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> {
            service.requireAccess("invalid", "token");
        });
    }

    @Test
    void requireAccess_withMissingNode_throws() {
        // Empty object node has no "id" field, so it should throw
        JsonNode missingNode = objectMapper.createObjectNode();

        when(gitHubClient.get("/repositories/missing", "token")).thenReturn(missingNode);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> {
            service.requireAccess("missing", "token");
        });
    }
}
