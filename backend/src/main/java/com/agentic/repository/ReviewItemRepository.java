package com.agentic.repository;

import com.agentic.model.ReviewItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReviewItemRepository extends JpaRepository<ReviewItem, UUID> {

    List<ReviewItem> findByStatusOrderByCreatedAtAsc(String status);

    List<ReviewItem> findByWorkflowRunId(UUID workflowRunId);
}
