package com.agentic.repository;

import com.agentic.model.GeminiUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GeminiUsageRepository extends JpaRepository<GeminiUsage, UUID> {

    Optional<GeminiUsage> findByDate(LocalDate date);
}
