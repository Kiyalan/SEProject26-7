package com.repopilot.client;

public class CodeWikiException extends RuntimeException {
    private final String operation;
    private final boolean retryable;

    public CodeWikiException(String operation, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.operation = operation;
        this.retryable = retryable;
    }

    public String operation() {
        return operation;
    }

    public boolean retryable() {
        return retryable;
    }
}
