package com.agentic.controller;

import com.agentic.engine.WorkflowEngine;
import com.agentic.engine.WorkflowLoader;
import com.agentic.engine.WorkflowDefinition;
import com.agentic.model.ReviewItem;
import com.agentic.model.WorkflowRun;
import com.agentic.repository.ReviewItemRepository;
import com.agentic.repository.WorkflowRunRepository;
import com.agentic.service.GitHubService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for review item management.
 * Exposes endpoints for listing pending reviews and approving/rejecting them.
 */
@RestController
@RequestMapping("/api/reviews")
@Slf4j
public class ReviewController {

    private final ReviewItemRepository reviewItemRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowEngine workflowEngine;
    private final GitHubService gitHubService;

    public ReviewController(ReviewItemRepository reviewItemRepository,
                            WorkflowRunRepository workflowRunRepository,
                            WorkflowEngine workflowEngine,
                            GitHubService gitHubService) {
        this.reviewItemRepository = reviewItemRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.workflowEngine = workflowEngine;
        this.gitHubService = gitHubService;
    }

    /**
     * List all pending review items ordered by creation time (oldest first).
     */
    @GetMapping("/pending")
    public ResponseEntity<List<ReviewItem>> getPendingReviews() {
        List<ReviewItem> pending = reviewItemRepository.findByStatusOrderByCreatedAtAsc("pending");
        return ResponseEntity.ok(pending);
    }

    /**
     * Approve a review item: push the patch to GitHub and complete the workflow.
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approveReview(@PathVariable UUID id) {
        return reviewItemRepository.findById(id)
                .map(review -> {
                    if (!"pending".equals(review.getStatus())) {
                        return ResponseEntity.badRequest()
                                .body(Map.of("error", "Review item is not pending, current status: " + review.getStatus()));
                    }

                    review.setStatus("approved");
                    review.setDecidedAt(Instant.now());
                    reviewItemRepository.save(review);

                    // Get the workflow run to access trigger payload (repo, branch info)
                    WorkflowRun run = workflowRunRepository.findById(review.getWorkflowRunId()).orElse(null);
                    if (run == null) {
                        return ResponseEntity.internalServerError()
                                .body(Map.of("error", "Workflow run not found"));
                    }

                    // Try to push the patch to GitHub
                    String commitSha = null;
                    try {
                        Map<String, Object> payload = review.getPayload();
                        String patch = payload != null ? (String) payload.get("patch") : null;

                        if (patch != null && run.getTriggerPayload() != null) {
                            String repo = (String) run.getTriggerPayload().get("repo");
                            Object prNum = run.getTriggerPayload().get("pr_number");
                            String branch = "main"; // Default, would ideally get from PR head ref

                            if (repo != null) {
                                commitSha = gitHubService.pushPatchAsPRComment(repo,
                                        prNum != null ? ((Number) prNum).intValue() : 0, patch);
                                log.info("Patch pushed for workflow run {}. Commit/Comment: {}", run.getId(), commitSha);
                            }
                        }
                    } catch (Exception e) {
                        log.error("Failed to push patch for workflow run {}: {}", run.getId(), e.getMessage());
                        // Don't fail the workflow — patch push is best-effort
                        commitSha = "push_failed: " + e.getMessage();
                    }

                    // Complete the workflow
                    run.setStatus("completed");
                    run.setCompletedAt(Instant.now());
                    workflowRunRepository.save(run);

                    log.info("Review {} approved, workflow run {} completed", id, review.getWorkflowRunId());
                    return ResponseEntity.ok(Map.of(
                            "status", "approved",
                            "reviewId", id.toString(),
                            "workflowRunId", review.getWorkflowRunId().toString(),
                            "commitSha", commitSha != null ? commitSha : "no_push"
                    ));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Reject a review item with a reason and fail the associated workflow.
     */
    @PostMapping("/{id}/reject")
    public ResponseEntity<?> rejectReview(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        String reason = body != null ? body.get("reason") : null;

        return reviewItemRepository.findById(id)
                .map(review -> {
                    if (!"pending".equals(review.getStatus())) {
                        return ResponseEntity.badRequest()
                                .body(Map.of("error", "Review item is not pending, current status: " + review.getStatus()));
                    }

                    review.setStatus("rejected");
                    review.setDecisionReason(reason);
                    review.setDecidedAt(Instant.now());
                    reviewItemRepository.save(review);

                    // Notify workflow engine of rejection
                    workflowEngine.resumeAfterReview(review.getWorkflowRunId(), false, reason);

                    log.info("Review {} rejected for workflow run {}. Reason: {}", id, review.getWorkflowRunId(), reason);
                    return ResponseEntity.ok(Map.of(
                            "status", "rejected",
                            "reviewId", id.toString(),
                            "workflowRunId", review.getWorkflowRunId().toString(),
                            "reason", reason != null ? reason : ""
                    ));
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
