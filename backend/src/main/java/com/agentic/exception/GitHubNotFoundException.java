package com.agentic.exception;

public class GitHubNotFoundException extends RuntimeException {

    public GitHubNotFoundException(String message) {
        super(message);
    }

    public GitHubNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
