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
    /** Optional cost tracker for real cost in AGENT_DONE events. Late-bound via setter. */
    private volatile io.kairo.api.cost.CostTracker costTracker;

    /** Tool-use id → tool name, populated on POST_REASONING. POST_ACTING reads this to identify
     *  which tool finished (PostActingEvent already carries toolName, but we also use the map for
     *  TOOL_CALL dedupe between this hook and {@code WebSocketApprovalHandler}). */
    private final Map<String, String> emittedToolCalls = new ConcurrentHashMap<>();

    /** Deduplicates TOOL_RESULT emissions: both POST_ACTING and TOOL_RESULT hooks fire for the
     *  same tool — without this guard the second emission (missing durationMs) overwrites the first. */
    private final Set<String> emittedToolResults = ConcurrentHashMap.newKeySet();

    /**
     * Set of toolCallIds the {@link WebSocketApprovalHandler} has already announced. When a tool
     * required ASK approval, WSAH emits the TOOL_CALL event before execution; if BridgeHook then
     * also emits in POST_REASONING for the same id, the frontend gets duplicate cards. Skip when
     * the id is in this set.
     */
    private final Set<String> announcedToolCallIds;

    public AgentEventBridgeHook(Sinks.Many<AgentEvent> sink, String sessionId) {
        this(sink, sessionId, ConcurrentHashMap.newKeySet(), null, null, null, null);
    }

    public AgentEventBridgeHook(
            Sinks.Many<AgentEvent> sink, String sessionId, Set<String> announcedToolCallIds) {
        this(sink, sessionId, announcedToolCallIds, null, null, null, null);
    }

    public AgentEventBridgeHook(
            Sinks.Many<AgentEvent> sink,
            String sessionId,
            Set<String> announcedToolCallIds,
            String workingDir) {
        this(sink, sessionId, announcedToolCallIds, workingDir, null, null, null);
    }

    public AgentEventBridgeHook(
            Sinks.Many<AgentEvent> sink,
            String sessionId,
            Set<String> announcedToolCallIds,
            String workingDir,
            ToolProgressTracker progressTracker) {
        this(sink, sessionId, announcedToolCallIds, workingDir, progressTracker, null, null);
    }

    public AgentEventBridgeHook(
            Sinks.Many<AgentEvent> sink,
            String sessionId,
            Set<String> announcedToolCallIds,
            String workingDir,
            ToolProgressTracker progressTracker,
            SessionDiagnosticsTracker diagnosticsTracker) {
        this(sink, sessionId, announcedToolCallIds, workingDir, progressTracker,
                diagnosticsTracker, null);
    }

    public AgentEventBridgeHook(
            Sinks.Many<AgentEvent> sink,
            String sessionId,
            Set<String> announcedToolCallIds,
            String workingDir,
            ToolProgressTracker progressTracker,
            SessionDiagnosticsTracker diagnosticsTracker,
            io.kairo.api.cost.CostTracker costTracker) {
        this.sink = sink;
        this.sessionId = sessionId;
        this.announcedToolCallIds = announcedToolCallIds;
        this.workingDir = workingDir;
        this.progressTracker = progressTracker;
        this.diagnosticsTracker = diagnosticsTracker;
        this.costTracker = costTracker;
    }

    /**
     * Late-bind the cost tracker after session creation. The bridge hook is constructed before the
     * session (and its CostTracker) exists, so this setter is called by AgentService after
     * {@code CodeAgentFactory.createSession()} returns.
     */
    public void setCostTracker(io.kairo.api.cost.CostTracker costTracker) {
        this.costTracker = costTracker;
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
                    if (eagerResult != null && emittedToolResults.add(toolUseId)) {
                        Map<String, Object> eagerMeta = enrichWithDuration(null, toolUseId);
                        emit(AgentEvent.toolResult(
                                sessionId, toolUseId, eagerResult.toString(), eagerMeta));
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
            if (!emittedToolResults.add(toolUseId)) {
                return HookResult.proceed(event);
            }
            String resultContent = event.result().content();
            Map<String, Object> meta = enrichWithDuration(event.result().metadata(), toolUseId);
            emit(AgentEvent.toolResult(sessionId, toolUseId, resultContent, meta));
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
            if (!emittedToolResults.add(toolUseId)) {
                return;
            }
            String resultContent = event.result().content();
            Map<String, Object> meta = enrichWithDuration(event.result().metadata(), toolUseId);
            emit(AgentEvent.toolResult(sessionId, toolUseId, resultContent, meta));
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
                failInProgressTodos();
            } else {
                double cost = costTracker != null ? costTracker.summary().estimatedCostUsd() : 0.0;
                emit(AgentEvent.done(sessionId, (int) Math.min(event.tokensUsed(), Integer.MAX_VALUE), cost));
            }
        } catch (Exception e) {
            log.warn("SESSION_END bridge failed for session {}: {}", sessionId, e.getMessage());
        }
        return HookResult.proceed(event);
    }

    private void failInProgressTodos() {
        if (workingDir == null || workingDir.isBlank()) return;
        java.nio.file.Path todoFile =
                java.nio.file.Path.of(workingDir, ".kairo", "todos.json");
        if (!java.nio.file.Files.exists(todoFile)) return;
        try {
            String content = java.nio.file.Files.readString(todoFile);
            String updated = content.replace("\"in_progress\"", "\"failed\"");
            if (!updated.equals(content)) {
                java.nio.file.Files.writeString(todoFile, updated);
                log.info("Marked in_progress todos as failed for session {}", sessionId);
            }
        } catch (Exception e) {
            log.debug("Failed to update todos on session end: {}", e.getMessage());
        }
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
        // Spin-retry on FAIL_NON_SERIALIZED. This hook emits the terminal AGENT_DONE / AGENT_ERROR,
        // and the shared multicast sink is written concurrently (agent reactor, swarm bridge,
        // narrator, heartbeat). A raw tryEmitNext silently drops the terminal under contention,
        // leaving the chat stuck on "Stop" forever — the exact failure AgentRuntimeContext.emit
        // already guards against. Route through the same serialized path.
        Sinks.EmitResult result =
                io.kairo.code.service.agent.AgentRuntimeContext.emitSerialized(sink, event);
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
     * Unregister the tool from the progress tracker and merge {@code durationMs} into the
     * metadata map so the frontend can display actual execution time.
     */
    private Map<String, Object> enrichWithDuration(Map<String, Object> original, String toolUseId) {
        long durationMs = -1;
        if (progressTracker != null) {
            durationMs = progressTracker.unregister(toolUseId);
        }
        if (durationMs < 0) {
            return original;
        }
        Map<String, Object> enriched =
                original != null
                        ? new java.util.LinkedHashMap<>(original)
                        : new java.util.LinkedHashMap<>();
        enriched.put("durationMs", durationMs);
        return enriched;
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
