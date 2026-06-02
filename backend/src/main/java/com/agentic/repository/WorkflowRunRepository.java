package com.agentic.repository;

import com.agentic.model.WorkflowRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, UUID> {

    List<WorkflowRun> findByStatusOrderByStartedAtDesc(String status);

    List<WorkflowRun> findAllByOrderByStartedAtDesc();

    boolean existsByDeliveryId(String deliveryId);

    boolean existsByDeliveryIdStartingWith(String prefix);
}
