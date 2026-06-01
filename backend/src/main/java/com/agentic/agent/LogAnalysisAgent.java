package com.agentic.agent;

import com.agentic.config.AgentConfig;
import com.agentic.dto.AgentInput;
import com.agentic.dto.AgentOutput;
// NOTE: Switched to LangChain4j. Original: import com.agentic.service.GeminiService;
import com.agentic.service.LangChainGeminiService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

@Slf4j
public class LogAnalysisAgent extends BaseAgent {

    private static final int MAX_LOG_LENGTH = 50000;
    private static final int TRUNCATED_LENGTH = 20000;
    private static final int MIN_LOG_LENGTH = 10;

    private final ObjectMapper objectMapper;

    public LogAnalysisAgent(String name, AgentConfig config, LangChainGeminiService geminiService, ObjectMapper objectMapper) {
        super(name, config, geminiService);
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentOutput execute(AgentInput input) {
        String logs = extractLogs(input);

        // Validate logs are not empty or too short
        if (logs == null || logs.isEmpty() || logs.length() < MIN_LOG_LENGTH) {
            return new AgentOutput(false, Map.of(), false, "Input logs are empty or too short");
        }

        // Truncate logs if they exceed the maximum length
        if (logs.length() > MAX_LOG_LENGTH) {
            logs = logs.substring(logs.length() - TRUNCATED_LENGTH);
        }

        // Call Gemini with the logs
        String prompt = buildPrompt(logs);
        String response;
        try {
            response = callGemini(prompt);
        } catch (Exception e) {
            log.error("Failed to call Gemini for log analysis", e);
            return new AgentOutput(false, Map.of(), false, "Failed to call Gemini API: " + e.getMessage());
        }

        // Parse response into structured report
        Map<String, Object> report = parseReport(response);

        return new AgentOutput(true, report, false, null);
    }

    private String extractLogs(AgentInput input) {
        if (input.data() == null) {
            return null;
        }
        Object logs = input.data().get("logs");
        return logs != null ? logs.toString() : null;
    }

    private String buildPrompt(String logs) {
        return "Analyze the following logs and return a JSON report with these fields: " +
                "errorType, rootCause, affectedComponents (array), suggestedFix, confidenceScore (0.0-1.0). " +
                "If no failure is detected, set errorType to \"none\".\n\n" +
                "```\n" + logs + "\n```";
    }

    Map<String, Object> parseReport(String response) {
        if (response == null || response.isBlank()) {
            log.warn("Empty response from Gemini, returning default report");
            return defaultReport();
        }

        try {
            String json = extractJson(response);
            Map<String, Object> report = objectMapper.readValue(json, new TypeReference<>() {});
            return report != null ? report : defaultReport();
        } catch (Exception e) {
            log.warn("Failed to parse Gemini response as JSON report: {}", e.getMessage());
            return defaultReport();
        }
    }

    private String extractJson(String response) {
        String trimmed = response.trim();

        // Try to extract JSON from markdown code block
        if (trimmed.contains("```json")) {
            int start = trimmed.indexOf("```json") + 7;
            int end = trimmed.indexOf("```", start);
            if (end > start) {
                return trimmed.substring(start, end).trim();
            }
        }

        // Try to extract JSON from generic code block
        if (trimmed.contains("```")) {
            int start = trimmed.indexOf("```") + 3;
            // Skip language identifier on same line
            int newline = trimmed.indexOf('\n', start);
            if (newline > start) {
                start = newline + 1;
            }
            int end = trimmed.indexOf("```", start);
            if (end > start) {
                return trimmed.substring(start, end).trim();
            }
        }

        // Try to find object braces directly
        int objStart = trimmed.indexOf('{');
        int objEnd = trimmed.lastIndexOf('}');
        if (objStart >= 0 && objEnd > objStart) {
            return trimmed.substring(objStart, objEnd + 1);
        }

        return trimmed;
    }

    private Map<String, Object> defaultReport() {
        return Map.of(
                "errorType", "none",
                "rootCause", "Unable to determine root cause",
                "affectedComponents", List.of(),
                "suggestedFix", "No fix suggested",
                "confidenceScore", 0.0
        );
    }
}
