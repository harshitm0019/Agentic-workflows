package com.agentic.exception;

public class QuotaExhaustedException extends RuntimeException {

    public QuotaExhaustedException(String message) {
        super(message);
    }

    public QuotaExhaustedException(String message, Throwable cause) {
        super(message, cause);
    }
}
