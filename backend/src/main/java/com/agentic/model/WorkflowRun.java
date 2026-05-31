package com.agentic.model;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "workflow_runs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workflow_name", nullable = false)
    private String workflowName;

    @Column(name = "trigger_event", nullable = false)
    private String triggerEvent;

    @Type(JsonType.class)
    @Column(name = "trigger_payload", columnDefinition = "text")
    private Map<String, Object> triggerPayload;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "current_step", nullable = false)
    private int currentStep;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error", columnDefinition = "text")
    private String error;

    @Column(name = "delivery_id", unique = true)
    private String deliveryId;
}
