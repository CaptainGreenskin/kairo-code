package io.kairo.code.service.agent;

import io.kairo.api.hook.HookHandler;
import io.kairo.api.hook.HookPhase;
import io.kairo.api.hook.HookResult;
import io.kairo.api.hook.PostActingEvent;
import io.kairo.api.hook.PostReasoningEvent;
import io.kairo.api.message.Content;
import io.kairo.code.service.AgentEvent;
import reactor.core.publisher.Sinks;

/**
 * Lightweight hook injected into child agent sessions spawned by the task tool.
 * Forwards the child's tool calls and results to the parent session's event sink
 * as {@link AgentEvent.EventType#SUBAGENT_EVENT} events, so the frontend can show
 * what the subagent is doing instead of an opaque "Running task..." spinner.
 */
public final class ChildEventForwardingHook {

    private final Sinks.Many<AgentEvent> parentSink;
    private final String parentSessionId;
    private final String taskId;
    private final String taskDescription;

    public ChildEventForwardingHook(Sinks.Many<AgentEvent> parentSink,
                                     String parentSessionId,
                                     String taskId,
                                     String taskDescription) {
        this.parentSink = parentSink;
        this.parentSessionId = parentSessionId;
        this.taskId = taskId;
        this.taskDescription = taskDescription;
    }

    @HookHandler(HookPhase.POST_REASONING)
    public HookResult<PostReasoningEvent> onPostReasoning(PostReasoningEvent event) {
        if (event.response() != null && event.response().contents() != null) {
            for (var content : event.response().contents()) {
                if (content instanceof Content.ToolUseContent tc) {
                    AgentRuntimeContext.emitSerialized(parentSink,
                            AgentEvent.subagentEvent(parentSessionId, taskId, taskDescription,
                                    "TOOL_CALL", tc.toolName(), false));
                }
            }
        }
        return HookResult.proceed(event);
    }

    @HookHandler(HookPhase.POST_ACTING)
    public HookResult<PostActingEvent> onPostActing(PostActingEvent event) {
        String toolName = event.toolName();
        boolean isError = event.result() != null && event.result().isError();
        AgentRuntimeContext.emitSerialized(parentSink,
                AgentEvent.subagentEvent(parentSessionId, taskId, taskDescription,
                        "TOOL_RESULT", toolName, isError));
        return HookResult.proceed(event);
    }
}
