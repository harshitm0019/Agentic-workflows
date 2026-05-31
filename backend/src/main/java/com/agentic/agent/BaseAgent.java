package com.agentic.agent;

import com.agentic.config.AgentConfig;
import com.agentic.dto.AgentInput;
import com.agentic.dto.AgentOutput;
import com.agentic.dto.GeminiRequest;
import com.agentic.dto.GeminiResponse;
import com.agentic.service.GeminiService;

public abstract class BaseAgent {

    protected final String name;
    protected final AgentConfig config;
    protected final GeminiService geminiService;

    protected BaseAgent(String name, AgentConfig config, GeminiService geminiService) {
        this.name = name;
        this.config = config;
        this.geminiService = geminiService;
    }

    public abstract AgentOutput execute(AgentInput input);

    protected String callGemini(String userPrompt) {
        GeminiRequest request = GeminiRequest.builder()
                .model(config.model())
                .systemPrompt(config.systemPrompt())
                .userPrompt(userPrompt)
                .temperature(config.temperature())
                .maxTokens(config.maxTokens())
                .build();

        GeminiResponse response = geminiService.generate(request);
        return response.getContent();
    }

    public String getName() {
        return name;
    }

    public AgentConfig getConfig() {
        return config;
    }
}
