package com.agentic.engine;

import com.agentic.config.AgentConfig;
import com.agentic.config.AgentsConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowLoaderTest {

    @Mock
    private AgentsConfig agentsConfig;

    private WorkflowLoader workflowLoader;

    @BeforeEach
    void setUp() {
        workflowLoader = new WorkflowLoader(agentsConfig);
    }

    @Test
    void shouldParseValidWorkflowYaml() {
        String yaml = """
                name: pr-review
                trigger: pull_request
                description: Reviews PR code
                
                steps:
                  - name: review-code
                    agent: code-review
                    input:
                      diff: "{{trigger.diff}}"
                    onError: halt
                
                  - name: suggest-fixes
                    agent: change-suggestion
                    input:
                      findings: "{{steps.review-code.output.findings}}"
                    onError: halt
                    requiresReview: true
                """;

        WorkflowDefinition def = workflowLoader.parseWorkflow(toInputStream(yaml));

        assertThat(def.name()).isEqualTo("pr-review");
        assertThat(def.trigger()).isEqualTo("pull_request");
        assertThat(def.description()).isEqualTo("Reviews PR code");
        assertThat(def.steps()).hasSize(2);

        WorkflowStep step1 = def.steps().get(0);
        assertThat(step1.name()).isEqualTo("review-code");
        assertThat(step1.agent()).isEqualTo("code-review");
        assertThat(step1.input()).containsEntry("diff", "{{trigger.diff}}");
        assertThat(step1.onError()).isEqualTo("halt");
        assertThat(step1.requiresReview()).isFalse();

        WorkflowStep step2 = def.steps().get(1);
        assertThat(step2.name()).isEqualTo("suggest-fixes");
        assertThat(step2.agent()).isEqualTo("change-suggestion");
        assertThat(step2.input()).containsEntry("findings", "{{steps.review-code.output.findings}}");
        assertThat(step2.onError()).isEqualTo("halt");
        assertThat(step2.requiresReview()).isTrue();
    }

    @Test
    void shouldDefaultOnErrorToHalt() {
        String yaml = """
                name: test-workflow
                trigger: manual
                description: Test
                
                steps:
                  - name: step1
                    agent: code-review
                    input:
                      data: "{{trigger.data}}"
                """;

        WorkflowDefinition def = workflowLoader.parseWorkflow(toInputStream(yaml));

        assertThat(def.steps().get(0).onError()).isEqualTo("halt");
    }

    @Test
    void shouldDefaultRequiresReviewToFalse() {
        String yaml = """
                name: test-workflow
                trigger: manual
                description: Test
                
                steps:
                  - name: step1
                    agent: code-review
                    input:
                      data: "{{trigger.data}}"
                """;

        WorkflowDefinition def = workflowLoader.parseWorkflow(toInputStream(yaml));

        assertThat(def.steps().get(0).requiresReview()).isFalse();
    }

    @Test
    void shouldValidateSuccessfullyWithValidAgents() {
        when(agentsConfig.getAgent("code-review")).thenReturn(Optional.of(mockAgentConfig("code-review")));
        when(agentsConfig.getAgent("change-suggestion")).thenReturn(Optional.of(mockAgentConfig("change-suggestion")));

        String yaml = """
                name: pr-review
                trigger: pull_request
                description: Reviews PR code
                
                steps:
                  - name: review-code
                    agent: code-review
                    input:
                      diff: "{{trigger.diff}}"
                    onError: halt
                  - name: suggest-fixes
                    agent: change-suggestion
                    input:
                      findings: "{{steps.review-code.output.findings}}"
                    onError: halt
                """;

        WorkflowDefinition def = workflowLoader.parseWorkflow(toInputStream(yaml));
        List<String> errors = workflowLoader.validate(def);

        assertThat(errors).isEmpty();
    }

    @Test
    void shouldReportErrorForUndefinedAgent() {
        when(agentsConfig.getAgent("code-review")).thenReturn(Optional.of(mockAgentConfig("code-review")));
        when(agentsConfig.getAgent("nonexistent-agent")).thenReturn(Optional.empty());

        String yaml = """
                name: test-workflow
                trigger: manual
                description: Test
                
                steps:
                  - name: step1
                    agent: code-review
                    input:
                      data: "{{trigger.data}}"
                  - name: step2
                    agent: nonexistent-agent
                    input:
                      data: "{{trigger.data}}"
                """;

        WorkflowDefinition def = workflowLoader.parseWorkflow(toInputStream(yaml));
        List<String> errors = workflowLoader.validate(def);

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("nonexistent-agent");
        assertThat(errors.get(0)).contains("undefined agent");
    }

    @Test
    void shouldReportErrorForDuplicateStepNames() {
        when(agentsConfig.getAgent("code-review")).thenReturn(Optional.of(mockAgentConfig("code-review")));

        String yaml = """
                name: test-workflow
                trigger: manual
                description: Test
                
                steps:
                  - name: duplicate-step
                    agent: code-review
                    input:
                      data: "{{trigger.data}}"
                  - name: duplicate-step
                    agent: code-review
                    input:
                      data: "{{trigger.data}}"
                """;

        WorkflowDefinition def = workflowLoader.parseWorkflow(toInputStream(yaml));
        List<String> errors = workflowLoader.validate(def);

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("Duplicate step name");
        assertThat(errors.get(0)).contains("duplicate-step");
    }

    @Test
    void shouldReportErrorForMissingWorkflowName() {
        String yaml = """
                trigger: manual
                description: Test
                
                steps:
                  - name: step1
                    agent: code-review
                    input:
                      data: "test"
                """;

        WorkflowDefinition def = workflowLoader.parseWorkflow(toInputStream(yaml));
        List<String> errors = workflowLoader.validate(def);

        assertThat(errors).anyMatch(e -> e.contains("name is required"));
    }

    @Test
    void shouldReportErrorForMissingTrigger() {
        String yaml = """
                name: test-workflow
                description: Test
                
                steps:
                  - name: step1
                    agent: code-review
                    input:
                      data: "test"
                """;

        WorkflowDefinition def = workflowLoader.parseWorkflow(toInputStream(yaml));
        List<String> errors = workflowLoader.validate(def);

        assertThat(errors).anyMatch(e -> e.contains("trigger is required"));
    }

    @Test
    void shouldReportErrorForEmptySteps() {
        String yaml = """
                name: test-workflow
                trigger: manual
                description: Test
                
                steps: []
                """;

        WorkflowDefinition def = workflowLoader.parseWorkflow(toInputStream(yaml));
        List<String> errors = workflowLoader.validate(def);

        assertThat(errors).anyMatch(e -> e.contains("at least one step"));
    }

    @Test
    void shouldThrowWhenLoadingNonexistentWorkflow() {
        assertThatThrownBy(() -> workflowLoader.load("nonexistent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Workflow not found");
    }

    @Test
    void shouldHandleStepWithEmptyInput() {
        String yaml = """
                name: test-workflow
                trigger: manual
                description: Test
                
                steps:
                  - name: step1
                    agent: code-review
                """;

        WorkflowDefinition def = workflowLoader.parseWorkflow(toInputStream(yaml));

        assertThat(def.steps().get(0).input()).isEmpty();
    }

    private InputStream toInputStream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private AgentConfig mockAgentConfig(String name) {
        return new AgentConfig(name, "desc", List.of("cap"), "gemini-1.5-flash", 0.3, 4096, "prompt");
    }
}
