package com.agentic.agent;

import com.agentic.config.AgentConfig;
import com.agentic.dto.AgentInput;
import com.agentic.dto.AgentOutput;
import com.agentic.service.GeminiService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

@Slf4j
public class CodeReviewAgent extends BaseAgent {

    private static final int MAX_DIFF_LINES = 2000;

    private final ObjectMapper objectMapper;

    public CodeReviewAgent(String name, AgentConfig config, GeminiService geminiService, ObjectMapper objectMapper) {
        super(name, config, geminiService);
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentOutput execute(AgentInput input) {
        String diff = extractDiff(input);

        // Validate diff is not empty
        if (diff == null || diff.isBlank()) {
            return new AgentOutput(false, Map.of(), false, "Input diff is empty");
        }

        // Validate diff does not exceed 2000 lines
        int lineCount = countLines(diff);
        if (lineCount > MAX_DIFF_LINES) {
            return new AgentOutput(false, Map.of(), false,
                    String.format("Diff exceeds maximum size limit of %d lines (got %d lines)", MAX_DIFF_LINES, lineCount));
        }

        // Call Gemini with the diff
        String prompt = buildPrompt(diff);
        String response;
        try {
            response = callGemini(prompt);
        } catch (Exception e) {
            log.error("Failed to call Gemini for code review", e);
            return new AgentOutput(false, Map.of(), false, "Failed to call Gemini API: " + e.getMessage());
        }

        // Parse response into structured findings
        List<Map<String, Object>> findings = parseFindings(response);

        if (findings.isEmpty()) {
            return new AgentOutput(true,
                    Map.of("findings", List.of(), "summary", "No issues found in the code diff."),
                    false, null);
        }

        return new AgentOutput(true,
                Map.of("findings", findings, "summary", String.format("Found %d issue(s) in the code diff.", findings.size())),
                false, null);
    }

    private String extractDiff(AgentInput input) {
        if (input.data() == null) {
            return null;
        }
        Object diff = input.data().get("diff");
        return diff != null ? diff.toString() : null;
    }

    private int countLines(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        int count = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    private String buildPrompt(String diff) {
        return "Please review the following code diff and return your findings as a JSON array. " +
                "Each finding must have these fields: filePath, lineNumber, severity (error|warning|suggestion), message. " +
                "If no issues are found, return an empty JSON array [].\n\n" +
                "```diff\n" + diff + "\n```";
    }

    List<Map<String, Object>> parseFindings(String response) {
        if (response == null || response.isBlank()) {
            log.warn("Empty response from Gemini, returning empty findings");
            return List.of();
        }

        try {
            // Extract JSON from the response (may be wrapped in markdown code blocks)
            String json = extractJson(response);
            List<Map<String, Object>> findings = objectMapper.readValue(json, new TypeReference<>() {});
            return findings != null ? findings : List.of();
        } catch (Exception e) {
            log.warn("Failed to parse Gemini response as JSON findings: {}", e.getMessage());
            return List.of();
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

        // Try to find array brackets directly
        int arrayStart = trimmed.indexOf('[');
        int arrayEnd = trimmed.lastIndexOf(']');
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            return trimmed.substring(arrayStart, arrayEnd + 1);
        }

        return trimmed;
    }
}
