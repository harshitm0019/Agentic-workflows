package com.agentic.model;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "review_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReviewItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workflow_run_id", nullable = false)
    private UUID workflowRunId;

    @Column(name = "workflow_step_id", nullable = false)
    private UUID workflowStepId;

    @Column(name = "agent_name", nullable = false)
    private String agentName;

    @Column(name = "review_type", nullable = false)
    private String reviewType;

    @Type(JsonType.class)
    @Column(name = "payload", columnDefinition = "text")
    private Map<String, Object> payload;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "decision_reason")
    private String decisionReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;
}
