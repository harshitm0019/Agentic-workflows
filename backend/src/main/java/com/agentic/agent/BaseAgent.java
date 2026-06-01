package com.agentic.agent;

import com.agentic.config.AgentConfig;
import com.agentic.dto.AgentInput;
import com.agentic.dto.AgentOutput;
import com.agentic.dto.GeminiRequest;
import com.agentic.dto.GeminiResponse;
// NOTE: Switched from raw GeminiService to LangChain4j-based service for cleaner LLM abstraction.
// Original import was: import com.agentic.service.GeminiService;
import com.agentic.service.LangChainGeminiService;

public abstract class BaseAgent {

    protected final String name;
    protected final AgentConfig config;
    // NOTE: Was GeminiService (raw WebClient). Now uses LangChain4j for cleaner provider abstraction.
    protected final LangChainGeminiService geminiService;

    protected BaseAgent(String name, AgentConfig config, LangChainGeminiService geminiService) {
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
