package com.agentic.model;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "workflow_steps")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowStepRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workflow_run_id", nullable = false)
    private UUID workflowRunId;

    @Column(name = "step_index", nullable = false)
    private int stepIndex;

    @Column(name = "agent_name", nullable = false)
    private String agentName;

    @Type(JsonType.class)
    @Column(name = "input", columnDefinition = "text")
    private Map<String, Object> input;

    @Type(JsonType.class)
    @Column(name = "output", columnDefinition = "text")
    private Map<String, Object> output;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error", columnDefinition = "text")
    private String error;
}
