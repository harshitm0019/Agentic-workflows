package com.agentic.repository;

import com.agentic.model.WorkflowStepRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WorkflowStepRepository extends JpaRepository<WorkflowStepRecord, UUID> {

    List<WorkflowStepRecord> findByWorkflowRunIdOrderByStepIndex(UUID workflowRunId);

    WorkflowStepRecord findByWorkflowRunIdAndStepIndex(UUID workflowRunId, int stepIndex);
}
