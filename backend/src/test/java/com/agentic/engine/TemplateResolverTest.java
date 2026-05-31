package com.agentic.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateResolverTest {

    private TemplateResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new TemplateResolver();
    }

    @Test
    void shouldResolveTriggerFieldReference() {
        Map<String, String> input = Map.of("diff", "{{trigger.diff}}");
        Map<String, Object> triggerPayload = Map.of("diff", "some diff content");
        Map<String, Map<String, Object>> stepOutputs = Map.of();

        Map<String, String> resolved = resolver.resolve(input, triggerPayload, stepOutputs);

        assertThat(resolved.get("diff")).isEqualTo("some diff content");
    }

    @Test
    void shouldResolveStepOutputFieldReference() {
        Map<String, String> input = Map.of("findings", "{{steps.review-code.output.findings}}");
        Map<String, Object> triggerPayload = Map.of();
        Map<String, Map<String, Object>> stepOutputs = Map.of(
                "review-code", Map.of("findings", "[{\"severity\":\"warning\"}]")
        );

        Map<String, String> resolved = resolver.resolve(input, triggerPayload, stepOutputs);

        assertThat(resolved.get("findings")).isEqualTo("[{\"severity\":\"warning\"}]");
    }

    @Test
    void shouldResolveStepOutputEntireOutput() {
        Map<String, String> input = Map.of("report", "{{steps.analyze-logs.output}}");
        Map<String, Object> triggerPayload = Map.of();
        Map<String, Map<String, Object>> stepOutputs = Map.of(
                "analyze-logs", Map.of("errorType", "runtime", "rootCause", "NPE")
        );

        Map<String, String> resolved = resolver.resolve(input, triggerPayload, stepOutputs);

        assertThat(resolved.get("report")).contains("errorType");
        assertThat(resolved.get("report")).contains("runtime");
    }

    @Test
    void shouldReturnEmptyStringForMissingTriggerField() {
        Map<String, String> input = Map.of("diff", "{{trigger.nonexistent}}");
        Map<String, Object> triggerPayload = Map.of("other", "value");
        Map<String, Map<String, Object>> stepOutputs = Map.of();

        Map<String, String> resolved = resolver.resolve(input, triggerPayload, stepOutputs);

        assertThat(resolved.get("diff")).isEmpty();
    }

    @Test
    void shouldReturnEmptyStringForMissingStepOutput() {
        Map<String, String> input = Map.of("data", "{{steps.unknown-step.output.field}}");
        Map<String, Object> triggerPayload = Map.of();
        Map<String, Map<String, Object>> stepOutputs = Map.of();

        Map<String, String> resolved = resolver.resolve(input, triggerPayload, stepOutputs);

        assertThat(resolved.get("data")).isEmpty();
    }

    @Test
    void shouldReturnEmptyStringForMissingFieldInStepOutput() {
        Map<String, String> input = Map.of("data", "{{steps.review-code.output.missing}}");
        Map<String, Object> triggerPayload = Map.of();
        Map<String, Map<String, Object>> stepOutputs = Map.of(
                "review-code", Map.of("findings", "some data")
        );

        Map<String, String> resolved = resolver.resolve(input, triggerPayload, stepOutputs);

        assertThat(resolved.get("data")).isEmpty();
    }

    @Test
    void shouldPreservePlainTextWithoutTemplates() {
        Map<String, String> input = Map.of("key", "plain value");
        Map<String, Object> triggerPayload = Map.of();
        Map<String, Map<String, Object>> stepOutputs = Map.of();

        Map<String, String> resolved = resolver.resolve(input, triggerPayload, stepOutputs);

        assertThat(resolved.get("key")).isEqualTo("plain value");
    }

    @Test
    void shouldResolveMultipleTemplatesInSingleValue() {
        Map<String, String> input = Map.of("combined", "Diff: {{trigger.diff}} PR: {{trigger.pr_number}}");
        Map<String, Object> triggerPayload = Map.of("diff", "abc", "pr_number", 42);
        Map<String, Map<String, Object>> stepOutputs = Map.of();

        Map<String, String> resolved = resolver.resolve(input, triggerPayload, stepOutputs);

        assertThat(resolved.get("combined")).isEqualTo("Diff: abc PR: 42");
    }

    @Test
    void shouldResolveMultipleInputEntries() {
        Map<String, String> input = new HashMap<>();
        input.put("diff", "{{trigger.diff}}");
        input.put("logs", "{{trigger.logs}}");

        Map<String, Object> triggerPayload = Map.of("diff", "my diff", "logs", "my logs");
        Map<String, Map<String, Object>> stepOutputs = Map.of();

        Map<String, String> resolved = resolver.resolve(input, triggerPayload, stepOutputs);

        assertThat(resolved.get("diff")).isEqualTo("my diff");
        assertThat(resolved.get("logs")).isEqualTo("my logs");
    }

    @Test
    void shouldLeaveUnknownExpressionsUnchanged() {
        Map<String, String> input = Map.of("data", "{{unknown.expression}}");
        Map<String, Object> triggerPayload = Map.of();
        Map<String, Map<String, Object>> stepOutputs = Map.of();

        Map<String, String> resolved = resolver.resolve(input, triggerPayload, stepOutputs);

        assertThat(resolved.get("data")).isEqualTo("{{unknown.expression}}");
    }
}
