package com.agentic.engine;

import com.agentic.agent.BaseAgent;
import com.agentic.dto.AgentInput;
import com.agentic.dto.AgentOutput;
import com.agentic.model.ReviewItem;
import com.agentic.model.WorkflowRun;
import com.agentic.model.WorkflowStepRecord;
import com.agentic.repository.ReviewItemRepository;
import com.agentic.repository.WorkflowRunRepository;
import com.agentic.repository.WorkflowStepRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sequential workflow engine that executes workflow steps one at a time.
 * Supports pause-for-review, error handling (halt/skip), and 5-minute timeouts.
 */
@Service
@Slf4j
public class WorkflowEngine {

    private static final long DEFAULT_STEP_TIMEOUT_SECONDS = 300; // 5 minutes

    private final WorkflowRunRepository runRepo;
    private final WorkflowStepRepository stepRepo;
    private final ReviewItemRepository reviewRepo;
    private final Map<String, BaseAgent> agents;
    private final TemplateResolver templateResolver;
    private long stepTimeoutSeconds = DEFAULT_STEP_TIMEOUT_SECONDS;

    public WorkflowEngine(WorkflowRunRepository runRepo,
                          WorkflowStepRepository stepRepo,
                          ReviewItemRepository reviewRepo,
                          Map<String, BaseAgent> agents,
                          TemplateResolver templateResolver) {
        this.runRepo = runRepo;
        this.stepRepo = stepRepo;
        this.reviewRepo = reviewRepo;
        this.agents = agents;
        this.templateResolver = templateResolver;
    }

    /**
     * Sets the step timeout in seconds. Useful for testing.
     */
    public void setStepTimeoutSeconds(long seconds) {
        this.stepTimeoutSeconds = seconds;
    }

    /**
     * Executes a workflow definition asynchronously.
     * Creates a WorkflowRun record, iterates steps sequentially, and handles
     * errors, reviews, and timeouts.
     */
    @Async
    public void executeWorkflow(WorkflowDefinition def, Map<String, Object> triggerPayload) {
        WorkflowRun run = createRun(def.name(), def.trigger(), triggerPayload);
        executeFromStep(run, def, triggerPayload, 0);
    }

    /**
     * Resumes a workflow after a human review decision.
     * If approved, continues from the next step. If rejected, marks the run as failed.
     */
    @Transactional
    public void resumeAfterReview(UUID workflowRunId, boolean approved, String reason) {
        WorkflowRun run = runRepo.findById(workflowRunId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow run not found: " + workflowRunId));

        if (!"paused_for_review".equals(run.getStatus())) {
            throw new IllegalStateException("Workflow run is not paused for review: " + run.getStatus());
        }

        // Update the review item
        List<ReviewItem> reviews = reviewRepo.findByWorkflowRunId(workflowRunId);
        ReviewItem pendingReview = reviews.stream()
                .filter(r -> "pending".equals(r.getStatus()))
                .findFirst()
                .orElse(null);

        if (pendingReview != null) {
            pendingReview.setStatus(approved ? "approved" : "rejected");
            pendingReview.setDecisionReason(reason);
            pendingReview.setDecidedAt(Instant.now());
            reviewRepo.save(pendingReview);
        }

        if (!approved) {
            run.setStatus("failed");
            run.setError("Review rejected: " + (reason != null ? reason : "No reason provided"));
            run.setCompletedAt(Instant.now());
            runRepo.save(run);
            log.info("Workflow run {} failed due to review rejection", workflowRunId);
            return;
        }

        // Resume from next step
        run.setStatus("running");
        runRepo.save(run);

        log.info("Workflow run {} resuming after approval from step {}", workflowRunId, run.getCurrentStep() + 1);
    }

    /**
     * Resumes workflow execution asynchronously after review approval.
     * This must be called separately from resumeAfterReview to allow the transaction to commit first.
     */
    @Async
    public void continueAfterReview(UUID workflowRunId, WorkflowDefinition def, Map<String, Object> triggerPayload, int fromStep) {
        WorkflowRun run = runRepo.findById(workflowRunId).orElse(null);
        if (run == null || !"running".equals(run.getStatus())) {
            return;
        }
        executeFromStep(run, def, triggerPayload, fromStep);
    }

    /**
     * Executes the workflow from a given step index.
     */
    private void executeFromStep(WorkflowRun run, WorkflowDefinition def, Map<String, Object> triggerPayload, int fromStep) {
        Map<String, Map<String, Object>> stepOutputs = collectPreviousOutputs(run.getId(), def.steps(), fromStep);

        for (int i = fromStep; i < def.steps().size(); i++) {
            WorkflowStep step = def.steps().get(i);
            updateCurrentStep(run, i);

            BaseAgent agent = agents.get(step.agent());
            if (agent == null) {
                String error = "Agent not found: " + step.agent();
                log.error(error);
                handleStepFailure(run, i, step, error);
                if ("halt".equals(step.onError())) {
                    failRun(run, error);
                    return;
                }
                continue;
            }

            // Resolve input
            Map<String, String> resolvedInputRaw = templateResolver.resolve(step.input(), triggerPayload, stepOutputs);
            Map<String, Object> resolvedInput = new HashMap<>(resolvedInputRaw);

            // Run agent with timeout
            AgentOutput result;
            Instant stepStart = Instant.now();
            try {
                result = executeWithTimeout(agent, resolvedInput, i > 0 ? stepOutputs.getOrDefault(def.steps().get(i - 1).name(), Map.of()) : triggerPayload);
            } catch (TimeoutException e) {
                String error = "Step timed out after " + stepTimeoutSeconds + " seconds";
                log.warn("Step '{}' in workflow '{}' timed out", step.name(), def.name());
                recordStep(run.getId(), i, step.agent(), resolvedInput, null, "timed_out", stepStart, error);
                if ("halt".equals(step.onError())) {
                    failRun(run, error);
                    return;
                }
                continue;
            } catch (Exception e) {
                String error = "Step execution error: " + e.getMessage();
                log.error("Step '{}' in workflow '{}' failed: {}", step.name(), def.name(), e.getMessage());
                recordStep(run.getId(), i, step.agent(), resolvedInput, null, "failed", stepStart, error);
                if ("halt".equals(step.onError())) {
                    failRun(run, error);
                    return;
                }
                continue;
            }

            // Handle agent failure
            if (!result.success()) {
                String error = result.error() != null ? result.error() : "Agent returned failure";
                log.warn("Agent '{}' returned failure in step '{}': {}", step.agent(), step.name(), error);
                recordStep(run.getId(), i, step.agent(), resolvedInput, result.data(), "failed", stepStart, error);
                if ("halt".equals(step.onError())) {
                    failRun(run, error);
                    return;
                }
                continue;
            }

            // Step completed successfully
            recordStep(run.getId(), i, step.agent(), resolvedInput, result.data(), "completed", stepStart, null);
            stepOutputs.put(step.name(), result.data() != null ? result.data() : Map.of());

            // Check if review is required
            if (result.requiresReview() || step.requiresReview()) {
                pauseForReview(run, i, step.agent(), result);
                return;
            }
        }

        completeRun(run);
    }

    @Transactional
    protected WorkflowRun createRun(String workflowName, String triggerEvent, Map<String, Object> triggerPayload) {
        WorkflowRun.WorkflowRunBuilder builder = WorkflowRun.builder()
                .workflowName(workflowName)
                .triggerEvent(triggerEvent)
                .triggerPayload(triggerPayload)
                .status("running")
                .currentStep(0)
                .startedAt(Instant.now());

        // Extract delivery_id from trigger payload for deduplication if present
        if (triggerPayload != null && triggerPayload.containsKey("delivery_id")) {
            builder.deliveryId(String.valueOf(triggerPayload.get("delivery_id")));
        }

        return runRepo.save(builder.build());
    }

    @Transactional
    protected void updateCurrentStep(WorkflowRun run, int stepIndex) {
        run.setCurrentStep(stepIndex);
        runRepo.save(run);
    }

    @Transactional
    protected WorkflowStepRecord recordStep(UUID runId, int stepIndex, String agentName,
                                            Map<String, Object> input, Map<String, Object> output,
                                            String status, Instant startedAt, String error) {
        WorkflowStepRecord record = WorkflowStepRecord.builder()
                .workflowRunId(runId)
                .stepIndex(stepIndex)
                .agentName(agentName)
                .input(input)
                .output(output)
                .status(status)
                .startedAt(startedAt)
                .completedAt(Instant.now())
                .error(error)
                .build();
        return stepRepo.save(record);
    }

    @Transactional
    protected void pauseForReview(WorkflowRun run, int stepIndex, String agentName, AgentOutput result) {
        run.setStatus("paused_for_review");
        runRepo.save(run);

        // Get the step record
        WorkflowStepRecord stepRecord = stepRepo.findByWorkflowRunIdAndStepIndex(run.getId(), stepIndex);
        UUID stepId = stepRecord != null ? stepRecord.getId() : run.getId();

        ReviewItem review = ReviewItem.builder()
                .workflowRunId(run.getId())
                .workflowStepId(stepId)
                .agentName(agentName)
                .reviewType("approve_patch")
                .payload(result.data())
                .status("pending")
                .createdAt(Instant.now())
                .build();
        reviewRepo.save(review);

        log.info("Workflow run {} paused for review at step {}", run.getId(), stepIndex);
    }

    @Transactional
    protected void failRun(WorkflowRun run, String error) {
        run.setStatus("failed");
        run.setError(error);
        run.setCompletedAt(Instant.now());
        runRepo.save(run);
        log.info("Workflow run {} failed: {}", run.getId(), error);
    }

    @Transactional
    protected void completeRun(WorkflowRun run) {
        run.setStatus("completed");
        run.setCompletedAt(Instant.now());
        runRepo.save(run);
        log.info("Workflow run {} completed successfully", run.getId());
    }

    private void handleStepFailure(WorkflowRun run, int stepIndex, WorkflowStep step, String error) {
        recordStep(run.getId(), stepIndex, step.agent(), Map.of(), null,
                "halt".equals(step.onError()) ? "failed" : "skipped",
                Instant.now(), error);
    }

    private AgentOutput executeWithTimeout(BaseAgent agent, Map<String, Object> input, Map<String, Object> previousOutput)
            throws Exception {
        AgentInput agentInput = new AgentInput(agent.getName(), input, previousOutput);

        CompletableFuture<AgentOutput> future = CompletableFuture.supplyAsync(() -> agent.execute(agentInput));

        try {
            return future.get(stepTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new RuntimeException(cause);
        }
    }

    /**
     * Collects outputs from previously completed steps (for resume-from-step scenarios).
     */
    private Map<String, Map<String, Object>> collectPreviousOutputs(UUID runId, List<WorkflowStep> steps, int upToStep) {
        Map<String, Map<String, Object>> outputs = new HashMap<>();
        List<WorkflowStepRecord> records = stepRepo.findByWorkflowRunIdOrderByStepIndex(runId);

        for (WorkflowStepRecord record : records) {
            if (record.getStepIndex() < upToStep && record.getStepIndex() < steps.size()) {
                String stepName = steps.get(record.getStepIndex()).name();
                outputs.put(stepName, record.getOutput() != null ? record.getOutput() : Map.of());
            }
        }
        return outputs;
    }
}
