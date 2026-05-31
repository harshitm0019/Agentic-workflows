package com.agentic.controller;

import com.agentic.model.GeminiUsage;
import com.agentic.repository.GeminiUsageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UsageController.class)
@ActiveProfiles("test")
class UsageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private GeminiUsageRepository geminiUsageRepository;

    @Test
    void getUsage_withExistingUsageRecord_returnsStats() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        GeminiUsage usage = GeminiUsage.builder()
                .id(UUID.randomUUID())
                .date(today)
                .requestCount(120)
                .totalTokens(45000)
                .updatedAt(Instant.now())
                .build();

        when(geminiUsageRepository.findByDate(today)).thenReturn(Optional.of(usage));

        mockMvc.perform(get("/api/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestsMade").value(120))
                .andExpect(jsonPath("$.requestLimit").value(1500))
                .andExpect(jsonPath("$.tokensUsed").value(45000))
                .andExpect(jsonPath("$.tokenLimit").value(1500000))
                .andExpect(jsonPath("$.date").value(today.toString()))
                .andExpect(jsonPath("$.warningThreshold").value(1200));
    }

    @Test
    void getUsage_noUsageRecordForToday_returnsZeros() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        when(geminiUsageRepository.findByDate(today)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestsMade").value(0))
                .andExpect(jsonPath("$.requestLimit").value(1500))
                .andExpect(jsonPath("$.tokensUsed").value(0))
                .andExpect(jsonPath("$.tokenLimit").value(1500000))
                .andExpect(jsonPath("$.date").value(today.toString()))
                .andExpect(jsonPath("$.warningThreshold").value(1200));
    }
}
