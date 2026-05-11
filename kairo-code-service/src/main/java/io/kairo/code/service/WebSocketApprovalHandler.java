package io.kairo.code.service;

import io.kairo.api.tool.ApprovalResult;
import io.kairo.api.tool.ToolCallRequest;
import io.kairo.api.tool.UserApprovalHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link UserApprovalHandler} that tracks pending approvals via {@link Sinks.One} and
 * notifies the frontend through the session's event sink.
 *
 * <p>Each pending approval is keyed by a stable toolCallId. When the streaming-path executor
 * reaches a tool whose side-effect is {@code SYSTEM_CHANGE} (e.g. {@code bash}), the chain
 * blocks here until the user approves or denies. Without an outbound notification the frontend
 * has no way to learn that a decision is pending — so we emit a {@code TOOL_CALL} event with
 * {@code requiresApproval=true} as soon as approval is requested.
 */
public final class WebSocketApprovalHandler implements UserApprovalHandler {

    private static final Logger log = LoggerFactory.getLogger(WebSocketApprovalHandler.class);

    private final Map<String, Sinks.One<ApprovalResult>> pendingApprovals = new ConcurrentHashMap<>();
    private final Map<String, ToolCallRequest> pendingRequests = new ConcurrentHashMap<>();
    private final AtomicLong idCounter = new AtomicLong();

    /**
     * Tool-call ids this handler has already emitted a TOOL_CALL event for. Shared with
     * {@link AgentEventBridgeHook} so its POST_REASONING emission can dedupe — without this,
     * the frontend gets two cards per ASK tool (one when approval is requested, one when the
     * synthetic streaming response is post-processed).
     */
    private final Set<String> announcedToolCallIds = ConcurrentHashMap.newKeySet();

    private final Sinks.Many<AgentEvent> eventSink;
    private final String sessionId;
    private volatile ToolProgressTracker progressTracker;

    public WebSocketApprovalHandler() {
        this(null, null);
    }

    public WebSocketApprovalHandler(Sinks.Many<AgentEvent> eventSink, String sessionId) {
        this.eventSink = eventSink;
        this.sessionId = sessionId;
    }

    /** Inject the per-session tracker so requestApproval can flip phase to AWAITING_APPROVAL. */
    public void setProgressTracker(ToolProgressTracker tracker) {
        this.progressTracker = tracker;
    }

    /** Returns the set of toolCallIds WSAH has already announced; used by BridgeHook to dedupe. */
    public Set<String> announcedToolCallIds() {
        return announcedToolCallIds;
    }

    /** Snapshot of currently-pending approvals; the state endpoint surfaces this for ops debugging. */
    public Map<String, ToolCallRequest> pendingApprovalsSnapshot() {
        return Map.copyOf(pendingRequests);
    }

    @Override
    public Mono<ApprovalResult> requestApproval(ToolCallRequest request) {
        String toolCallId = generateToolCallId(request);
        Sinks.One<ApprovalResult> sink = Sinks.one();

        pendingApprovals.put(toolCallId, sink);
        pendingRequests.put(toolCallId, request);

        notifyPendingApproval(request, toolCallId);
        if (progressTracker != null) {
            // Tool already registered as EXECUTING via the TOOL_CALL emit; flip to AWAITING so the
            // heartbeat carries the right phase.
            progressTracker.setPhase(toolCallId, ToolProgressTracker.Phase.AWAITING_APPROVAL);
        }

        // NOTE: do NOT clean up on CANCEL. The upstream Flux can cancel this Mono
        // before the user has a chance to press y, which would orphan the request.
        // resolveApproval() and cancelAll() are the only legitimate removers.
        return sink.asMono()
                .doFinally(signal -> {
                    if (signal != SignalType.CANCEL) {
                        pendingApprovals.remove(toolCallId);
                        pendingRequests.remove(toolCallId);
                    }
                });
    }

    /**
     * Resolve a pending approval with the given result.
     */
    public boolean resolveApproval(String toolCallId, ApprovalResult result) {
        return resolveApproval(toolCallId, result, null);
    }

    /**
     * Resolve a pending approval, optionally mutating the pending request's args map BEFORE the
     * waiting tool resumes. Used by {@code exit_plan_mode} to inject user-edited plan items into
     * the tool's input map: the tool reads {@code items} after {@code requestApproval().block()}
     * returns, so any mutation we do here is observed by the tool.
     *
     * <p>{@code editedArgs} is shallow-merged into the pending request's args map. Pass {@code
     * null} when there are no edits.
     */
    public boolean resolveApproval(
            String toolCallId, ApprovalResult result, Map<String, Object> editedArgs) {
        if (editedArgs != null && !editedArgs.isEmpty()) {
            ToolCallRequest pending = pendingRequests.get(toolCallId);
            if (pending != null && pending.args() != null) {
                try {
                    pending.args().putAll(editedArgs);
                } catch (UnsupportedOperationException e) {
                    log.warn(
                            "Cannot apply edited args for {} — pending request args is immutable",
                            toolCallId);
                }
            }
        }
        Sinks.One<ApprovalResult> sink = pendingApprovals.remove(toolCallId);
        if (sink == null) {
            return false;
        }
        pendingRequests.remove(toolCallId);
        if (progressTracker != null) {
            // Approval came back — tool is going back into the executor pipeline (or being skipped
            // on deny). Either way, it's no longer waiting on a human, so reflect that on the chip.
            progressTracker.setPhase(toolCallId, ToolProgressTracker.Phase.EXECUTING);
        }
        sink.tryEmitValue(result);
        return true;
    }

    /**
     * Cancel all pending approvals (e.g., when a session is destroyed).
     */
    public void cancelAll() {
        pendingApprovals.forEach((id, sink) ->
                sink.tryEmitValue(ApprovalResult.denied("Session terminated")));
        pendingApprovals.clear();
        pendingRequests.clear();
        announcedToolCallIds.clear();
    }

    private void notifyPendingApproval(ToolCallRequest request, String toolCallId) {
        if (eventSink == null || sessionId == null) {
            return;
        }
        Map<String, Object> args = request.args() != null ? request.args() : Map.of();
        AgentEvent event = AgentEvent.toolCall(sessionId, request.toolName(), args, toolCallId, true);
        Sinks.EmitResult emit = eventSink.tryEmitNext(event);
        if (emit.isFailure()) {
            log.warn("Failed to emit pending-approval TOOL_CALL for session {}: {}", sessionId, emit);
            return;
        }
        // Mark as announced so BridgeHook's POST_REASONING handler skips a duplicate emission.
        announcedToolCallIds.add(toolCallId);
    }

    private String generateToolCallId(ToolCallRequest request) {
        // Prefer the model-supplied id so the approval correlates with the TOOL_CALL event
        // emitted by AgentEventBridgeHook (which carries the same id).
        if (request.toolCallId() != null && !request.toolCallId().isBlank()) {
            return request.toolCallId();
        }
        if (request.args() != null) {
            Object existing = request.args().get("toolCallId");
            if (existing != null) {
                return existing.toString();
            }
        }
        return "approval-" + idCounter.incrementAndGet() + "-" + request.toolName();
    }
}
