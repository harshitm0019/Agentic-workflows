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
class ChangeSuggestionAgentTest {

    @Mock
    private GeminiService geminiService;

    private ChangeSuggestionAgent agent;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        AgentConfig config = new AgentConfig(
                "change-suggestion",
                "Generates code patches in unified diff format",
                List.of("code-generation", "patch-creation", "fix-suggestion"),
                "gemini-1.5-flash",
                0.4,
                8192,
                "You are an expert software engineer."
        );
        agent = new ChangeSuggestionAgent("change-suggestion", config, geminiService, objectMapper);
    }

    // --- Input Validation Tests ---

    @Test
    void shouldRejectNullData() {
        AgentInput input = new AgentInput("change-suggestion", null, null);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isFalse();
        assertThat(output.error()).contains("No findings or failure report provided");
        assertThat(output.requiresReview()).isFalse();
        verify(geminiService, never()).generate(any());
    }

    @Test
    void shouldRejectEmptyData() {
        AgentInput input = new AgentInput("change-suggestion", Map.of(), null);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isFalse();
        assertThat(output.error()).contains("No findings or failure report provided");
        verify(geminiService, never()).generate(any());
    }

    @Test
    void shouldRejectBlankFindings() {
        AgentInput input = new AgentInput("change-suggestion", Map.of("findings", "   "), null);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isFalse();
        assertThat(output.error()).contains("No findings or failure report provided");
        verify(geminiService, never()).generate(any());
    }

    @Test
    void shouldRejectBlankReport() {
        AgentInput input = new AgentInput("change-suggestion", Map.of("report", "  \n  "), null);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isFalse();
        assertThat(output.error()).contains("No findings or failure report provided");
        verify(geminiService, never()).generate(any());
    }

    // --- Findings Input Tests ---

    @Test
    void shouldGeneratePatchFromFindings() {
        String findings = """
                [{"filePath": "src/Main.java", "lineNumber": 10, "severity": "error", "message": "NullPointerException risk"}]
                """;
        AgentInput input = new AgentInput("change-suggestion", Map.of("findings", findings), null);

        String patchResponse = """
                ```diff
                --- a/src/Main.java
                +++ b/src/Main.java
                @@ -8,6 +8,9 @@
                     public void process(String value) {
                +        if (value == null) {
                +            throw new IllegalArgumentException("value cannot be null");
                +        }
                         value.toString();
                     }
                ```
                """;
        GeminiResponse mockResponse = GeminiResponse.builder()
                .content(patchResponse)
                .totalTokens(150)
                .build();
        when(geminiService.generate(any(GeminiRequest.class))).thenReturn(mockResponse);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isTrue();
        assertThat(output.error()).isNull();
        assertThat(output.requiresReview()).isTrue();
        assertThat(output.data().get("patch")).isNotNull();
        assertThat(output.data().get("patch").toString()).contains("--- a/src/Main.java");
        assertThat(output.data().get("patch").toString()).contains("+++ b/src/Main.java");
        assertThat(output.data().get("format")).isEqualTo("unified-diff");
    }

    @Test
    void shouldHandleFindingsAsObjectMap() {
        Map<String, Object> findingsMap = Map.of(
                "filePath", "src/App.java",
                "lineNumber", 5,
                "severity", "warning",
                "message", "Unused variable"
        );
        AgentInput input = new AgentInput("change-suggestion", Map.of("findings", List.of(findingsMap)), null);

        String patchResponse = """
                ```diff
                --- a/src/App.java
                +++ b/src/App.java
                @@ -3,5 +3,4 @@
                -    int unused = 42;
                     doSomething();
                ```
                """;
        GeminiResponse mockResponse = GeminiResponse.builder()
                .content(patchResponse)
                .totalTokens(100)
                .build();
        when(geminiService.generate(any(GeminiRequest.class))).thenReturn(mockResponse);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isTrue();
        assertThat(output.requiresReview()).isTrue();
        assertThat(output.data().get("patch").toString()).contains("--- a/src/App.java");
    }

    // --- Failure Report Input Tests ---

    @Test
    void shouldGeneratePatchFromFailureReport() {
        String report = """
                {"errorType": "compilation", "rootCause": "Missing import statement", "affectedComponents": ["UserService"], "suggestedFix": "Add import for java.util.List", "confidenceScore": 0.9}
                """;
        AgentInput input = new AgentInput("change-suggestion", Map.of("report", report), null);

        String patchResponse = """
                ```diff
                --- a/src/UserService.java
                +++ b/src/UserService.java
                @@ -1,4 +1,5 @@
                 package com.app.service;
                +import java.util.List;
                 
                 public class UserService {
                ```
                """;
        GeminiResponse mockResponse = GeminiResponse.builder()
                .content(patchResponse)
                .totalTokens(120)
                .build();
        when(geminiService.generate(any(GeminiRequest.class))).thenReturn(mockResponse);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isTrue();
        assertThat(output.error()).isNull();
        assertThat(output.requiresReview()).isTrue();
        assertThat(output.data().get("patch").toString()).contains("+import java.util.List;");
        assertThat(output.data().get("format")).isEqualTo("unified-diff");
    }

    @Test
    void shouldPreferFindingsOverReportWhenBothPresent() {
        AgentInput input = new AgentInput("change-suggestion",
                Map.of("findings", "some findings data", "report", "some report data"), null);

        String patchResponse = """
                --- a/file.java
                +++ b/file.java
                @@ -1 +1 @@
                -old
                +new
                """;
        GeminiResponse mockResponse = GeminiResponse.builder()
                .content(patchResponse)
                .totalTokens(50)
                .build();
        when(geminiService.generate(any(GeminiRequest.class))).thenReturn(mockResponse);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isTrue();
        // The agent should pick "findings" since it's checked first
        assertThat(output.data().get("patch")).isNotNull();
    }

    // --- No Fix Possible Tests ---

    @Test
    void shouldReturnFailureWhenGeminiIndicatesNoFix() {
        String findings = """
                [{"filePath": "config.xml", "lineNumber": 1, "severity": "error", "message": "Configuration schema is deprecated"}]
                """;
        AgentInput input = new AgentInput("change-suggestion", Map.of("findings", findings), null);

        String noFixResponse = "NO_FIX: The issue is in a third-party configuration schema that requires manual migration. " +
                "Please refer to the migration guide at https://example.com/migration.";
        GeminiResponse mockResponse = GeminiResponse.builder()
                .content(noFixResponse)
                .totalTokens(80)
                .build();
        when(geminiService.generate(any(GeminiRequest.class))).thenReturn(mockResponse);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isFalse();
        assertThat(output.error()).contains("Unable to generate a fix");
        assertThat(output.error()).contains("third-party configuration schema");
        assertThat(output.data().get("explanation")).isNotNull();
        assertThat(output.requiresReview()).isFalse();
    }

    @Test
    void shouldReturnFailureWhenResponseIsEmpty() {
        String findings = "[{\"filePath\": \"test.java\", \"lineNumber\": 1, \"severity\": \"error\", \"message\": \"Issue\"}]";
        AgentInput input = new AgentInput("change-suggestion", Map.of("findings", findings), null);

        GeminiResponse mockResponse = GeminiResponse.builder()
                .content("")
                .totalTokens(0)
                .build();
        when(geminiService.generate(any(GeminiRequest.class))).thenReturn(mockResponse);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isFalse();
        assertThat(output.error()).contains("Unable to generate a fix");
        assertThat(output.requiresReview()).isFalse();
    }

    @Test
    void shouldReturnFailureWhenResponseIsNull() {
        String findings = "[{\"filePath\": \"test.java\", \"lineNumber\": 1, \"severity\": \"error\", \"message\": \"Issue\"}]";
        AgentInput input = new AgentInput("change-suggestion", Map.of("findings", findings), null);

        GeminiResponse mockResponse = GeminiResponse.builder()
                .content(null)
                .totalTokens(0)
                .build();
        when(geminiService.generate(any(GeminiRequest.class))).thenReturn(mockResponse);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isFalse();
        assertThat(output.requiresReview()).isFalse();
    }

    // --- Malformed Response Tests ---

    @Test
    void shouldReturnFailureWhenResponseIsNotADiff() {
        String findings = "[{\"filePath\": \"test.java\", \"lineNumber\": 1, \"severity\": \"error\", \"message\": \"Bug found\"}]";
        AgentInput input = new AgentInput("change-suggestion", Map.of("findings", findings), null);

        String nonDiffResponse = "I reviewed the code and the issue is complex. You should refactor the entire module.";
        GeminiResponse mockResponse = GeminiResponse.builder()
                .content(nonDiffResponse)
                .totalTokens(40)
                .build();
        when(geminiService.generate(any(GeminiRequest.class))).thenReturn(mockResponse);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isFalse();
        assertThat(output.error()).contains("Unable to generate a valid unified diff patch");
        assertThat(output.requiresReview()).isFalse();
    }

    // --- Gemini API Error Tests ---

    @Test
    void shouldHandleGeminiApiException() {
        String findings = "[{\"filePath\": \"test.java\", \"lineNumber\": 1, \"severity\": \"error\", \"message\": \"Issue\"}]";
        AgentInput input = new AgentInput("change-suggestion", Map.of("findings", findings), null);

        when(geminiService.generate(any(GeminiRequest.class)))
                .thenThrow(new RuntimeException("Gemini API unavailable"));

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isFalse();
        assertThat(output.error()).contains("Failed to call Gemini API");
        assertThat(output.error()).contains("Gemini API unavailable");
        assertThat(output.requiresReview()).isFalse();
    }

    // --- Patch Extraction Tests ---

    @Test
    void shouldExtractPatchFromDiffCodeBlock() {
        String findings = "some findings";
        AgentInput input = new AgentInput("change-suggestion", Map.of("findings", findings), null);

        String response = """
                Here is the fix:
                ```diff
                --- a/src/Main.java
                +++ b/src/Main.java
                @@ -1,3 +1,4 @@
                +import java.util.Objects;
                 public class Main {
                ```
                This should fix the issue.
                """;
        GeminiResponse mockResponse = GeminiResponse.builder()
                .content(response)
                .totalTokens(100)
                .build();
        when(geminiService.generate(any(GeminiRequest.class))).thenReturn(mockResponse);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isTrue();
        assertThat(output.requiresReview()).isTrue();
        String patch = output.data().get("patch").toString();
        assertThat(patch).contains("--- a/src/Main.java");
        assertThat(patch).contains("+++ b/src/Main.java");
        assertThat(patch).contains("+import java.util.Objects;");
    }

    @Test
    void shouldExtractPatchFromGenericCodeBlock() {
        String findings = "some findings";
        AgentInput input = new AgentInput("change-suggestion", Map.of("findings", findings), null);

        String response = """
                ```
                --- a/src/App.java
                +++ b/src/App.java
                @@ -5,3 +5,4 @@
                +    // fixed
                     return value;
                ```
                """;
        GeminiResponse mockResponse = GeminiResponse.builder()
                .content(response)
                .totalTokens(80)
                .build();
        when(geminiService.generate(any(GeminiRequest.class))).thenReturn(mockResponse);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isTrue();
        assertThat(output.requiresReview()).isTrue();
        assertThat(output.data().get("patch").toString()).contains("--- a/src/App.java");
    }

    @Test
    void shouldExtractPatchFromPlainTextDiff() {
        String findings = "some findings";
        AgentInput input = new AgentInput("change-suggestion", Map.of("findings", findings), null);

        String response = """
                --- a/src/Service.java
                +++ b/src/Service.java
                @@ -10,3 +10,4 @@
                +    Objects.requireNonNull(param);
                     process(param);
                """;
        GeminiResponse mockResponse = GeminiResponse.builder()
                .content(response)
                .totalTokens(60)
                .build();
        when(geminiService.generate(any(GeminiRequest.class))).thenReturn(mockResponse);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isTrue();
        assertThat(output.requiresReview()).isTrue();
        assertThat(output.data().get("patch").toString()).startsWith("--- a/src/Service.java");
    }

    @Test
    void shouldExtractPatchWithDiffHeaderFromMixedResponse() {
        String findings = "some findings";
        AgentInput input = new AgentInput("change-suggestion", Map.of("findings", findings), null);

        String response = "Here is the fix I suggest:\n" +
                "--- a/src/Util.java\n" +
                "+++ b/src/Util.java\n" +
                "@@ -1,3 +1,3 @@\n" +
                "-    return null;\n" +
                "+    return Optional.empty();\n";
        GeminiResponse mockResponse = GeminiResponse.builder()
                .content(response)
                .totalTokens(70)
                .build();
        when(geminiService.generate(any(GeminiRequest.class))).thenReturn(mockResponse);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isTrue();
        assertThat(output.requiresReview()).isTrue();
        assertThat(output.data().get("patch").toString()).contains("--- a/src/Util.java");
    }

    // --- RequiresReview Flag Tests ---

    @Test
    void shouldAlwaysSetRequiresReviewOnSuccess() {
        String report = """
                {"errorType": "runtime", "rootCause": "NPE", "affectedComponents": ["Service"], "suggestedFix": "Add null check", "confidenceScore": 0.8}
                """;
        AgentInput input = new AgentInput("change-suggestion", Map.of("report", report), null);

        String patchResponse = "--- a/Service.java\n+++ b/Service.java\n@@ -1 +1 @@\n-old\n+new";
        GeminiResponse mockResponse = GeminiResponse.builder()
                .content(patchResponse)
                .totalTokens(50)
                .build();
        when(geminiService.generate(any(GeminiRequest.class))).thenReturn(mockResponse);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isTrue();
        assertThat(output.requiresReview()).isTrue();
    }

    @Test
    void shouldNotSetRequiresReviewOnFailure() {
        AgentInput input = new AgentInput("change-suggestion", null, null);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isFalse();
        assertThat(output.requiresReview()).isFalse();
    }

    // --- Previous Step Output Tests ---

    @Test
    void shouldWorkWithPreviousStepOutput() {
        String findings = """
                [{"filePath": "main.py", "lineNumber": 3, "severity": "suggestion", "message": "Add type hints"}]
                """;
        AgentInput input = new AgentInput("change-suggestion",
                Map.of("findings", findings),
                Map.of("summary", "Found 1 issue"));

        String patchResponse = """
                ```diff
                --- a/main.py
                +++ b/main.py
                @@ -1,3 +1,3 @@
                -def process(value):
                +def process(value: str) -> str:
                     return value.upper()
                ```
                """;
        GeminiResponse mockResponse = GeminiResponse.builder()
                .content(patchResponse)
                .totalTokens(80)
                .build();
        when(geminiService.generate(any(GeminiRequest.class))).thenReturn(mockResponse);

        AgentOutput output = agent.execute(input);

        assertThat(output.success()).isTrue();
        assertThat(output.requiresReview()).isTrue();
        assertThat(output.data().get("patch").toString()).contains("+def process(value: str) -> str:");
    }
}
