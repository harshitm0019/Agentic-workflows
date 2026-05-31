package com.agentic.agent;

import com.agentic.config.AgentConfig;
import com.agentic.dto.AgentInput;
import com.agentic.dto.AgentOutput;
import com.agentic.dto.GeminiRequest;
import com.agentic.dto.GeminiResponse;
import com.agentic.service.GeminiService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogAnalysisAgentTest {

    @Mock
    private GeminiService geminiService;

    private LogAnalysisAgent agent;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        AgentConfig config = new AgentConfig(
                "log-analysis",
                "Analyzes failure logs",
                List.of("log-parsing", "error-classification", "root-cause-analysis"),
                "gemini-1.5-flash",
                0.2,
                4096,
                "You are an expert at analyzing application and CI/CD failure logs."
        );
        agent = new LogAnalysisAgent("log-analysis", config, geminiService, objectMapper);
    }

    @Test
    void shouldRejectEmptyLogs() {
        AgentInput input = new AgentInput("log-analysis", Map.of("logs", ""), null);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isFalse();
        assertThat(output.error()).isEqualTo("Input logs are empty or too short");
        verify(geminiService, never()).generate(any());
    }

    @Test
    void shouldRejectNullLogs() {
        AgentInput input = new AgentInput("log-analysis", Map.of(), null);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isFalse();
        assertThat(output.error()).isEqualTo("Input logs are empty or too short");
        verify(geminiService, never()).generate(any());
    }

    @Test
    void shouldRejectNullData() {
        AgentInput input = new AgentInput("log-analysis", null, null);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isFalse();
        assertThat(output.error()).isEqualTo("Input logs are empty or too short");
        verify(geminiService, never()).generate(any());
    }

    @Test
    void shouldRejectLogsShorterThan10Chars() {
        AgentInput input = new AgentInput("log-analysis", Map.of("logs", "short"), null);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isFalse();
        assertThat(output.error()).isEqualTo("Input logs are empty or too short");
        verify(geminiService, never()).generate(any());
    }

    @Test
    void shouldTruncateLogsExceeding50KChars() {
        // Create a string longer than 50000 characters
        String prefix = "A".repeat(40000);
        String tail = "B".repeat(20000);
        String logs = prefix + tail; // 60000 chars total

        AgentInput input = new AgentInput("log-analysis", Map.of("logs", logs), null);

        String geminiResponseJson = """
                {
                    "errorType": "runtime",
                    "rootCause": "OutOfMemoryError",
                    "affectedComponents": ["service-a"],
                    "suggestedFix": "Increase heap size",
                    "confidenceScore": 0.9
                }
                """;
        GeminiResponse mockResponse = GeminiResponse.builder()
                .content(geminiResponseJson)
                .totalTokens(100)
                .build();
        when(geminiService.generate(any(GeminiRequest.class))).thenReturn(mockResponse);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isTrue();

        // Verify that the prompt sent to Gemini contains only the last 20000 chars
        ArgumentCaptor<GeminiRequest> requestCaptor = ArgumentCaptor.forClass(GeminiRequest.class);
        verify(geminiService).generate(requestCaptor.capture());

        String sentPrompt = requestCaptor.getValue().getUserPrompt();
        // The prompt should contain the tail (last 20000 chars of the 60000 char input)
        // The last 20000 chars of "A"*40000 + "B"*20000 = "B"*20000
        assertThat(sentPrompt).contains("B".repeat(20000));
        assertThat(sentPrompt).doesNotContain("A".repeat(40000));
    }

    @Test
    void shouldAcceptLogsAtExactly50KChars() {
        String logs = "X".repeat(50000);
        AgentInput input = new AgentInput("log-analysis", Map.of("logs", logs), null);

        String geminiResponseJson = """
                {
                    "errorType": "none",
                    "rootCause": "No issues found",
                    "affectedComponents": [],
                    "suggestedFix": "None needed",
                    "confidenceScore": 0.95
                }
                """;
        GeminiResponse mockResponse = GeminiResponse.builder()
                .content(geminiResponseJson)
                .totalTokens(80)
                .build();
        when(geminiService.generate(any(GeminiRequest.class))).thenReturn(mockResponse);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isTrue();

        // Verify no truncation happened - the full 50000 chars should be in the prompt
        ArgumentCaptor<GeminiRequest> requestCaptor = ArgumentCaptor.forClass(GeminiRequest.class);
        verify(geminiService).generate(requestCaptor.capture());

        String sentPrompt = requestCaptor.getValue().getUserPrompt();
        assertThat(sentPrompt).contains("X".repeat(50000));
    }

    @Test
    void shouldReturnReportFromGeminiResponse() {
        String logs = "2024-01-15 ERROR NullPointerException at com.app.Service.process(Service.java:42)";
        AgentInput input = new AgentInput("log-analysis", Map.of("logs", logs), null);

        String geminiResponseJson = """
                {
                    "errorType": "runtime",
                    "rootCause": "NullPointerException in Service.process",
                    "affectedComponents": ["com.app.Service", "com.app.Controller"],
                    "suggestedFix": "Add null check before calling process method",
                    "confidenceScore": 0.85
                }
                """;
        GeminiResponse mockResponse = GeminiResponse.builder()
                .content(geminiResponseJson)
                .totalTokens(120)
                .build();
        when(geminiService.generate(any(GeminiRequest.class))).thenReturn(mockResponse);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isTrue();
        assertThat(output.error()).isNull();
        assertThat(output.data().get("errorType")).isEqualTo("runtime");
        assertThat(output.data().get("rootCause")).isEqualTo("NullPointerException in Service.process");

        @SuppressWarnings("unchecked")
        List<String> components = (List<String>) output.data().get("affectedComponents");
        assertThat(components).containsExactly("com.app.Service", "com.app.Controller");
        assertThat(output.data().get("suggestedFix")).isEqualTo("Add null check before calling process method");
        assertThat(output.data().get("confidenceScore")).isEqualTo(0.85);
    }

    @Test
    void shouldReturnNoneErrorTypeWhenNoFailureDetected() {
        String logs = "2024-01-15 INFO Application started successfully on port 8080\n" +
                "2024-01-15 INFO Health check passed";
        AgentInput input = new AgentInput("log-analysis", Map.of("logs", logs), null);

        String geminiResponseJson = """
                {
                    "errorType": "none",
                    "rootCause": "No failure detected",
                    "affectedComponents": [],
                    "suggestedFix": "No fix needed",
                    "confidenceScore": 0.95
                }
                """;
        GeminiResponse mockResponse = GeminiResponse.builder()
                .content(geminiResponseJson)
                .totalTokens(60)
                .build();
        when(geminiService.generate(any(GeminiRequest.class))).thenReturn(mockResponse);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isTrue();
        assertThat(output.error()).isNull();
        assertThat(output.data().get("errorType")).isEqualTo("none");
        assertThat(output.data().get("confidenceScore")).isEqualTo(0.95);
    }

    @Test
    void shouldHandleGeminiResponseWrappedInMarkdownCodeBlock() {
        String logs = "ERROR: Failed to connect to database on port 5432";
        AgentInput input = new AgentInput("log-analysis", Map.of("logs", logs), null);

        String wrappedResponse = """
                ```json
                {
                    "errorType": "configuration",
                    "rootCause": "Database connection refused",
                    "affectedComponents": ["database", "connection-pool"],
                    "suggestedFix": "Check database is running and port 5432 is accessible",
                    "confidenceScore": 0.9
                }
                ```
                """;
        GeminiResponse mockResponse = GeminiResponse.builder()
                .content(wrappedResponse)
                .totalTokens(90)
                .build();
        when(geminiService.generate(any(GeminiRequest.class))).thenReturn(mockResponse);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isTrue();
        assertThat(output.data().get("errorType")).isEqualTo("configuration");
        assertThat(output.data().get("rootCause")).isEqualTo("Database connection refused");

        @SuppressWarnings("unchecked")
        List<String> components = (List<String>) output.data().get("affectedComponents");
        assertThat(components).containsExactly("database", "connection-pool");
    }

    @Test
    void shouldHandleMalformedGeminiResponseGracefully() {
        String logs = "Some valid log content exceeding ten characters";
        AgentInput input = new AgentInput("log-analysis", Map.of("logs", logs), null);

        String malformedResponse = "I analyzed the logs and found some issues. The main problem is a memory leak.";
        GeminiResponse mockResponse = GeminiResponse.builder()
                .content(malformedResponse)
                .totalTokens(30)
                .build();
        when(geminiService.generate(any(GeminiRequest.class))).thenReturn(mockResponse);

        AgentOutput output = agent.execute(input);

        // Should gracefully return default report
        assertThat(output.success()).isTrue();
        assertThat(output.data().get("errorType")).isEqualTo("none");
        assertThat(output.data().get("confidenceScore")).isEqualTo(0.0);
    }

    @Test
    void shouldHandleGeminiApiException() {
        String logs = "Some valid log content exceeding ten characters";
        AgentInput input = new AgentInput("log-analysis", Map.of("logs", logs), null);

        when(geminiService.generate(any(GeminiRequest.class)))
                .thenThrow(new RuntimeException("Gemini API unavailable"));

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isFalse();
        assertThat(output.error()).contains("Failed to call Gemini API");
        assertThat(output.error()).contains("Gemini API unavailable");
    }

    @Test
    void shouldHandleNullGeminiResponseContent() {
        String logs = "Some valid log content exceeding ten characters";
        AgentInput input = new AgentInput("log-analysis", Map.of("logs", logs), null);

        GeminiResponse mockResponse = GeminiResponse.builder()
                .content(null)
                .totalTokens(0)
                .build();
        when(geminiService.generate(any(GeminiRequest.class))).thenReturn(mockResponse);

        AgentOutput output = agent.execute(input);

        // Should return default report with "none" error type
        assertThat(output.success()).isTrue();
        assertThat(output.data().get("errorType")).isEqualTo("none");
        assertThat(output.data().get("confidenceScore")).isEqualTo(0.0);
    }

    @Test
    void shouldNotRequireReview() {
        String logs = "2024-01-15 ERROR Connection timeout after 30s";
        AgentInput input = new AgentInput("log-analysis", Map.of("logs", logs), null);

        String geminiResponseJson = """
                {
                    "errorType": "runtime",
                    "rootCause": "Connection timeout",
                    "affectedComponents": ["http-client"],
                    "suggestedFix": "Increase timeout or check network",
                    "confidenceScore": 0.8
                }
                """;
        GeminiResponse mockResponse = GeminiResponse.builder()
                .content(geminiResponseJson)
                .totalTokens(50)
                .build();
        when(geminiService.generate(any(GeminiRequest.class))).thenReturn(mockResponse);

        AgentOutput output = agent.execute(input);

        assertThat(output.requiresReview()).isFalse();
    }
}
