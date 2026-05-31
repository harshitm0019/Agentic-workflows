package com.agentic.engine;

import java.util.List;

public record WorkflowDefinition(
    String name,
    String trigger,
    String description,
    List<WorkflowStep> steps
) {}
