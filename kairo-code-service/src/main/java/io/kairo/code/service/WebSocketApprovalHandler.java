package io.kairo.code.service;

import io.kairo.api.tool.ApprovalResult;
import io.kairo.api.tool.ToolCallRequest;
import io.kairo.api.tool.UserApprovalHandler;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link UserApprovalHandler} that tracks pending approvals via {@link Sinks.One}.
 *
 * <p>Each pending approval is keyed by toolCallId. The service layer calls
 * {@link #resolveApproval} when the client sends an approve/reject decision.
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
     * Cancel all pending approvals (e.g., when a session is destroyed).
     */
    public void cancelAll() {
        pendingApprovals.forEach((id, sink) ->
                sink.tryEmitValue(ApprovalResult.denied("Session terminated")));
        pendingApprovals.clear();
        pendingRequests.clear();
    }

    private static String extractToolCallId(ToolCallRequest request) {
        if (request.args() != null && request.args().containsKey("toolCallId")) {
            return request.args().get("toolCallId").toString();
        }
        return "tc-" + System.nanoTime() + "-" + request.toolName();
    }
}
