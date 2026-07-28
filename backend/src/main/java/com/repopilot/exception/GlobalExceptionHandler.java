package com.repopilot.exception;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> handleStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("detail", ex.getReason() == null ? "请求失败" : ex.getReason()));
    }

    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    ResponseEntity<Map<String, String>> handleBadRequest(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("detail", rootMessage(ex)));
    }

    @ExceptionHandler(NullPointerException.class)
    ResponseEntity<Map<String, String>> handleNpe(NullPointerException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("detail", "空指针异常: " + firstUsefulFrame(ex)));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, String>> handleDataAccess(DataAccessException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("detail", "数据库错误: " + rootMessage(ex)));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> handleGeneric(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("detail", rootMessage(ex)));
    }

    private String rootMessage(Throwable ex) {
        Throwable cur = ex;
        while (cur.getCause() != null) {
            cur = cur.getCause();
        }
        String message = cur.getMessage();
        if (message == null || message.isBlank()) {
            return cur.getClass().getSimpleName();
        }
        return message;
    }

    private String firstUsefulFrame(NullPointerException ex) {
        for (StackTraceElement frame : ex.getStackTrace()) {
            if (frame.getClassName().startsWith("com.repopilot.")) {
                return frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber();
            }
        }
        return "unknown";
    }
}
