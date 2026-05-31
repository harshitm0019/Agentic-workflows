package com.agentic.controller;

import com.agentic.model.WorkflowRun;
import com.agentic.model.WorkflowStepRecord;
import com.agentic.repository.WorkflowRunRepository;
import com.agentic.repository.WorkflowStepRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for workflow run queries.
 * Exposes endpoints to list all workflow runs and get details for a specific run.
 */
@RestController
@RequestMapping("/api/workflows")
@Slf4j
public class WorkflowController {

    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowStepRepository workflowStepRepository;

    public WorkflowController(WorkflowRunRepository workflowRunRepository,
                              WorkflowStepRepository workflowStepRepository) {
        this.workflowRunRepository = workflowRunRepository;
        this.workflowStepRepository = workflowStepRepository;
    }

    /**
     * List all workflow runs ordered by start time (most recent first).
     */
    @GetMapping
    public ResponseEntity<List<WorkflowRun>> listWorkflows() {
        List<WorkflowRun> runs = workflowRunRepository.findAllByOrderByStartedAtDesc();
        return ResponseEntity.ok(runs);
    }

    /**
     * Get a specific workflow run by ID, including its step records.
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getWorkflow(@PathVariable UUID id) {
        return workflowRunRepository.findById(id)
                .map(run -> {
                    List<WorkflowStepRecord> steps = workflowStepRepository.findByWorkflowRunIdOrderByStepIndex(run.getId());
                    Map<String, Object> response = Map.of(
                            "run", run,
                            "steps", steps
                    );
                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
