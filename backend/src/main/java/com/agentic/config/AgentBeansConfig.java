package com.agentic.config;

import com.agentic.agent.BaseAgent;
import com.agentic.agent.ChangeSuggestionAgent;
import com.agentic.agent.CodeReviewAgent;
import com.agentic.agent.LogAnalysisAgent;
// NOTE: Switched from raw GeminiService to LangChain4j-based service.
// Original import was: import com.agentic.service.GeminiService;
import com.agentic.service.LangChainGeminiService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Registers all valid agent definitions as Spring-managed BaseAgent beans,
 * accessible via a Map keyed by agent name.
 */
@Configuration
@Slf4j
public class AgentBeansConfig {

    @Bean
    public Map<String, BaseAgent> agents(AgentsConfig agentsConfig, LangChainGeminiService geminiService, ObjectMapper objectMapper) {
        Map<String, BaseAgent> agentMap = new HashMap<>();

        for (AgentConfig config : agentsConfig.getValidAgents()) {
            BaseAgent agent = createAgent(config, geminiService, objectMapper);
            if (agent != null) {
                agentMap.put(config.name(), agent);
                log.info("Registered agent bean: {}", config.name());
            }
        }

        log.info("Registered {} agent bean(s)", agentMap.size());
        return agentMap;
    }

    private BaseAgent createAgent(AgentConfig config, LangChainGeminiService geminiService, ObjectMapper objectMapper) {
        return switch (config.name()) {
            case "code-review" -> new CodeReviewAgent(config.name(), config, geminiService, objectMapper);
            case "log-analysis" -> new LogAnalysisAgent(config.name(), config, geminiService, objectMapper);
            case "change-suggestion" -> new ChangeSuggestionAgent(config.name(), config, geminiService, objectMapper);
            default -> {
                log.warn("No agent implementation for: {}", config.name());
                yield null;
            }
        };
    }
}
