package com.agentic.agent;

import com.agentic.config.AgentConfig;
import com.agentic.dto.AgentInput;
import com.agentic.dto.AgentOutput;
// NOTE: Switched to LangChain4j. Original: import com.agentic.service.GeminiService;
import com.agentic.service.LangChainGeminiService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public class ChangeSuggestionAgent extends BaseAgent {

    private final ObjectMapper objectMapper;

    public ChangeSuggestionAgent(String name, AgentConfig config, LangChainGeminiService geminiService, ObjectMapper objectMapper) {
        super(name, config, geminiService);
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentOutput execute(AgentInput input) {
        String content = extractContent(input);

        // Validate input is not empty
        if (content == null || content.isBlank() || content.equals("[]") || content.equals("null")) {
            // No findings = code is clean. Complete successfully without requiring review.
            return new AgentOutput(true,
                    Map.of("summary", "No issues to fix. Code review found no problems."),
                    false, null);
        }

        // Call Gemini with the content
        String prompt = buildPrompt(content, input);
        String response;
        try {
            response = callGemini(prompt);
        } catch (Exception e) {
            log.error("Failed to call Gemini for change suggestion", e);
            return new AgentOutput(false, Map.of(), false,
                    "Failed to call Gemini API: " + e.getMessage());
        }

        // Check if Gemini indicated no fix is possible
        if (isNoFixResponse(response)) {
            String explanation = extractExplanation(response);
            return new AgentOutput(false,
                    Map.of("explanation", explanation),
                    false,
                    "Unable to generate a fix: " + explanation);
        }

        // Extract patch from response
        String patch = extractPatch(response);

        if (patch == null || patch.isBlank()) {
            return new AgentOutput(false, Map.of(), false,
                    "Unable to generate a valid unified diff patch. Manual intervention suggested.");
        }

        return new AgentOutput(true,
                Map.of("patch", patch, "format", "unified-diff"),
                true, null);
    }

    /**
     * Extracts the content to analyze from the input.
     * Supports both "findings" (from code review) and "report" (from log analysis).
     */
    String extractContent(AgentInput input) {
        if (input.data() == null) {
            return null;
        }

        // Try "findings" first (from code review agent)
        Object findings = input.data().get("findings");
        if (findings != null) {
            return convertToString(findings);
        }

        // Try "report" (from log analysis agent)
        Object report = input.data().get("report");
        if (report != null) {
            return convertToString(report);
        }

        return null;
    }

    private String convertToString(Object obj) {
        if (obj instanceof String s) {
            return s;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("Failed to serialize input to JSON: {}", e.getMessage());
            return obj.toString();
        }
    }

    private String buildPrompt(String content, AgentInput input) {
        String inputType = determineInputType(input);
        return "Based on the following " + inputType + ", generate a code fix as a unified diff patch.\n" +
                "The patch MUST be in standard unified diff format that can be applied with `git apply`.\n" +
                "Include proper file paths (a/ and b/ prefixes), line numbers, and context lines.\n" +
                "If you absolutely cannot generate a fix, start your response with 'NO_FIX:' followed by an explanation.\n\n" +
                inputType + ":\n```\n" + content + "\n```";
    }

    private String determineInputType(AgentInput input) {
        if (input.data() != null && input.data().containsKey("findings")) {
            return "code review findings";
        }
        return "failure analysis report";
    }

    /**
     * Checks if the response indicates that no fix is possible.
     */
    boolean isNoFixResponse(String response) {
        if (response == null || response.isBlank()) {
            return true;
        }
        String trimmed = response.trim();
        return trimmed.startsWith("NO_FIX:");
    }

    /**
     * Extracts the explanation from a NO_FIX response.
     */
    String extractExplanation(String response) {
        if (response == null || response.isBlank()) {
            return "No response received from Gemini";
        }
        String trimmed = response.trim();
        if (trimmed.startsWith("NO_FIX:")) {
            return trimmed.substring("NO_FIX:".length()).trim();
        }
        return trimmed;
    }

    /**
     * Extracts the unified diff patch from the Gemini response.
     * Handles markdown code blocks and plain text responses.
     */
    String extractPatch(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }

        String trimmed = response.trim();

        // Try to extract from ```diff code block
        if (trimmed.contains("```diff")) {
            int start = trimmed.indexOf("```diff") + 7;
            int end = trimmed.indexOf("```", start);
            if (end > start) {
                return trimmed.substring(start, end).trim();
            }
        }

        // Try to extract from generic code block
        if (trimmed.contains("```")) {
            int start = trimmed.indexOf("```") + 3;
            // Skip language identifier on same line
            int newline = trimmed.indexOf('\n', start);
            if (newline > start) {
                start = newline + 1;
            }
            int end = trimmed.indexOf("```", start);
            if (end > start) {
                String content = trimmed.substring(start, end).trim();
                if (looksLikeDiff(content)) {
                    return content;
                }
            }
        }

        // Try to find diff content directly (starts with --- or diff --)
        if (looksLikeDiff(trimmed)) {
            return trimmed;
        }

        // Try to extract diff portion from mixed response
        int diffStart = findDiffStart(trimmed);
        if (diffStart >= 0) {
            return trimmed.substring(diffStart).trim();
        }

        return null;
    }

    private boolean looksLikeDiff(String text) {
        return text.startsWith("---") || text.startsWith("diff --") || text.startsWith("@@");
    }

    private int findDiffStart(String text) {
        int idx = text.indexOf("\n---");
        if (idx >= 0) {
            return idx + 1;
        }
        idx = text.indexOf("\ndiff --");
        if (idx >= 0) {
            return idx + 1;
        }
        return -1;
    }
}
