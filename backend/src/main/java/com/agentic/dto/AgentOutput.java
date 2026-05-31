package com.agentic.dto;

import java.util.Map;

public record AgentOutput(
    boolean success,
    Map<String, Object> data,
    boolean requiresReview,
    String error
) {}
