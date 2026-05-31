package com.agentic.controller;

import com.agentic.model.GeminiUsage;
import com.agentic.repository.GeminiUsageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * REST controller for Gemini usage statistics.
 * Returns current daily usage (requests and tokens) relative to configured limits.
 */
@RestController
@RequestMapping("/api/usage")
@Slf4j
public class UsageController {

    private final GeminiUsageRepository geminiUsageRepository;

    @Value("${gemini.daily-limit:1500}")
    private int dailyLimit;

    public UsageController(GeminiUsageRepository geminiUsageRepository) {
        this.geminiUsageRepository = geminiUsageRepository;
    }

    /**
     * Get current Gemini usage stats for today.
     * Returns requests made/limit and tokens used/limit.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getUsage() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        GeminiUsage usage = geminiUsageRepository.findByDate(today).orElse(null);

        int requestsMade = usage != null ? usage.getRequestCount() : 0;
        int tokensUsed = usage != null ? usage.getTotalTokens() : 0;

        // Token limit is an estimated value based on free tier (assume ~1M tokens/day for Gemini free tier)
        int tokenLimit = dailyLimit * 1000;

        Map<String, Object> response = Map.of(
                "requestsMade", requestsMade,
                "requestLimit", dailyLimit,
                "tokensUsed", tokensUsed,
                "tokenLimit", tokenLimit,
                "date", today.toString(),
                "warningThreshold", (int) (dailyLimit * 0.8)
        );

        return ResponseEntity.ok(response);
    }
}
