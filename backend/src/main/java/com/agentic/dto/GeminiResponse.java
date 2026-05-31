package com.agentic.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeminiResponse {

    private String content;
    private int promptTokens;
    private int completionTokens;
    private int totalTokens;
}
