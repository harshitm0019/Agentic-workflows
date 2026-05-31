package com.agentic.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeminiRequest {

    private String model;
    private String systemPrompt;
    private String userPrompt;
    @Builder.Default
    private double temperature = 0.7;
    @Builder.Default
    private int maxTokens = 2048;
}
