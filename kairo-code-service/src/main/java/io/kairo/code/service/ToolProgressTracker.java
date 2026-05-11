package io.kairo.code.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Tracks in-flight tool executions for one session so the service can emit periodic
 * {@code TOOL_PROGRESS} heartbeats when a tool exceeds the heartbeat threshold.
 *
 * <p>The original failure mode this guards against: when {@code exit_plan_mode} (or any
 * approval-blocking tool) waited several minutes for a human, the UI had no way to tell
 * "still alive, awaiting input" from "stuck". A 5s heartbeat with a phase tag (EXECUTING /
 * AWAITING_APPROVAL / STREAMING) lets the card show a live elapsed counter.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>{@link AgentEventBridgeHook} calls {@link #register} when it emits a {@code TOOL_CALL}
 *       and {@link #unregister} when the matching {@code TOOL_RESULT} arrives.
 *   <li>{@link WebSocketApprovalHandler} flips phase to {@code AWAITING_APPROVAL} on
 *       {@code requestApproval} and back to {@code EXECUTING} on {@code resolveApproval} /
 *       {@code cancelAll}.
 *   <li>{@link AgentService} runs a single fleet-wide ticker that calls {@link #snapshotIfStale}
 *       to drive the heartbeat emission.
 * </ul>
 *
 * <p>This class is thread-safe; all mutations go through {@link ConcurrentHashMap}.
 */
public final class ToolProgressTracker {

    /** Tool execution phase, mirrored as {@code resultMetadata.phase} on TOOL_PROGRESS events. */
    public enum Phase {
        EXECUTING,
        AWAITING_APPROVAL,
        STREAMING
    }

    /** Snapshot of one in-flight tool, suitable for emission as a heartbeat. */
    public record InflightTool(String toolCallId, String toolName, Phase phase, long elapsedMs) {}

    private final Map<String, Entry> inflight = new ConcurrentHashMap<>();

    /** Record a tool as in flight starting now in the {@link Phase#EXECUTING} phase. */
    public void register(String toolCallId, String toolName) {
        if (toolCallId == null) return;
        inflight.put(toolCallId, new Entry(toolName, System.currentTimeMillis(), Phase.EXECUTING));
    }

    /** Update the phase for an already-registered tool. No-op if the call is not tracked. */
    public void setPhase(String toolCallId, Phase phase) {
        if (toolCallId == null || phase == null) return;
        inflight.computeIfPresent(toolCallId, (id, e) -> new Entry(e.toolName, e.startMs, phase));
    }

    /** Remove a tool from tracking, e.g. on {@code TOOL_RESULT}. */
    public void unregister(String toolCallId) {
        if (toolCallId == null) return;
        inflight.remove(toolCallId);
    }

    /** Drop all entries (called from {@code cancelAll} / session destroy paths). */
    public void clear() {
        inflight.clear();
    }

    /**
     * Iterate every in-flight tool whose elapsed time exceeds {@code thresholdMs}. The consumer
     * receives an {@link InflightTool} snapshot the caller can convert into a TOOL_PROGRESS event.
     */
    public void snapshotIfStale(long thresholdMs, Consumer<InflightTool> consumer) {
        long now = System.currentTimeMillis();
        inflight.forEach(
                (callId, entry) -> {
                    long elapsed = now - entry.startMs;
                    if (elapsed >= thresholdMs) {
                        consumer.accept(
                                new InflightTool(callId, entry.toolName, entry.phase, elapsed));
                    }
                });
    }

    /** True when nothing is currently being tracked — callers can skip the tick. */
    public boolean isEmpty() {
        return inflight.isEmpty();
    }

    private record Entry(String toolName, long startMs, Phase phase) {}
}
