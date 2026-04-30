package io.kairo.code.server.session;

import io.kairo.api.hook.HookResult;
import io.kairo.api.hook.HookPhase;
import io.kairo.api.hook.PreActingEvent;
import io.kairo.api.hook.PostActingEvent;
import io.kairo.api.hook.PostReasoningEvent;
import io.kairo.api.hook.ToolResultEvent;
import io.kairo.api.message.Content;
import io.kairo.api.tool.ToolCallRequest;
import io.kairo.code.service.AgentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A hook listener that captures agent lifecycle events and tool execution
 * events, then forwards them as {@link AgentEvent} payloads to the
 * WebSocket STOMP topic.
 *
 * <p>Registered as a hook on the AgentBuilder so it receives real-time
 * events during agent execution.
 */
public class AgentEventListener {

    private static final Logger log = LoggerFactory.getLogger(AgentEventListener.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final String sessionId;
    private final WebSocketApprovalHandler approvalHandler;

    /** Tracks pending tool calls keyed by toolName+input hash → toolUseId. */
    private final Map<String, String> pendingToolCalls = new ConcurrentHashMap<>();

    public AgentEventListener(SimpMessagingTemplate messagingTemplate,
                              String sessionId,
                              WebSocketApprovalHandler approvalHandler) {
        this.messagingTemplate = messagingTemplate;
        this.sessionId = sessionId;
        this.approvalHandler = approvalHandler;
    }

    /**
     * Called after the model reasons about the request.
     * Detects tool calls in the response and fires TOOL_CALL events.
     */
    @io.kairo.api.hook.HookHandler(HookPhase.POST_REASONING)
    public HookResult<PostReasoningEvent> onPostReasoning(PostReasoningEvent event) {
        // Detect tool calls in the model response
        if (event.response() != null && event.response().contents() != null) {
            for (Content content : event.response().contents()) {
                if (content instanceof Content.ToolUseContent toolUse) {
                    String toolUseId = toolUse.toolId();
                    String toolName = toolUse.toolName();
                    Map<String, Object> input = toolUse.input();

                    pendingToolCalls.put(toolUseId, toolName);

                    boolean requiresApproval = requiresApproval(toolName);

                    AgentEvent toolCallEvent = AgentEvent.toolCall(
                            sessionId, toolName, input, toolUseId, requiresApproval);
                    push(toolCallEvent);
                }
            }
        }
        return HookResult.proceed(event);
    }

    /**
     * Called before a tool executes. Not used for tool call detection
     * (that happens in POST_REASONING), but useful for timing.
     */
    @io.kairo.api.hook.HookHandler(HookPhase.PRE_ACTING)
    public HookResult<PreActingEvent> onPreActing(PreActingEvent event) {
        return HookResult.proceed(event);
    }

    /**
     * Called after a tool completes. Fires TOOL_RESULT events.
     */
    @io.kairo.api.hook.HookHandler(HookPhase.POST_ACTING)
    public HookResult<PostActingEvent> onPostActing(PostActingEvent event) {
        if (event.result() != null) {
            String toolUseId = event.result().toolUseId();
            String resultContent = event.result().content();
            AgentEvent resultEvent = AgentEvent.toolResult(sessionId, toolUseId, resultContent);
            push(resultEvent);
        }
        return HookResult.proceed(event);
    }

    /**
     * Shortcut annotation for TOOL_RESULT phase.
     * Also fires TOOL_RESULT events via ToolResultEvent.
     */
    @io.kairo.api.hook.OnToolResult
    public void onToolResult(ToolResultEvent event) {
        // Already handled by onPostActing; this is a backup path
        if (event.result() != null) {
            String toolUseId = event.result().toolUseId();
            String resultContent = event.result().content();
            AgentEvent resultEvent = AgentEvent.toolResult(sessionId, toolUseId, resultContent);
            push(resultEvent);
        }
    }

    /**
     * Push an event to the STOMP topic.
     */
    private void push(AgentEvent event) {
        try {
            messagingTemplate.convertAndSend("/topic/session/" + sessionId, event);
        } catch (Exception e) {
            log.error("Failed to push event to session {}", sessionId, e);
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
