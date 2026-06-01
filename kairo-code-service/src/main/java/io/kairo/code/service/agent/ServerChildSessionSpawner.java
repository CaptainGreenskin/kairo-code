package io.kairo.code.service.agent;

import io.kairo.api.model.ModelProvider;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentFactory;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.code.core.task.ChildSessionSpawner;
import io.kairo.code.service.AgentEvent;
import java.nio.file.Path;
import java.util.List;
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

    public ServerChildSessionSpawner(CodeAgentConfig parentConfig, ModelProvider modelProvider,
                                      Sinks.Many<AgentEvent> parentSink, String parentSessionId) {
        this.parentConfig = parentConfig;
        this.modelProvider = modelProvider;
        this.parentSink = parentSink;
        this.parentSessionId = parentSessionId;
    }

    @Override
    public CodeAgentSession spawn(String taskId, Path workingDir) {
        CodeAgentConfig childConfig = new CodeAgentConfig(
                parentConfig.apiKey(),
                parentConfig.baseUrl(),
                parentConfig.modelName(),
                parentConfig.maxIterations(),
                workingDir.toString(),
                parentConfig.mcpConfig(),
                parentConfig.toolBudgetForce(),
                parentConfig.repetitiveToolThreshold(),
                parentConfig.thinkingBudget(),
                parentConfig.llmClassifier());

        // Extract task description from the taskId for display purposes.
        // The actual description comes from the tool input at call time;
        // we use taskId as a fallback label.
        String description = taskId;

        List<Object> hooks = parentSink != null
                ? List.of(new ChildEventForwardingHook(
                        parentSink, parentSessionId, taskId, description))
                : List.of();

        return CodeAgentFactory.createSession(childConfig,
                CodeAgentFactory.SessionOptions.empty()
                        .withModelProvider(modelProvider)
                        .withHooks(hooks)
                        .asChildSession());
    }
}
