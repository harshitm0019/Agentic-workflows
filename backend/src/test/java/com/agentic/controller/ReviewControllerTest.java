package com.agentic.controller;

import com.agentic.engine.WorkflowEngine;
import com.agentic.model.ReviewItem;
import com.agentic.repository.ReviewItemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ReviewController.class)
@ActiveProfiles("test")
class ReviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ReviewItemRepository reviewItemRepository;

    @MockBean
    private WorkflowEngine workflowEngine;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void getPendingReviews_returnsPendingItems() throws Exception {
        UUID id1 = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();

        ReviewItem review = ReviewItem.builder()
                .id(id1)
                .workflowRunId(runId)
                .workflowStepId(stepId)
                .agentName("change-suggestion")
                .reviewType("approve_patch")
                .payload(Map.of("patch", "--- a/file.java\n+++ b/file.java"))
                .status("pending")
                .createdAt(Instant.parse("2024-01-15T10:00:00Z"))
                .build();

        when(reviewItemRepository.findByStatusOrderByCreatedAtAsc("pending"))
                .thenReturn(List.of(review));

        mockMvc.perform(get("/api/reviews/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].agentName").value("change-suggestion"))
                .andExpect(jsonPath("$[0].reviewType").value("approve_patch"))
                .andExpect(jsonPath("$[0].status").value("pending"));
    }

    @Test
    void getPendingReviews_noPending_returnsEmptyArray() throws Exception {
        when(reviewItemRepository.findByStatusOrderByCreatedAtAsc("pending"))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/reviews/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void approveReview_pendingItem_returns200AndResumesWorkflow() throws Exception {
        UUID reviewId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();

        ReviewItem review = ReviewItem.builder()
                .id(reviewId)
                .workflowRunId(runId)
                .workflowStepId(stepId)
                .agentName("change-suggestion")
                .reviewType("approve_patch")
                .status("pending")
                .createdAt(Instant.parse("2024-01-15T10:00:00Z"))
                .build();

        when(reviewItemRepository.findById(reviewId)).thenReturn(Optional.of(review));
        when(reviewItemRepository.save(any(ReviewItem.class))).thenReturn(review);

        mockMvc.perform(post("/api/reviews/{id}/approve", reviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.reviewId").value(reviewId.toString()))
                .andExpect(jsonPath("$.workflowRunId").value(runId.toString()));

        verify(workflowEngine).resumeAfterReview(runId, true, null);
    }

    @Test
    void approveReview_nonExistingId_returns404() throws Exception {
        UUID reviewId = UUID.randomUUID();
        when(reviewItemRepository.findById(reviewId)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/reviews/{id}/approve", reviewId))
                .andExpect(status().isNotFound());

        verifyNoInteractions(workflowEngine);
    }

    @Test
    void approveReview_alreadyApproved_returns400() throws Exception {
        UUID reviewId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        ReviewItem review = ReviewItem.builder()
                .id(reviewId)
                .workflowRunId(runId)
                .workflowStepId(UUID.randomUUID())
                .agentName("change-suggestion")
                .reviewType("approve_patch")
                .status("approved")
                .createdAt(Instant.parse("2024-01-15T10:00:00Z"))
                .decidedAt(Instant.parse("2024-01-15T10:05:00Z"))
                .build();

        when(reviewItemRepository.findById(reviewId)).thenReturn(Optional.of(review));

        mockMvc.perform(post("/api/reviews/{id}/approve", reviewId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        verifyNoInteractions(workflowEngine);
    }

    @Test
    void rejectReview_pendingItem_returns200WithReason() throws Exception {
        UUID reviewId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();

        ReviewItem review = ReviewItem.builder()
                .id(reviewId)
                .workflowRunId(runId)
                .workflowStepId(stepId)
                .agentName("change-suggestion")
                .reviewType("approve_patch")
                .status("pending")
                .createdAt(Instant.parse("2024-01-15T10:00:00Z"))
                .build();

        when(reviewItemRepository.findById(reviewId)).thenReturn(Optional.of(review));
        when(reviewItemRepository.save(any(ReviewItem.class))).thenReturn(review);

        String requestBody = objectMapper.writeValueAsString(Map.of("reason", "Patch introduces a bug"));

        mockMvc.perform(post("/api/reviews/{id}/reject", reviewId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("rejected"))
                .andExpect(jsonPath("$.reviewId").value(reviewId.toString()))
                .andExpect(jsonPath("$.workflowRunId").value(runId.toString()))
                .andExpect(jsonPath("$.reason").value("Patch introduces a bug"));

        verify(workflowEngine).resumeAfterReview(runId, false, "Patch introduces a bug");
    }

    @Test
    void rejectReview_nonExistingId_returns404() throws Exception {
        UUID reviewId = UUID.randomUUID();
        when(reviewItemRepository.findById(reviewId)).thenReturn(Optional.empty());

        String requestBody = objectMapper.writeValueAsString(Map.of("reason", "Not needed"));

        mockMvc.perform(post("/api/reviews/{id}/reject", reviewId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isNotFound());

        verifyNoInteractions(workflowEngine);
    }

    @Test
    void rejectReview_alreadyRejected_returns400() throws Exception {
        UUID reviewId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        ReviewItem review = ReviewItem.builder()
                .id(reviewId)
                .workflowRunId(runId)
                .workflowStepId(UUID.randomUUID())
                .agentName("change-suggestion")
                .reviewType("approve_patch")
                .status("rejected")
                .decisionReason("Already rejected")
                .createdAt(Instant.parse("2024-01-15T10:00:00Z"))
                .decidedAt(Instant.parse("2024-01-15T10:05:00Z"))
                .build();

        when(reviewItemRepository.findById(reviewId)).thenReturn(Optional.of(review));

        String requestBody = objectMapper.writeValueAsString(Map.of("reason", "Another reason"));

        mockMvc.perform(post("/api/reviews/{id}/reject", reviewId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        verifyNoInteractions(workflowEngine);
    }
}
