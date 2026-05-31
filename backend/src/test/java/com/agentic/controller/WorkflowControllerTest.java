package com.agentic.controller;

import com.agentic.model.WorkflowRun;
import com.agentic.model.WorkflowStepRecord;
import com.agentic.repository.WorkflowRunRepository;
import com.agentic.repository.WorkflowStepRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WorkflowController.class)
@ActiveProfiles("test")
class WorkflowControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WorkflowRunRepository workflowRunRepository;

    @MockBean
    private WorkflowStepRepository workflowStepRepository;

    @Test
    void listWorkflows_returnsAllRunsOrderedByStartTime() throws Exception {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        WorkflowRun run1 = WorkflowRun.builder()
                .id(id1)
                .workflowName("pr-review")
                .triggerEvent("pull_request")
                .status("completed")
                .currentStep(1)
                .startedAt(Instant.parse("2024-01-15T10:00:00Z"))
                .completedAt(Instant.parse("2024-01-15T10:05:00Z"))
                .build();

        WorkflowRun run2 = WorkflowRun.builder()
                .id(id2)
                .workflowName("failure-analysis")
                .triggerEvent("workflow_run_failure")
                .status("running")
                .currentStep(0)
                .startedAt(Instant.parse("2024-01-15T11:00:00Z"))
                .build();

        when(workflowRunRepository.findAllByOrderByStartedAtDesc()).thenReturn(List.of(run2, run1));

        mockMvc.perform(get("/api/workflows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].workflowName").value("failure-analysis"))
                .andExpect(jsonPath("$[0].status").value("running"))
                .andExpect(jsonPath("$[1].workflowName").value("pr-review"))
                .andExpect(jsonPath("$[1].status").value("completed"));
    }

    @Test
    void listWorkflows_emptyList_returns200WithEmptyArray() throws Exception {
        when(workflowRunRepository.findAllByOrderByStartedAtDesc()).thenReturn(List.of());

        mockMvc.perform(get("/api/workflows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getWorkflow_existingId_returnsRunWithSteps() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();

        WorkflowRun run = WorkflowRun.builder()
                .id(runId)
                .workflowName("pr-review")
                .triggerEvent("pull_request")
                .status("completed")
                .currentStep(1)
                .startedAt(Instant.parse("2024-01-15T10:00:00Z"))
                .completedAt(Instant.parse("2024-01-15T10:05:00Z"))
                .build();

        WorkflowStepRecord step = WorkflowStepRecord.builder()
                .id(stepId)
                .workflowRunId(runId)
                .stepIndex(0)
                .agentName("code-review")
                .input(Map.of("diff", "sample diff"))
                .output(Map.of("findings", List.of()))
                .status("completed")
                .startedAt(Instant.parse("2024-01-15T10:00:00Z"))
                .completedAt(Instant.parse("2024-01-15T10:02:00Z"))
                .build();

        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(workflowStepRepository.findByWorkflowRunIdOrderByStepIndex(runId)).thenReturn(List.of(step));

        mockMvc.perform(get("/api/workflows/{id}", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.workflowName").value("pr-review"))
                .andExpect(jsonPath("$.run.status").value("completed"))
                .andExpect(jsonPath("$.steps").isArray())
                .andExpect(jsonPath("$.steps.length()").value(1))
                .andExpect(jsonPath("$.steps[0].agentName").value("code-review"))
                .andExpect(jsonPath("$.steps[0].status").value("completed"));
    }

    @Test
    void getWorkflow_nonExistingId_returns404() throws Exception {
        UUID runId = UUID.randomUUID();
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/workflows/{id}", runId))
                .andExpect(status().isNotFound());
    }
}
