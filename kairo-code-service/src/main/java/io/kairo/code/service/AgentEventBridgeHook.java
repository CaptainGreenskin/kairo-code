package io.kairo.code.service;

import io.kairo.api.hook.HookResult;
import io.kairo.api.hook.HookPhase;
import io.kairo.api.hook.PostActingEvent;
import io.kairo.api.hook.PostReasoningEvent;
import io.kairo.api.hook.ToolResultEvent;
import io.kairo.api.message.Content;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A hook that captures agent lifecycle events and writes them as
 * {@link AgentEvent} into a Reactor {@link Sinks.Many}.
 *
 * <p>This is the transport-agnostic replacement for {@code AgentEventListener}.
 * Register this hook via {@code SessionOptions.withHooks(List.of(bridgeHook))}.
 */
public class AgentEventBridgeHook {

    private static final Logger log = LoggerFactory.getLogger(AgentEventBridgeHook.class);

    private final Sinks.Many<AgentEvent> sink;
    private final String sessionId;

    /** Tracks pending tool calls keyed by toolUseId → toolName. */
    private final Map<String, String> pendingToolCalls = new ConcurrentHashMap<>();

    /** Whether plan steps have already been detected for this session. */
    private volatile boolean planStepsDetected = false;

    public AgentEventBridgeHook(Sinks.Many<AgentEvent> sink, String sessionId) {
        this.sink = sink;
        this.sessionId = sessionId;
    }

    /**
     * Called after the model reasons about the request.
     * Emits TEXT_CHUNK for text content and TOOL_CALL for tool_use content.
     */
    @io.kairo.api.hook.HookHandler(HookPhase.POST_REASONING)
    public HookResult<PostReasoningEvent> onPostReasoning(PostReasoningEvent event) {
        if (event.response() != null && event.response().contents() != null) {
            for (Content content : event.response().contents()) {
                if (content instanceof Content.TextContent text) {
                    String textContent = text.text();
                    if (textContent != null && !textContent.isBlank()) {
                        emit(AgentEvent.textChunk(sessionId, textContent));

                        // Detect plan steps on first text block that contains them
                        if (!planStepsDetected) {
                            List<String> steps = PlanStepParser.parse(textContent);
                            if (!steps.isEmpty()) {
                                planStepsDetected = true;
                                emit(AgentEvent.planSteps(sessionId, steps));
                            }
                        }
                    }
                } else if (content instanceof Content.ToolUseContent toolUse) {
                    String toolUseId = toolUse.toolId();
                    String toolName = toolUse.toolName();
                    Map<String, Object> input = toolUse.input();

                    pendingToolCalls.put(toolUseId, toolName);

                    boolean requiresApproval = requiresApproval(toolName);

                    emit(AgentEvent.toolCall(sessionId, toolName, input, toolUseId, requiresApproval));
                }
            }
        }
        return HookResult.proceed(event);
    }

    /**
     * Called after a tool completes. Emits TOOL_RESULT events.
     */
    @io.kairo.api.hook.HookHandler(HookPhase.POST_ACTING)
    public HookResult<PostActingEvent> onPostActing(PostActingEvent event) {
        if (event.result() != null) {
            String toolUseId = event.result().toolUseId();
            String resultContent = event.result().content();
            emit(AgentEvent.toolResult(sessionId, toolUseId, resultContent));
        }
        return HookResult.proceed(event);
    }

    /**
     * Shortcut annotation for TOOL_RESULT phase.
     */
    @io.kairo.api.hook.OnToolResult
    public void onToolResult(ToolResultEvent event) {
        if (event.result() != null) {
            String toolUseId = event.result().toolUseId();
            String resultContent = event.result().content();
            emit(AgentEvent.toolResult(sessionId, toolUseId, resultContent));
        }
    }

    private void emit(AgentEvent event) {
        Sinks.EmitResult result = sink.tryEmitNext(event);
        if (result.isFailure()) {
            log.warn("Failed to emit event {} for session {}: {}", event.type(), sessionId, result);
        }
    }

    /**
     * Determine if a tool call requires user approval.
     * Write tools and bash are considered risky.
     */
    private static boolean requiresApproval(String toolName) {
        return switch (toolName) {
            case "bash", "write_file", "edit_file" -> true;
            default -> false;
        };
    }
}
