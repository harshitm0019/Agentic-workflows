package com.agentic.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentsConfigTest {

    @Test
    void shouldLoadValidAgentsAndSkipInvalid() {
        AgentsConfig config = new AgentsConfig();

        List<AgentsConfig.AgentConfigProperties> agents = new ArrayList<>();

        // Valid agent
        AgentsConfig.AgentConfigProperties valid = new AgentsConfig.AgentConfigProperties();
        valid.setName("code-review");
        valid.setDescription("Reviews code");
        valid.setCapabilities(List.of("code-analysis"));
        valid.setModel("gemini-1.5-flash");
        valid.setTemperature(0.3);
        valid.setMaxTokens(4096);
        valid.setSystemPrompt("You are a code reviewer.");
        agents.add(valid);

        // Invalid agent - missing name
        AgentsConfig.AgentConfigProperties missingName = new AgentsConfig.AgentConfigProperties();
        missingName.setDescription("No name agent");
        missingName.setCapabilities(List.of("test"));
        missingName.setSystemPrompt("Some prompt");
        agents.add(missingName);

        // Invalid agent - missing capabilities
        AgentsConfig.AgentConfigProperties missingCapabilities = new AgentsConfig.AgentConfigProperties();
        missingCapabilities.setName("bad-agent");
        missingCapabilities.setSystemPrompt("Some prompt");
        agents.add(missingCapabilities);

        // Invalid agent - missing systemPrompt
        AgentsConfig.AgentConfigProperties missingPrompt = new AgentsConfig.AgentConfigProperties();
        missingPrompt.setName("another-bad-agent");
        missingPrompt.setCapabilities(List.of("test"));
        agents.add(missingPrompt);

        config.setAgents(agents);
        config.validateAgents();

        assertThat(config.getValidAgents()).hasSize(1);
        assertThat(config.getValidAgents().get(0).name()).isEqualTo("code-review");
    }

    @Test
    void shouldReturnEmptyListWhenNoAgentsDefined() {
        AgentsConfig config = new AgentsConfig();
        config.setAgents(new ArrayList<>());
        config.validateAgents();

        assertThat(config.getValidAgents()).isEmpty();
    }

    @Test
    void shouldFindAgentByName() {
        AgentsConfig config = new AgentsConfig();

        List<AgentsConfig.AgentConfigProperties> agents = new ArrayList<>();
        AgentsConfig.AgentConfigProperties props = new AgentsConfig.AgentConfigProperties();
        props.setName("log-analysis");
        props.setDescription("Analyzes logs");
        props.setCapabilities(List.of("log-parsing"));
        props.setModel("gemini-1.5-flash");
        props.setTemperature(0.2);
        props.setMaxTokens(4096);
        props.setSystemPrompt("You analyze logs.");
        agents.add(props);

        config.setAgents(agents);
        config.validateAgents();

        assertThat(config.getAgent("log-analysis")).isPresent();
        assertThat(config.getAgent("log-analysis").get().name()).isEqualTo("log-analysis");
        assertThat(config.getAgent("nonexistent")).isEmpty();
    }

    @Test
    void shouldLoadAllThreeDefaultAgents() {
        AgentsConfig config = new AgentsConfig();

        List<AgentsConfig.AgentConfigProperties> agents = new ArrayList<>();

        AgentsConfig.AgentConfigProperties codeReview = new AgentsConfig.AgentConfigProperties();
        codeReview.setName("code-review");
        codeReview.setCapabilities(List.of("code-analysis", "bug-detection"));
        codeReview.setModel("gemini-1.5-flash");
        codeReview.setTemperature(0.3);
        codeReview.setMaxTokens(4096);
        codeReview.setSystemPrompt("Review code.");
        agents.add(codeReview);

        AgentsConfig.AgentConfigProperties logAnalysis = new AgentsConfig.AgentConfigProperties();
        logAnalysis.setName("log-analysis");
        logAnalysis.setCapabilities(List.of("log-parsing"));
        logAnalysis.setModel("gemini-1.5-flash");
        logAnalysis.setTemperature(0.2);
        logAnalysis.setMaxTokens(4096);
        logAnalysis.setSystemPrompt("Analyze logs.");
        agents.add(logAnalysis);

        AgentsConfig.AgentConfigProperties changeSuggestion = new AgentsConfig.AgentConfigProperties();
        changeSuggestion.setName("change-suggestion");
        changeSuggestion.setCapabilities(List.of("code-generation"));
        changeSuggestion.setModel("gemini-1.5-flash");
        changeSuggestion.setTemperature(0.4);
        changeSuggestion.setMaxTokens(8192);
        changeSuggestion.setSystemPrompt("Generate patches.");
        agents.add(changeSuggestion);

        config.setAgents(agents);
        config.validateAgents();

        assertThat(config.getValidAgents()).hasSize(3);
        assertThat(config.getAgent("code-review")).isPresent();
        assertThat(config.getAgent("log-analysis")).isPresent();
        assertThat(config.getAgent("change-suggestion")).isPresent();
    }
}
