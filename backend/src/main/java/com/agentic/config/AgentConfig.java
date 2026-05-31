package com.agentic.config;

import java.util.List;

public record AgentConfig(
    String name,
    String description,
    List<String> capabilities,
    String model,
    double temperature,
    int maxTokens,
    String systemPrompt
) {}
