package com.repopilot.exception;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @InjectMocks
    GlobalExceptionHandler handler;

    @Test
    void handleStatus_returnsStatusCode() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        ResponseEntity<Map<String, String>> response = handler.handleStatus(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("detail", "Not found");
    }

    @Test
    void handleStatus_withNullReason() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.BAD_REQUEST, null);
        ResponseEntity<Map<String, String>> response = handler.handleStatus(ex);

        assertThat(response.getBody()).containsEntry("detail", "请求失败");
    }

    @Test
    void handleBadRequest_illegalStateException() {
        IllegalStateException ex = new IllegalStateException("Invalid state");
        ResponseEntity<Map<String, String>> response = handler.handleBadRequest(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("detail", "Invalid state");
    }

    @Test
    void handleBadRequest_illegalArgumentException() {
        IllegalArgumentException ex = new IllegalArgumentException("Bad argument");
        ResponseEntity<Map<String, String>> response = handler.handleBadRequest(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("detail", "Bad argument");
    }

    @Test
    void handleBadRequest_withChainedException() {
        IllegalStateException ex = new IllegalStateException("outer", new RuntimeException("inner message"));
        ResponseEntity<Map<String, String>> response = handler.handleBadRequest(ex);

        assertThat(response.getBody()).containsEntry("detail", "inner message");
    }

    @Test
    void handleBadRequest_withChainedExceptionNoMessage() {
        IllegalStateException ex = new IllegalStateException("outer", new RuntimeException());
        ResponseEntity<Map<String, String>> response = handler.handleBadRequest(ex);

        assertThat(response.getBody().get("detail")).isNotNull();
    }

    @Test
    void handleNpe_returnsInternalServerError() {
        NullPointerException ex = new NullPointerException("test");
        ResponseEntity<Map<String, String>> response = handler.handleNpe(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().get("detail")).contains("空指针异常");
    }

    @Test
    void handleNpe_withNoUsefulFrames() {
        NullPointerException ex = new NullPointerException("test");
        ResponseEntity<Map<String, String>> response = handler.handleNpe(ex);

        assertThat(response.getBody().get("detail")).contains("unknown");
    }

    @Test
    void handleDataAccess_returnsInternalServerError() {
        DataAccessException ex = new DataAccessResourceFailureException("DB connection failed");
        ResponseEntity<Map<String, String>> response = handler.handleDataAccess(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("detail", "数据库错误: DB connection failed");
    }

    @Test
    void handleDataAccess_withChainedException() {
        DataAccessException ex = new DataAccessResourceFailureException("outer", new RuntimeException("inner DB error"));
        ResponseEntity<Map<String, String>> response = handler.handleDataAccess(ex);

        assertThat(response.getBody()).containsEntry("detail", "数据库错误: inner DB error");
    }

    @Test
    void handleGeneric_returnsInternalServerError() {
        Exception ex = new RuntimeException("Something went wrong");
        ResponseEntity<Map<String, String>> response = handler.handleGeneric(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("detail", "Something went wrong");
    }

    @Test
    void handleGeneric_withChainedException() {
        Exception ex = new RuntimeException("outer", new IllegalStateException("inner"));
        ResponseEntity<Map<String, String>> response = handler.handleGeneric(ex);

        assertThat(response.getBody()).containsEntry("detail", "inner");
    }

    @Test
    void handleGeneric_withNullMessage() {
        Exception ex = new RuntimeException();
        ResponseEntity<Map<String, String>> response = handler.handleGeneric(ex);

        assertThat(response.getBody().get("detail")).isNotNull();
        assertThat(response.getBody().get("detail")).isNotBlank();
    }
}
