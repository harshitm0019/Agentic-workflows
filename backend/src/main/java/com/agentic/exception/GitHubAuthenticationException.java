package com.agentic.exception;

public class GitHubAuthenticationException extends RuntimeException {

    public GitHubAuthenticationException(String message) {
        super(message);
    }

    public GitHubAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
