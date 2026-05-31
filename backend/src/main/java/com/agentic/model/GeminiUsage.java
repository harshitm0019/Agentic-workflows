package com.agentic.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "gemini_usage")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GeminiUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "date", nullable = false, unique = true)
    private LocalDate date;

    @Column(name = "request_count", nullable = false)
    private int requestCount;

    @Column(name = "total_tokens", nullable = false)
    private int totalTokens;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
