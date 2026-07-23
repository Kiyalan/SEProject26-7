package com.repopilot.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.config.AppProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeWikiClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void mapsRegisterResponse() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/repos", exchange -> {
            byte[] body = "{\"id\":\"cw-42\",\"name\":\"owner/repo\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        CodeWikiClient.RepoResponse response = client().register("/repos/42", "owner/repo");

        assertThat(response.resolvedId()).isEqualTo("cw-42");
    }

    @Test
    void mapsServerErrorsWithOperationAndRetryability() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/health", exchange -> {
            byte[] body = "{\"detail\":\"temporarily unavailable\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        assertThatThrownBy(() -> client().health())
                .isInstanceOfSatisfying(CodeWikiException.class, error -> {
                    assertThat(error.operation()).isEqualTo("health");
                    assertThat(error.retryable()).isTrue();
                    assertThat(error.getMessage()).contains("temporarily unavailable");
                });
    }

    @Test
    void pollsRepositoryScopedRunUntilCodeWikiDoneStatus() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/repos/cw-42/runs/run-1", exchange -> {
            byte[] body = """
                    {"run_id":"run-1","repo_id":"cw-42","status":"done","stats":{"node_count":7}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        assertThat(client().awaitRun("cw-42", "run-1").path("status").asText()).isEqualTo("done");
    }

    @Test
    void usesDedicatedGraphStatusEndpoint() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/repos/cw-42/graph/status", exchange -> {
            byte[] body = "{\"node_count\":12,\"edge_count\":20}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        assertThat(client().graphStatus("cw-42").path("node_count").asInt()).isEqualTo(12);
    }

    private CodeWikiClient client() {
        AppProperties.CodeWiki config = new AppProperties.CodeWiki(
                "http://localhost:" + server.getAddress().getPort(), ".", "/repos",
                true, 2, 2, 1, 2);
        AppProperties properties = new AppProperties(null, null, config);
        return new CodeWikiClient(RestClient.builder(), new ObjectMapper(), properties);
    }
}
