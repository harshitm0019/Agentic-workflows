package com.agentic.dto;

import java.util.Map;

public record AgentInput(
    String type,
    Map<String, Object> data,
    Map<String, Object> previousStepOutput
) {}
