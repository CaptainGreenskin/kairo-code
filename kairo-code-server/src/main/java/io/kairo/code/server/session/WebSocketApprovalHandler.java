package io.kairo.code.server.session;

import io.kairo.api.tool.ApprovalResult;
import io.kairo.api.tool.ToolCallRequest;
import io.kairo.api.tool.UserApprovalHandler;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket-based {@link UserApprovalHandler} that sends approval prompts
 * over the WebSocket and waits for client responses.
 *
 * <p>Each pending approval is tracked by toolCallId with a {@link Sinks.One}
 * that the controller completes when the client sends an approve/reject message.
 */
public final class WebSocketApprovalHandler implements UserApprovalHandler {

    private final Map<String, Sinks.One<ApprovalResult>> pendingApprovals = new ConcurrentHashMap<>();
    private final Map<String, ToolCallRequest> pendingRequests = new ConcurrentHashMap<>();

    @Override
    public Mono<ApprovalResult> requestApproval(ToolCallRequest request) {
        String toolCallId = extractToolCallId(request);
        Sinks.One<ApprovalResult> sink = Sinks.one();

        pendingApprovals.put(toolCallId, sink);
        pendingRequests.put(toolCallId, request);

        return sink.asMono()
                .doFinally(signal -> {
                    pendingApprovals.remove(toolCallId);
                    pendingRequests.remove(toolCallId);
                });
    }

    /**
     * Resolve a pending approval with the given result.
     * Called by the controller when the client sends an approve/reject message.
     */
    public boolean resolveApproval(String toolCallId, ApprovalResult result) {
        Sinks.One<ApprovalResult> sink = pendingApprovals.remove(toolCallId);
        if (sink == null) {
            return false;
        }
        pendingRequests.remove(toolCallId);
        sink.tryEmitValue(result);
        return true;
    }

    /**
     * Check if there is a pending approval for the given toolCallId.
     */
    public boolean hasPendingApproval(String toolCallId) {
        return pendingApprovals.containsKey(toolCallId);
    }

    /**
     * Get the pending tool call request for the given toolCallId.
     */
    public ToolCallRequest getPendingRequest(String toolCallId) {
        return pendingRequests.get(toolCallId);
    }

    /**
     * Return all pending request IDs. Visible for testing.
     */
    public Map<String, ToolCallRequest> getPendingRequests() {
        return Map.copyOf(pendingRequests);
    }

    /**
     * Cancel all pending approvals (e.g., when a session is destroyed).
     */
    public void cancelAll() {
        pendingApprovals.forEach((id, sink) ->
                sink.tryEmitValue(ApprovalResult.denied("Session terminated")));
        pendingApprovals.clear();
        pendingRequests.clear();
    }

    private static String extractToolCallId(ToolCallRequest request) {
        // Use the toolCallId from metadata if available, otherwise generate from toolName+args hash
        if (request.args() != null && request.args().containsKey("toolCallId")) {
            return request.args().get("toolCallId").toString();
        }
        // Fallback: generate a unique ID
        return "tc-" + System.nanoTime() + "-" + request.toolName();
    }
}
