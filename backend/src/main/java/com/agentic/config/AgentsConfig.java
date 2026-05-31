package com.agentic.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Configuration
@ConfigurationProperties
@Slf4j
@Getter
@Setter
public class AgentsConfig {

    private List<AgentConfigProperties> agents = new ArrayList<>();

    private List<AgentConfig> validAgents;

    @PostConstruct
    public void validateAgents() {
        List<AgentConfig> valid = new ArrayList<>();

        for (AgentConfigProperties props : agents) {
            AgentConfig agent = props.toAgentConfig();
            List<String> errors = validateAgent(agent);
            if (errors.isEmpty()) {
                valid.add(agent);
                log.info("Loaded agent: {}", agent.name());
            } else {
                log.error("Invalid agent definition skipped. Errors: {}", errors);
            }
        }

        this.validAgents = Collections.unmodifiableList(valid);
        log.info("Loaded {} valid agent(s) out of {} defined", validAgents.size(), agents.size());
    }

    private List<String> validateAgent(AgentConfig agent) {
        List<String> errors = new ArrayList<>();

        if (agent.name() == null || agent.name().isBlank()) {
            errors.add("Missing required field: name");
        }
        if (agent.capabilities() == null || agent.capabilities().isEmpty()) {
            errors.add("Missing required field: capabilities");
        }
        if (agent.systemPrompt() == null || agent.systemPrompt().isBlank()) {
            errors.add("Missing required field: systemPrompt");
        }

        return errors;
    }

    public Optional<AgentConfig> getAgent(String name) {
        return validAgents.stream()
                .filter(a -> a.name().equals(name))
                .findFirst();
    }

    public List<AgentConfig> getValidAgents() {
        return validAgents;
    }

    @Getter
    @Setter
    public static class AgentConfigProperties {
        private String name;
        private String description;
        private List<String> capabilities = new ArrayList<>();
        private String model;
        private double temperature;
        private int maxTokens;
        private String systemPrompt;

        public AgentConfig toAgentConfig() {
            return new AgentConfig(name, description, capabilities, model, temperature, maxTokens, systemPrompt);
        }
    }
}
