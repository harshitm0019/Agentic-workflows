package com.agentic.agent;

import com.agentic.config.AgentConfig;
import com.agentic.dto.AgentInput;
import com.agentic.dto.AgentOutput;
import com.agentic.dto.GeminiRequest;
import com.agentic.dto.GeminiResponse;
import com.agentic.service.LangChainGeminiService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BaseAgentTest {

    @Mock
    private LangChainGeminiService geminiService;

    @Test
    void callGeminiShouldUseAgentConfigSettings() {
        AgentConfig config = new AgentConfig(
                "test-agent",
                "A test agent",
                List.of("testing"),
                "gemini-1.5-flash",
                0.5,
                2048,
                "You are a test agent."
        );

        TestAgent agent = new TestAgent("test-agent", config, geminiService);

        GeminiResponse mockResponse = GeminiResponse.builder()
                .content("test response")
                .promptTokens(10)
                .completionTokens(20)
                .totalTokens(30)
                .build();
        when(geminiService.generate(any(GeminiRequest.class))).thenReturn(mockResponse);

        String result = agent.callGemini("Hello, world!");

        assertThat(result).isEqualTo("test response");

        ArgumentCaptor<GeminiRequest> captor = ArgumentCaptor.forClass(GeminiRequest.class);
        verify(geminiService).generate(captor.capture());

        GeminiRequest capturedRequest = captor.getValue();
        assertThat(capturedRequest.getModel()).isEqualTo("gemini-1.5-flash");
        assertThat(capturedRequest.getSystemPrompt()).isEqualTo("You are a test agent.");
        assertThat(capturedRequest.getUserPrompt()).isEqualTo("Hello, world!");
        assertThat(capturedRequest.getTemperature()).isEqualTo(0.5);
        assertThat(capturedRequest.getMaxTokens()).isEqualTo(2048);
    }

    @Test
    void shouldExposeNameAndConfig() {
        AgentConfig config = new AgentConfig(
                "my-agent",
                "Description",
                List.of("cap1"),
                "gemini-1.5-flash",
                0.7,
                4096,
                "System prompt"
        );

        TestAgent agent = new TestAgent("my-agent", config, geminiService);

        assertThat(agent.getName()).isEqualTo("my-agent");
        assertThat(agent.getConfig()).isEqualTo(config);
    }

    // Concrete implementation for testing the abstract class
    private static class TestAgent extends BaseAgent {

        protected TestAgent(String name, AgentConfig config, LangChainGeminiService geminiService) {
            super(name, config, geminiService);
        }

        @Override
        public AgentOutput execute(AgentInput input) {
            String response = callGemini("test prompt");
            return new AgentOutput(true, Map.of("result", response), false, null);
        }
    }
}
