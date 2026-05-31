package com.agentic.engine;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves template references in workflow step inputs.
 * Supports:
 * - {{trigger.<field>}} - references fields from the trigger payload
 * - {{steps.<stepName>.output.<field>}} - references output fields from previous steps
 * - {{steps.<stepName>.output}} - references the entire output of a previous step
 */
@Component
public class TemplateResolver {

    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{\\{(.+?)}}");
    private static final String TRIGGER_PREFIX = "trigger.";
    private static final String STEPS_PREFIX = "steps.";

    /**
     * Resolves all template references in the given input map.
     *
     * @param input          the step input map containing template references
     * @param triggerPayload the trigger payload data
     * @param stepOutputs    map of step name to its output data
     * @return a new map with all templates resolved to their values
     */
    public Map<String, String> resolve(Map<String, String> input,
                                       Map<String, Object> triggerPayload,
                                       Map<String, Map<String, Object>> stepOutputs) {
        Map<String, String> resolved = new HashMap<>();
        for (Map.Entry<String, String> entry : input.entrySet()) {
            resolved.put(entry.getKey(), resolveValue(entry.getValue(), triggerPayload, stepOutputs));
        }
        return resolved;
    }

    /**
     * Resolves a single template string value.
     */
    String resolveValue(String value, Map<String, Object> triggerPayload,
                        Map<String, Map<String, Object>> stepOutputs) {
        Matcher matcher = TEMPLATE_PATTERN.matcher(value);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String expression = matcher.group(1).trim();
            String replacement = resolveExpression(expression, triggerPayload, stepOutputs);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    private String resolveExpression(String expression,
                                     Map<String, Object> triggerPayload,
                                     Map<String, Map<String, Object>> stepOutputs) {
        if (expression.startsWith(TRIGGER_PREFIX)) {
            String field = expression.substring(TRIGGER_PREFIX.length());
            Object val = triggerPayload.get(field);
            return val != null ? val.toString() : "";
        }

        if (expression.startsWith(STEPS_PREFIX)) {
            return resolveStepReference(expression.substring(STEPS_PREFIX.length()), stepOutputs);
        }

        return "{{" + expression + "}}";
    }

    private String resolveStepReference(String path, Map<String, Map<String, Object>> stepOutputs) {
        // path format: <stepName>.output or <stepName>.output.<field>
        int firstDot = path.indexOf('.');
        if (firstDot < 0) {
            return "";
        }

        String stepName = path.substring(0, firstDot);
        String remainder = path.substring(firstDot + 1);

        Map<String, Object> output = stepOutputs.get(stepName);
        if (output == null) {
            return "";
        }

        if ("output".equals(remainder)) {
            // Return entire output as string
            return output.toString();
        }

        if (remainder.startsWith("output.")) {
            String field = remainder.substring("output.".length());
            Object val = output.get(field);
            return val != null ? val.toString() : "";
        }

        return "";
    }
}
