package io.kairo.code.core.workflow;

import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.task.ChildSessionSpawner;
import io.kairo.code.core.task.SubagentRegistry;
import io.kairo.code.core.workspace.WorktreeWorkspaceProvider;
import java.util.Objects;

/**
 * Dependency bundle for scripted workflow execution.
 * Registered in {@code ToolContext} bean map, analogous to {@code TaskToolDependencies}.
 */
public record WorkflowDependencies(
        ChildSessionSpawner spawner,
        WorktreeWorkspaceProvider workspaceProvider,
        CodeAgentConfig parentConfig,
        WorkflowProgressEmitter progressEmitter,
        SubagentRegistry subagentRegistry
) {
    public WorkflowDependencies {
        Objects.requireNonNull(spawner, "spawner");
        Objects.requireNonNull(parentConfig, "parentConfig");
        if (progressEmitter == null) progressEmitter = WorkflowProgressEmitter.SLF4J_INSTANCE;
    }
}
