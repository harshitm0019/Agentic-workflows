package com.agentic.engine;

import java.util.Map;

public record WorkflowStep(
    String name,
    String agent,
    Map<String, String> input,
    String onError,
    boolean requiresReview
) {}
