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
class CodeReviewAgentTest {

    @Mock
    private GeminiService geminiService;

    private CodeReviewAgent agent;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        AgentConfig config = new AgentConfig(
                "code-review",
                "Analyzes code diffs",
                List.of("code-analysis", "bug-detection"),
                "gemini-1.5-flash",
                0.3,
                4096,
                "You are an expert code reviewer."
        );
        agent = new CodeReviewAgent("code-review", config, geminiService, objectMapper);
    }

    @Test
    void shouldRejectEmptyDiff() {
        AgentInput input = new AgentInput("code-review", Map.of("diff", ""), null);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isFalse();
        assertThat(output.error()).isEqualTo("Input diff is empty");
        verify(geminiService, never()).generate(any());
    }

    @Test
    void shouldRejectNullDiff() {
        AgentInput input = new AgentInput("code-review", Map.of(), null);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isFalse();
        assertThat(output.error()).isEqualTo("Input diff is empty");
        verify(geminiService, never()).generate(any());
    }

    @Test
    void shouldRejectNullData() {
        AgentInput input = new AgentInput("code-review", null, null);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isFalse();
        assertThat(output.error()).isEqualTo("Input diff is empty");
        verify(geminiService, never()).generate(any());
    }

    @Test
    void shouldRejectBlankDiff() {
        AgentInput input = new AgentInput("code-review", Map.of("diff", "   \n  \n  "), null);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isFalse();
        assertThat(output.error()).isEqualTo("Input diff is empty");
        verify(geminiService, never()).generate(any());
    }

    @Test
    void shouldRejectDiffExceeding2000Lines() {
        StringBuilder largeDiff = new StringBuilder();
        for (int i = 0; i < 2001; i++) {
            largeDiff.append("+ line ").append(i).append("\n");
        }
        AgentInput input = new AgentInput("code-review", Map.of("diff", largeDiff.toString()), null);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isFalse();
        assertThat(output.error()).contains("exceeds maximum size limit of 2000 lines");
        verify(geminiService, never()).generate(any());
    }

    @Test
    void shouldAcceptDiffWithExactly2000Lines() {
        StringBuilder diff = new StringBuilder();
        for (int i = 0; i < 1999; i++) {
            diff.append("+ line ").append(i).append("\n");
        }
        diff.append("+ last line"); // 2000th line, no trailing newline
        AgentInput input = new AgentInput("code-review", Map.of("diff", diff.toString()), null);

        GeminiResponse mockResponse = GeminiResponse.builder()
                .content("[]")
                .totalTokens(50)
                .build();
        when(geminiService.generate(any(GeminiRequest.class))).thenReturn(mockResponse);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isTrue();
    }

    @Test
    void shouldReturnFindingsFromGeminiResponse() {
        String diff = "--- a/src/Main.java\n+++ b/src/Main.java\n@@ -1,3 +1,3 @@\n-int x = null;\n+int x = 0;";
        AgentInput input = new AgentInput("code-review", Map.of("diff", diff), null);

        String geminiResponseJson = """
                [
                    {
                        "filePath": "src/Main.java",
                        "lineNumber": 1,
                        "severity": "error",
                        "message": "Assigning null to primitive int type"
                    }
                ]
                """;
        GeminiResponse mockResponse = GeminiResponse.builder()
                .content(geminiResponseJson)
                .totalTokens(100)
                .build();
        when(geminiService.generate(any(GeminiRequest.class))).thenReturn(mockResponse);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isTrue();
        assertThat(output.error()).isNull();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) output.data().get("findings");
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).get("filePath")).isEqualTo("src/Main.java");
        assertThat(findings.get(0).get("lineNumber")).isEqualTo(1);
        assertThat(findings.get(0).get("severity")).isEqualTo("error");
        assertThat(findings.get(0).get("message")).isEqualTo("Assigning null to primitive int type");

        assertThat(output.data().get("summary")).isEqualTo("Found 1 issue(s) in the code diff.");
    }

    @Test
    void shouldReturnEmptyFindingsWithSummaryWhenNoIssues() {
        String diff = "--- a/src/Main.java\n+++ b/src/Main.java\n@@ -1,3 +1,3 @@\n-int x = 0;\n+int x = 1;";
        AgentInput input = new AgentInput("code-review", Map.of("diff", diff), null);

        GeminiResponse mockResponse = GeminiResponse.builder()
                .content("[]")
                .totalTokens(50)
                .build();
        when(geminiService.generate(any(GeminiRequest.class))).thenReturn(mockResponse);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isTrue();
        assertThat(output.error()).isNull();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) output.data().get("findings");
        assertThat(findings).isEmpty();
        assertThat(output.data().get("summary")).isEqualTo("No issues found in the code diff.");
    }

    @Test
    void shouldHandleGeminiResponseWrappedInMarkdownCodeBlock() {
        String diff = "+some code change";
        AgentInput input = new AgentInput("code-review", Map.of("diff", diff), null);

        String wrappedResponse = """
                ```json
                [
                    {
                        "filePath": "app.js",
                        "lineNumber": 42,
                        "severity": "warning",
                        "message": "Unused variable"
                    }
                ]
                ```
                """;
        GeminiResponse mockResponse = GeminiResponse.builder()
                .content(wrappedResponse)
                .totalTokens(80)
                .build();
        when(geminiService.generate(any(GeminiRequest.class))).thenReturn(mockResponse);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) output.data().get("findings");
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).get("filePath")).isEqualTo("app.js");
        assertThat(findings.get(0).get("severity")).isEqualTo("warning");
    }

    @Test
    void shouldHandleMalformedGeminiResponseGracefully() {
        String diff = "+some code change";
        AgentInput input = new AgentInput("code-review", Map.of("diff", diff), null);

        String malformedResponse = "This is not valid JSON at all. The code looks fine to me.";
        GeminiResponse mockResponse = GeminiResponse.builder()
                .content(malformedResponse)
                .totalTokens(30)
                .build();
        when(geminiService.generate(any(GeminiRequest.class))).thenReturn(mockResponse);

        AgentOutput output = agent.execute(input);

        // Should gracefully return empty findings rather than throwing an error
        assertThat(output.success()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) output.data().get("findings");
        assertThat(findings).isEmpty();
        assertThat(output.data().get("summary")).isEqualTo("No issues found in the code diff.");
    }

    @Test
    void shouldHandleEmptyGeminiResponseGracefully() {
        String diff = "+some code change";
        AgentInput input = new AgentInput("code-review", Map.of("diff", diff), null);

        GeminiResponse mockResponse = GeminiResponse.builder()
                .content("")
                .totalTokens(10)
                .build();
        when(geminiService.generate(any(GeminiRequest.class))).thenReturn(mockResponse);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) output.data().get("findings");
        assertThat(findings).isEmpty();
    }

    @Test
    void shouldHandleGeminiApiException() {
        String diff = "+some code change";
        AgentInput input = new AgentInput("code-review", Map.of("diff", diff), null);

        when(geminiService.generate(any(GeminiRequest.class)))
                .thenThrow(new RuntimeException("Gemini API unavailable"));

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isFalse();
        assertThat(output.error()).contains("Failed to call Gemini API");
        assertThat(output.error()).contains("Gemini API unavailable");
    }

    @Test
    void shouldHandleMultipleFindings() {
        String diff = "+code with multiple issues";
        AgentInput input = new AgentInput("code-review", Map.of("diff", diff), null);

        String multipleFindings = """
                [
                    {"filePath": "a.java", "lineNumber": 10, "severity": "error", "message": "NullPointerException risk"},
                    {"filePath": "b.java", "lineNumber": 25, "severity": "warning", "message": "Deprecated method usage"},
                    {"filePath": "c.java", "lineNumber": 3, "severity": "suggestion", "message": "Consider using streams"}
                ]
                """;
        GeminiResponse mockResponse = GeminiResponse.builder()
                .content(multipleFindings)
                .totalTokens(200)
                .build();
        when(geminiService.generate(any(GeminiRequest.class))).thenReturn(mockResponse);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) output.data().get("findings");
        assertThat(findings).hasSize(3);
        assertThat(output.data().get("summary")).isEqualTo("Found 3 issue(s) in the code diff.");
    }

    @Test
    void shouldHandleResponseWithTextBeforeJson() {
        String diff = "+some code";
        AgentInput input = new AgentInput("code-review", Map.of("diff", diff), null);

        String responseWithPreamble = """
                Here are my findings from the code review:
                [{"filePath": "main.py", "lineNumber": 5, "severity": "suggestion", "message": "Add type hints"}]
                """;
        GeminiResponse mockResponse = GeminiResponse.builder()
                .content(responseWithPreamble)
                .totalTokens(60)
                .build();
        when(geminiService.generate(any(GeminiRequest.class))).thenReturn(mockResponse);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) output.data().get("findings");
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).get("filePath")).isEqualTo("main.py");
    }

    @Test
    void shouldNotRequireReview() {
        String diff = "+simple change";
        AgentInput input = new AgentInput("code-review", Map.of("diff", diff), null);

        GeminiResponse mockResponse = GeminiResponse.builder()
                .content("[]")
                .totalTokens(20)
                .build();
        when(geminiService.generate(any(GeminiRequest.class))).thenReturn(mockResponse);

        AgentOutput output = agent.execute(input);

        assertThat(output.requiresReview()).isFalse();
    }

    @Test
    void shouldHandleNullGeminiResponseContent() {
        String diff = "+some code change";
        AgentInput input = new AgentInput("code-review", Map.of("diff", diff), null);

        GeminiResponse mockResponse = GeminiResponse.builder()
                .content(null)
                .totalTokens(0)
                .build();
        when(geminiService.generate(any(GeminiRequest.class))).thenReturn(mockResponse);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) output.data().get("findings");
        assertThat(findings).isEmpty();
    }
}
