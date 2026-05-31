package com.agentic.engine;

import com.agentic.config.AgentsConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Loads and validates workflow definitions from YAML files in the classpath
 * under workflows/ directory.
 */
@Component
@Slf4j
public class WorkflowLoader {

    private static final String WORKFLOWS_PATH = "classpath:workflows/*.yaml";

    private final AgentsConfig agentsConfig;
    private final Map<String, WorkflowDefinition> workflows = new HashMap<>();

    public WorkflowLoader(AgentsConfig agentsConfig) {
        this.agentsConfig = agentsConfig;
    }

    @PostConstruct
    public void loadWorkflows() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources(WORKFLOWS_PATH);
            for (Resource resource : resources) {
                try {
                    WorkflowDefinition def = parseWorkflow(resource.getInputStream());
                    List<String> errors = validate(def);
                    if (errors.isEmpty()) {
                        workflows.put(def.name(), def);
                        log.info("Loaded workflow: {}", def.name());
                    } else {
                        log.error("Invalid workflow '{}': {}", resource.getFilename(), errors);
                    }
                } catch (Exception e) {
                    log.error("Failed to parse workflow file '{}': {}", resource.getFilename(), e.getMessage());
                }
            }
            log.info("Loaded {} workflow(s)", workflows.size());
        } catch (IOException e) {
            log.warn("No workflow files found at {}: {}", WORKFLOWS_PATH, e.getMessage());
        }
    }

    /**
     * Retrieves a loaded workflow definition by name.
     *
     * @param name the workflow name
     * @return the workflow definition
     * @throws IllegalArgumentException if the workflow is not found
     */
    public WorkflowDefinition load(String name) {
        WorkflowDefinition def = workflows.get(name);
        if (def == null) {
            throw new IllegalArgumentException("Workflow not found: " + name);
        }
        return def;
    }

    /**
     * Returns all loaded workflow definitions.
     */
    public Collection<WorkflowDefinition> getAll() {
        return Collections.unmodifiableCollection(workflows.values());
    }

    /**
     * Parses a YAML input stream into a WorkflowDefinition.
     */
    @SuppressWarnings("unchecked")
    WorkflowDefinition parseWorkflow(InputStream inputStream) {
        Yaml yaml = new Yaml();
        Map<String, Object> doc = yaml.load(inputStream);

        String name = (String) doc.get("name");
        String trigger = (String) doc.get("trigger");
        String description = (String) doc.get("description");

        List<Map<String, Object>> stepsRaw = (List<Map<String, Object>>) doc.get("steps");
        List<WorkflowStep> steps = new ArrayList<>();

        if (stepsRaw != null) {
            for (Map<String, Object> stepRaw : stepsRaw) {
                String stepName = (String) stepRaw.get("name");
                String agent = (String) stepRaw.get("agent");
                Map<String, String> input = (Map<String, String>) stepRaw.get("input");
                String onError = (String) stepRaw.getOrDefault("onError", "halt");
                boolean requiresReview = Boolean.TRUE.equals(stepRaw.get("requiresReview"));

                steps.add(new WorkflowStep(
                        stepName,
                        agent,
                        input != null ? input : Map.of(),
                        onError,
                        requiresReview
                ));
            }
        }

        return new WorkflowDefinition(name, trigger, description, steps);
    }

    /**
     * Validates a workflow definition for correctness:
     * - No duplicate step names
     * - All agent references must exist in AgentsConfig
     */
    List<String> validate(WorkflowDefinition def) {
        List<String> errors = new ArrayList<>();

        if (def.name() == null || def.name().isBlank()) {
            errors.add("Workflow name is required");
        }

        if (def.trigger() == null || def.trigger().isBlank()) {
            errors.add("Workflow trigger is required");
        }

        if (def.steps() == null || def.steps().isEmpty()) {
            errors.add("Workflow must have at least one step");
        } else {
            // Check for duplicate step names
            Set<String> stepNames = new HashSet<>();
            for (WorkflowStep step : def.steps()) {
                if (step.name() == null || step.name().isBlank()) {
                    errors.add("Step name is required");
                } else if (!stepNames.add(step.name())) {
                    errors.add("Duplicate step name: " + step.name());
                }

                // Check agent references
                if (step.agent() == null || step.agent().isBlank()) {
                    errors.add("Step '" + step.name() + "' is missing agent reference");
                } else if (agentsConfig.getAgent(step.agent()).isEmpty()) {
                    errors.add("Step '" + step.name() + "' references undefined agent: " + step.agent());
                }
            }
        }

        return errors;
    }
}
