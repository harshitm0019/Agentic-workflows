package com.agentic.engine;

import com.agentic.agent.BaseAgent;
import com.agentic.config.AgentConfig;
import com.agentic.dto.AgentInput;
import com.agentic.dto.AgentOutput;
import com.agentic.model.ReviewItem;
import com.agentic.model.WorkflowRun;
import com.agentic.model.WorkflowStepRecord;
import com.agentic.repository.ReviewItemRepository;
import com.agentic.repository.WorkflowRunRepository;
import com.agentic.repository.WorkflowStepRepository;
import com.agentic.service.LangChainGeminiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class WorkflowEngineIntegrationTest {

    @Autowired
    private WorkflowEngine workflowEngine;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private WorkflowStepRepository stepRepo;

    @Autowired
    private ReviewItemRepository reviewRepo;

    @Autowired
    private Map<String, BaseAgent> agents;

    // Latches to synchronize async execution in tests
    private static CountDownLatch executionLatch;
    private static AtomicReference<UUID> lastRunId = new AtomicReference<>();

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        public Map<String, BaseAgent> agents() {
            return Map.of(
                    "test-agent", new SuccessAgent(),
                    "review-agent", new ReviewAgent(),
                    "failing-agent", new FailingAgent(),
                    "slow-agent", new SlowAgent()
            );
        }
    }

    @BeforeEach
    void setUp() {
        reviewRepo.deleteAll();
        stepRepo.deleteAll();
        runRepo.deleteAll();
        executionLatch = new CountDownLatch(1);
    }

    @Test
    void shouldExecuteWorkflowSuccessfully() throws Exception {
        WorkflowDefinition def = new WorkflowDefinition(
                "test-workflow", "manual", "Test workflow",
                List.of(
                        new WorkflowStep("step-1", "test-agent", Map.of("data", "{{trigger.input}}"), "halt", false),
                        new WorkflowStep("step-2", "test-agent", Map.of("prev", "{{steps.step-1.output.result}}"), "halt", false)
                )
        );

        workflowEngine.executeWorkflow(def, Map.of("input", "hello"));

        // Wait for async execution
        awaitCompletion();

        List<WorkflowRun> runs = runRepo.findAllByOrderByStartedAtDesc();
        assertThat(runs).hasSize(1);

        WorkflowRun run = runs.get(0);
        assertThat(run.getStatus()).isEqualTo("completed");
        assertThat(run.getWorkflowName()).isEqualTo("test-workflow");
        assertThat(run.getCompletedAt()).isNotNull();

        List<WorkflowStepRecord> steps = stepRepo.findByWorkflowRunIdOrderByStepIndex(run.getId());
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).getStatus()).isEqualTo("completed");
        assertThat(steps.get(0).getAgentName()).isEqualTo("test-agent");
        assertThat(steps.get(1).getStatus()).isEqualTo("completed");
    }

    @Test
    void shouldFailWorkflowOnHaltError() throws Exception {
        WorkflowDefinition def = new WorkflowDefinition(
                "fail-workflow", "manual", "Failing workflow",
                List.of(
                        new WorkflowStep("step-1", "failing-agent", Map.of("data", "test"), "halt", false),
                        new WorkflowStep("step-2", "test-agent", Map.of("data", "test"), "halt", false)
                )
        );

        workflowEngine.executeWorkflow(def, Map.of("data", "test"));

        awaitCompletion();

        List<WorkflowRun> runs = runRepo.findAllByOrderByStartedAtDesc();
        assertThat(runs).hasSize(1);

        WorkflowRun run = runs.get(0);
        assertThat(run.getStatus()).isEqualTo("failed");
        assertThat(run.getError()).isNotNull();
        assertThat(run.getCompletedAt()).isNotNull();

        List<WorkflowStepRecord> steps = stepRepo.findByWorkflowRunIdOrderByStepIndex(run.getId());
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).getStatus()).isEqualTo("failed");
    }

    @Test
    void shouldContinueWorkflowOnSkipError() throws Exception {
        WorkflowDefinition def = new WorkflowDefinition(
                "skip-workflow", "manual", "Skip error workflow",
                List.of(
                        new WorkflowStep("step-1", "failing-agent", Map.of("data", "test"), "skip", false),
                        new WorkflowStep("step-2", "test-agent", Map.of("data", "{{trigger.data}}"), "halt", false)
                )
        );

        workflowEngine.executeWorkflow(def, Map.of("data", "test"));

        awaitCompletion();

        List<WorkflowRun> runs = runRepo.findAllByOrderByStartedAtDesc();
        assertThat(runs).hasSize(1);

        WorkflowRun run = runs.get(0);
        assertThat(run.getStatus()).isEqualTo("completed");

        List<WorkflowStepRecord> steps = stepRepo.findByWorkflowRunIdOrderByStepIndex(run.getId());
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).getStatus()).isEqualTo("failed");
        assertThat(steps.get(1).getStatus()).isEqualTo("completed");
    }

    @Test
    void shouldPauseForReviewAndCreateReviewItem() throws Exception {
        WorkflowDefinition def = new WorkflowDefinition(
                "review-workflow", "manual", "Review workflow",
                List.of(
                        new WorkflowStep("step-1", "test-agent", Map.of("data", "test"), "halt", false),
                        new WorkflowStep("step-2", "review-agent", Map.of("data", "test"), "halt", true)
                )
        );

        workflowEngine.executeWorkflow(def, Map.of("data", "test"));

        awaitCompletion();

        List<WorkflowRun> runs = runRepo.findAllByOrderByStartedAtDesc();
        assertThat(runs).hasSize(1);

        WorkflowRun run = runs.get(0);
        assertThat(run.getStatus()).isEqualTo("paused_for_review");

        List<ReviewItem> reviews = reviewRepo.findByWorkflowRunId(run.getId());
        assertThat(reviews).hasSize(1);
        assertThat(reviews.get(0).getStatus()).isEqualTo("pending");
        assertThat(reviews.get(0).getAgentName()).isEqualTo("review-agent");
    }

    @Test
    void shouldResumeAfterApproval() throws Exception {
        // First, create a paused workflow
        WorkflowDefinition def = new WorkflowDefinition(
                "review-workflow", "manual", "Review workflow",
                List.of(
                        new WorkflowStep("step-1", "review-agent", Map.of("data", "test"), "halt", true)
                )
        );

        workflowEngine.executeWorkflow(def, Map.of("data", "test"));
        awaitCompletion();

        List<WorkflowRun> runs = runRepo.findAllByOrderByStartedAtDesc();
        WorkflowRun run = runs.get(0);
        assertThat(run.getStatus()).isEqualTo("paused_for_review");

        // Now resume with approval
        workflowEngine.resumeAfterReview(run.getId(), true, "Looks good");

        // Verify state
        run = runRepo.findById(run.getId()).orElseThrow();
        assertThat(run.getStatus()).isEqualTo("running");

        // Verify review item updated
        List<ReviewItem> reviews = reviewRepo.findByWorkflowRunId(run.getId());
        assertThat(reviews).hasSize(1);
        assertThat(reviews.get(0).getStatus()).isEqualTo("approved");
        assertThat(reviews.get(0).getDecidedAt()).isNotNull();
    }

    @Test
    void shouldFailAfterRejection() throws Exception {
        // First, create a paused workflow
        WorkflowDefinition def = new WorkflowDefinition(
                "review-workflow", "manual", "Review workflow",
                List.of(
                        new WorkflowStep("step-1", "review-agent", Map.of("data", "test"), "halt", true)
                )
        );

        workflowEngine.executeWorkflow(def, Map.of("data", "test"));
        awaitCompletion();

        List<WorkflowRun> runs = runRepo.findAllByOrderByStartedAtDesc();
        WorkflowRun run = runs.get(0);

        // Reject the review
        workflowEngine.resumeAfterReview(run.getId(), false, "Code needs rework");

        // Verify state
        run = runRepo.findById(run.getId()).orElseThrow();
        assertThat(run.getStatus()).isEqualTo("failed");
        assertThat(run.getError()).contains("Review rejected");
        assertThat(run.getError()).contains("Code needs rework");
        assertThat(run.getCompletedAt()).isNotNull();

        // Verify review item updated
        List<ReviewItem> reviews = reviewRepo.findByWorkflowRunId(run.getId());
        assertThat(reviews).hasSize(1);
        assertThat(reviews.get(0).getStatus()).isEqualTo("rejected");
        assertThat(reviews.get(0).getDecisionReason()).isEqualTo("Code needs rework");
    }

    @Test
    void shouldHandleStepTimeout() throws Exception {
        // Set a short timeout for testing (2 seconds instead of 5 minutes)
        workflowEngine.setStepTimeoutSeconds(2);

        WorkflowDefinition def = new WorkflowDefinition(
                "timeout-workflow", "manual", "Timeout workflow",
                List.of(
                        new WorkflowStep("step-1", "slow-agent", Map.of("data", "test"), "halt", false)
                )
        );

        workflowEngine.executeWorkflow(def, Map.of("data", "test"));

        // Wait for the timeout to trigger plus processing time
        awaitCompletionWithTimeout(10);

        List<WorkflowRun> runs = runRepo.findAllByOrderByStartedAtDesc();
        assertThat(runs).hasSize(1);

        WorkflowRun run = runs.get(0);
        assertThat(run.getStatus()).isEqualTo("failed");

        List<WorkflowStepRecord> steps = stepRepo.findByWorkflowRunIdOrderByStepIndex(run.getId());
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).getStatus()).isEqualTo("timed_out");
        assertThat(steps.get(0).getError()).contains("timed out");

        // Reset to default
        workflowEngine.setStepTimeoutSeconds(300);
    }

    @Test
    void shouldHandleMissingAgent() throws Exception {
        WorkflowDefinition def = new WorkflowDefinition(
                "missing-agent-workflow", "manual", "Missing agent workflow",
                List.of(
                        new WorkflowStep("step-1", "nonexistent-agent", Map.of("data", "test"), "halt", false)
                )
        );

        workflowEngine.executeWorkflow(def, Map.of("data", "test"));

        awaitCompletion();

        List<WorkflowRun> runs = runRepo.findAllByOrderByStartedAtDesc();
        assertThat(runs).hasSize(1);

        WorkflowRun run = runs.get(0);
        assertThat(run.getStatus()).isEqualTo("failed");
        assertThat(run.getError()).contains("Agent not found");
    }

    @Test
    void shouldResumeThrowsForNonPausedRun() throws Exception {
        // Create a completed workflow
        WorkflowDefinition def = new WorkflowDefinition(
                "test-workflow", "manual", "Test",
                List.of(
                        new WorkflowStep("step-1", "test-agent", Map.of("data", "test"), "halt", false)
                )
        );

        workflowEngine.executeWorkflow(def, Map.of("data", "test"));
        awaitCompletion();

        List<WorkflowRun> runs = runRepo.findAllByOrderByStartedAtDesc();
        WorkflowRun run = runs.get(0);
        assertThat(run.getStatus()).isEqualTo("completed");

        assertThatThrownBy(() -> workflowEngine.resumeAfterReview(run.getId(), true, "reason"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not paused for review");
    }

    @Test
    void shouldResumeThrowsForNonExistentRun() {
        UUID fakeId = UUID.randomUUID();
        assertThatThrownBy(() -> workflowEngine.resumeAfterReview(fakeId, true, "reason"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    // --- Helper methods ---

    private void awaitCompletion() throws InterruptedException {
        awaitCompletionWithTimeout(5);
    }

    private void awaitCompletionWithTimeout(int seconds) throws InterruptedException {
        // Poll for completion since @Async runs in a different thread
        long deadline = System.currentTimeMillis() + (seconds * 1000L);
        while (System.currentTimeMillis() < deadline) {
            List<WorkflowRun> runs = runRepo.findAllByOrderByStartedAtDesc();
            if (!runs.isEmpty()) {
                String status = runs.get(0).getStatus();
                if ("completed".equals(status) || "failed".equals(status) || "paused_for_review".equals(status)) {
                    return;
                }
            }
            Thread.sleep(100);
        }
    }

    // --- Test agents ---

    static class SuccessAgent extends BaseAgent {
        public SuccessAgent() {
            super("test-agent", new AgentConfig("test-agent", "Test", List.of("test"), "gemini-1.5-flash", 0.3, 4096, "test"), null);
        }

        @Override
        public AgentOutput execute(AgentInput input) {
            return new AgentOutput(true, Map.of("result", "success", "processed", input.data().toString()), false, null);
        }
    }

    static class ReviewAgent extends BaseAgent {
        public ReviewAgent() {
            super("review-agent", new AgentConfig("review-agent", "Review", List.of("review"), "gemini-1.5-flash", 0.3, 4096, "test"), null);
        }

        @Override
        public AgentOutput execute(AgentInput input) {
            return new AgentOutput(true, Map.of("patch", "diff content", "format", "unified-diff"), true, null);
        }
    }

    static class FailingAgent extends BaseAgent {
        public FailingAgent() {
            super("failing-agent", new AgentConfig("failing-agent", "Fail", List.of("fail"), "gemini-1.5-flash", 0.3, 4096, "test"), null);
        }

        @Override
        public AgentOutput execute(AgentInput input) {
            return new AgentOutput(false, Map.of(), false, "Intentional test failure");
        }
    }

    static class SlowAgent extends BaseAgent {
        public SlowAgent() {
            super("slow-agent", new AgentConfig("slow-agent", "Slow", List.of("slow"), "gemini-1.5-flash", 0.3, 4096, "test"), null);
        }

        @Override
        public AgentOutput execute(AgentInput input) {
            try {
                // Sleep longer than the timeout - but use a short timeout for tests
                Thread.sleep(600_000); // 10 minutes
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new AgentOutput(true, Map.of(), false, null);
        }
    }
}
