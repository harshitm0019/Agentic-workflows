package com.agentic.exception;

public class GitHubForbiddenException extends RuntimeException {

    public GitHubForbiddenException(String message) {
        super(message);
    }

    public GitHubForbiddenException(String message, Throwable cause) {
        super(message, cause);
    }
}
