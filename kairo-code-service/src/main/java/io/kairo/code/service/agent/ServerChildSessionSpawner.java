package io.kairo.code.service.agent;

import io.kairo.api.model.ModelProvider;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentFactory;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.code.core.task.AgentType;
import io.kairo.code.core.task.ChildSessionSpawner;
import io.kairo.code.core.task.TaskToolDependencies;
import io.kairo.code.core.workspace.WorktreeWorkspaceProvider;
import io.kairo.code.service.AgentEvent;
import java.nio.file.Path;
import java.util.List;
import javax.annotation.Nullable;
import reactor.core.publisher.Sinks;

/**
 * Server-side child session spawner for the task tool. Creates a headless child
 * {@link CodeAgentSession} with {@code asChildSession()} and injects a
 * {@link ChildEventForwardingHook} that pipes the child's tool calls/results
 * to the parent session's event sink as {@code SUBAGENT_EVENT} events.
 */
public final class ServerChildSessionSpawner implements ChildSessionSpawner {

    private final CodeAgentConfig parentConfig;
    private final ModelProvider modelProvider;
    private final Sinks.Many<AgentEvent> parentSink;
    private final String parentSessionId;
    private final int depth;
    @Nullable private final WorktreeWorkspaceProvider workspaceProvider;

    public ServerChildSessionSpawner(CodeAgentConfig parentConfig, ModelProvider modelProvider,
                                      Sinks.Many<AgentEvent> parentSink, String parentSessionId) {
        this(parentConfig, modelProvider, parentSink, parentSessionId, 0, null);
    }

    public ServerChildSessionSpawner(CodeAgentConfig parentConfig, ModelProvider modelProvider,
                                      Sinks.Many<AgentEvent> parentSink, String parentSessionId,
                                      int depth, @Nullable WorktreeWorkspaceProvider workspaceProvider) {
        this.parentConfig = parentConfig;
        this.modelProvider = modelProvider;
        this.parentSink = parentSink;
        this.parentSessionId = parentSessionId;
        this.depth = depth;
        this.workspaceProvider = workspaceProvider;
    }

    @Override
    public CodeAgentSession spawn(String taskId, Path workingDir, AgentType agentType, String modelOverride) {
        return spawnInternal(taskId, workingDir, agentType, modelOverride, null, null);
    }

    @Override
    public CodeAgentSession spawn(String taskId, Path workingDir, AgentType agentType,
                                   String modelOverride, String name,
                                   io.kairo.code.core.task.SubagentRegistry registry) {
        return spawnInternal(taskId, workingDir, agentType, modelOverride, name, registry);
    }

    private CodeAgentSession spawnInternal(String taskId, Path workingDir, AgentType agentType,
                                            String modelOverride, @Nullable String name,
                                            @Nullable io.kairo.code.core.task.SubagentRegistry registry) {
        String effectiveModel = (modelOverride != null && !modelOverride.isBlank())
                ? modelOverride : parentConfig.modelName();

        CodeAgentConfig childConfig = new CodeAgentConfig(
                parentConfig.apiKey(),
                parentConfig.baseUrl(),
                effectiveModel,
                parentConfig.maxIterations(),
                workingDir.toString(),
                parentConfig.mcpConfig(),
                parentConfig.toolBudgetForce(),
                parentConfig.repetitiveToolThreshold(),
                parentConfig.thinkingBudget(),
                parentConfig.llmClassifier());

        String description = taskId;

        java.util.ArrayList<Object> hooks = new java.util.ArrayList<>();
        if (parentSink != null) {
            hooks.add(new ChildEventForwardingHook(
                    parentSink, parentSessionId, taskId, description));
        }
        if (name != null && !name.isBlank() && registry != null) {
            hooks.add(new io.kairo.code.core.hook.SubagentInboxHook(registry, name));
        }

        CodeAgentFactory.SessionOptions opts = CodeAgentFactory.SessionOptions.empty()
                .withModelProvider(modelOverride != null && !modelOverride.isBlank()
                        ? CodeAgentFactory.buildModelProvider(parentConfig.apiKey(), parentConfig.baseUrl(), parentConfig.modelName())
                        : modelProvider)
                .withHooks(hooks)
                .asChildSession();

        if (agentType != null) {
            opts = opts.withAgentType(agentType);
        }

        if (depth < 1 && workspaceProvider != null) {
            ServerChildSessionSpawner childSpawner = new ServerChildSessionSpawner(
                    childConfig, modelProvider, parentSink, parentSessionId, depth + 1, workspaceProvider);
            TaskToolDependencies childDeps = new TaskToolDependencies(
                    workspaceProvider, childSpawner,
                    (tid, desc, stats, path) -> reactor.core.publisher.Mono.just(
                            io.kairo.code.core.task.WorktreeMergeChoice.DISCARD));
            opts = opts.withTaskTool(childDeps);
        }

        return CodeAgentFactory.createSession(childConfig, opts);
    }
}
