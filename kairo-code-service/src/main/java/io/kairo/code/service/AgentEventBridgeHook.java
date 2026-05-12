package io.kairo.code.service;

import io.kairo.api.agent.AgentState;
import io.kairo.api.hook.HookResult;
import io.kairo.api.hook.HookPhase;
import io.kairo.api.hook.PostActingEvent;
import io.kairo.api.hook.PostReasoningEvent;
import io.kairo.api.hook.SessionEndEvent;
import io.kairo.api.hook.ToolResultEvent;
import io.kairo.api.message.Content;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.Set;
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
    private final String workingDir;
    private final ToolProgressTracker progressTracker;
    /** Optional telemetry counter; nullable for tests / legacy constructors. */
    private final SessionDiagnosticsTracker diagnosticsTracker;

    /** Tool-use id → tool name, populated on POST_REASONING. POST_ACTING reads this to identify
     *  which tool finished (PostActingEvent already carries toolName, but we also use the map for
     *  TOOL_CALL dedupe between this hook and {@code WebSocketApprovalHandler}). */
    private final Map<String, String> emittedToolCalls = new ConcurrentHashMap<>();

    /**
     * Set of toolCallIds the {@link WebSocketApprovalHandler} has already announced. When a tool
     * required ASK approval, WSAH emits the TOOL_CALL event before execution; if BridgeHook then
     * also emits in POST_REASONING for the same id, the frontend gets duplicate cards. Skip when
     * the id is in this set.
     */
    private final Set<String> announcedToolCallIds;

    public AgentEventBridgeHook(Sinks.Many<AgentEvent> sink, String sessionId) {
        this(sink, sessionId, ConcurrentHashMap.newKeySet(), null, null, null);
    }

    public AgentEventBridgeHook(
            Sinks.Many<AgentEvent> sink, String sessionId, Set<String> announcedToolCallIds) {
        this(sink, sessionId, announcedToolCallIds, null, null, null);
    }

    public AgentEventBridgeHook(
            Sinks.Many<AgentEvent> sink,
            String sessionId,
            Set<String> announcedToolCallIds,
            String workingDir) {
        this(sink, sessionId, announcedToolCallIds, workingDir, null, null);
    }

    public AgentEventBridgeHook(
            Sinks.Many<AgentEvent> sink,
            String sessionId,
            Set<String> announcedToolCallIds,
            String workingDir,
            ToolProgressTracker progressTracker) {
        this(sink, sessionId, announcedToolCallIds, workingDir, progressTracker, null);
    }

    public AgentEventBridgeHook(
            Sinks.Many<AgentEvent> sink,
            String sessionId,
            Set<String> announcedToolCallIds,
            String workingDir,
            ToolProgressTracker progressTracker,
            SessionDiagnosticsTracker diagnosticsTracker) {
        this.sink = sink;
        this.sessionId = sessionId;
        this.announcedToolCallIds = announcedToolCallIds;
        this.workingDir = workingDir;
        this.progressTracker = progressTracker;
        this.diagnosticsTracker = diagnosticsTracker;
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
                    }
                } else if (content instanceof Content.ToolUseContent toolUse) {
                    String toolUseId = toolUse.toolId();
                    String toolName = toolUse.toolName();
                    Map<String, Object> rawInput = toolUse.input();

                    // Skip if we already emitted TOOL_CALL for this id. This dedupes two paths:
                    //   1. WebSocketApprovalHandler announced it eagerly for ASK tools.
                    //   2. ReasoningPhase replays the same ToolUseContent across multiple synthetic
                    //      ModelResponses in the streaming-eager loop — without dedupe the UI gets
                    //      a phantom card per replay (observed with GLM-5.1 emitting 28+ cards for
                    //      a single 9-tool run).
                    if (emittedToolCalls.putIfAbsent(toolUseId, toolName) != null) {
                        continue;
                    }
                    if (announcedToolCallIds.contains(toolUseId)) {
                        continue;
                    }

                    // Streaming-eager re-injection marker: ReasoningPhase rebuilds the assistant
                    // ModelResponse with a ToolUseContent that carries `_streaming_result` so the
                    // agent loop can feed the eager result back as tool_use → tool_result. The
                    // marker is internal — strip it before sending to the UI so the input panel
                    // shows the real tool arguments, not the result.
                    Map<String, Object> input = rawInput;
                    Object eagerResult = null;
                    if (rawInput != null && rawInput.containsKey("_streaming_result")) {
                        input = new java.util.LinkedHashMap<>(rawInput);
                        eagerResult = ((java.util.Map<String, Object>) input).remove("_streaming_result");
                    }

                    boolean requiresApproval = requiresApproval(toolName);

                    emit(AgentEvent.toolCall(sessionId, toolName, input, toolUseId, requiresApproval));
                    if (progressTracker != null) {
                        progressTracker.register(toolUseId, toolName);
                    }

                    // For streaming-eager tools the actual TOOL_RESULT was already emitted via
                    // POST_ACTING — but it arrived BEFORE this TOOL_CALL on the wire, so the UI
                    // had no card to attach it to and silently dropped it. Re-emit the result
                    // here, after the card exists, so it flips from "Running" to "done". Without
                    // this, every non-approval streaming-eager tool (tree, batch_read, glob, …)
                    // sticks at Running forever even though the agent has finished.
                    if (eagerResult != null) {
                        emit(AgentEvent.toolResult(sessionId, toolUseId, eagerResult.toString()));
                        if (progressTracker != null) {
                            progressTracker.unregister(toolUseId);
                        }
                        maybeEmitTodosSnapshot(toolName);
                    }
                }
            }
        }
        return HookResult.proceed(event);
    }

    /**
     * Called after a tool completes. Emits TOOL_RESULT and, when the tool was a todo_* mutation,
     * a follow-up TODOS_UPDATED event with the on-disk snapshot so the UI can refresh.
     */
    @io.kairo.api.hook.HookHandler(HookPhase.POST_ACTING)
    public HookResult<PostActingEvent> onPostActing(PostActingEvent event) {
        if (event.result() != null) {
            String toolUseId = event.result().toolUseId();
            String resultContent = event.result().content();
            emit(
                    AgentEvent.toolResult(
                            sessionId, toolUseId, resultContent, event.result().metadata()));
            if (progressTracker != null) {
                progressTracker.unregister(toolUseId);
            }
            maybeEmitTodosSnapshot(event.toolName());
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
            emit(
                    AgentEvent.toolResult(
                            sessionId, toolUseId, resultContent, event.result().metadata()));
            if (progressTracker != null) {
                progressTracker.unregister(toolUseId);
            }
            maybeEmitTodosSnapshot(event.toolName());
        }
    }

    /**
     * If {@code toolName} is the agent's todo manager, re-read {@code .kairo/todos.json} and emit a
     * TODOS_UPDATED snapshot. We re-read instead of trusting the tool's result string because
     * {@code TodoWriteTool} returns a confirmation message ({@code "Wrote N todo(s)"}) rather than
     * the full list, and we want the UI to mirror on-disk state authoritatively.
     */
    private void maybeEmitTodosSnapshot(String toolName) {
        if (!"todo_write".equals(toolName) && !"todo_read".equals(toolName)) {
            return;
        }
        if (workingDir == null) {
            return;
        }
        String todosJson = TodoStorage.readJson(workingDir);
        emit(AgentEvent.todosUpdated(sessionId, todosJson));
    }

    /**
     * Called once per session at end-of-loop, regardless of success/failure. This is now the
     * <strong>sole</strong> terminal event emission point — kairo-core's {@code TerminalHookGuard}
     * guarantees that {@code onSessionEnd} fires exactly once, eliminating the previous dual-emit
     * race between this hook and {@code AgentService.doFinally}. The latter has been reduced to
     * resource cleanup only (slot release, runningState flip).
     */
    @io.kairo.api.hook.HookHandler(HookPhase.SESSION_END)
    public HookResult<SessionEndEvent> onSessionEnd(SessionEndEvent event) {
        try {
            if (event.finalState() == AgentState.FAILED || event.error() != null) {
                emit(AgentEvent.error(
                        sessionId,
                        event.error() != null ? event.error() : "Agent session failed",
                        "AGENT_FAILED"));
            } else {
                emit(AgentEvent.done(sessionId, (int) Math.min(event.tokensUsed(), Integer.MAX_VALUE), 0.0));
            }
        } catch (Exception e) {
            log.warn("SESSION_END bridge failed for session {}: {}", sessionId, e.getMessage());
        }
        return HookResult.proceed(event);
    }

    private void emit(AgentEvent event) {
        // Structured INFO log — `grep "session=<id>"` reproduces the full event timeline without
        // having to scrape WebSocket frames. Keeps the log line bounded by clipping previews.
        log.info(
                "event.emit session={} type={} preview={}",
                sessionId,
                event.type(),
                contentPreview(event));
        if (diagnosticsTracker != null) {
            diagnosticsTracker.record(event.type(), event.timestamp());
        }
        Sinks.EmitResult result = sink.tryEmitNext(event);
        if (result.isFailure()) {
            log.warn("Failed to emit event {} for session {}: {}", event.type(), sessionId, result);
        }
    }

    /** Best-effort short preview of an event for log lines. Always ≤ 80 chars, single line. */
    private static String contentPreview(AgentEvent event) {
        String s = event.toString();
        if (s.length() > 80) {
            s = s.substring(0, 77) + "...";
        }
        return s.replace('\n', ' ').replace('\r', ' ');
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
