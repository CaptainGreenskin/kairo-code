package io.kairo.code.core.workflow;

import java.util.List;

/**
 * Parsed metadata from a workflow script's {@code export const meta = {...}} block.
 */
public record WorkflowMeta(
        String name,
        String description,
        List<Phase> phases,
        String whenToUse
) {
    public record Phase(String title, String detail, String model) {}

    public WorkflowMeta {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("WorkflowMeta.name is required");
        }
        if (phases == null) phases = List.of();
    }
}
