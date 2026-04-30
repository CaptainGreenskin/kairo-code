package io.kairo.code.server.dto;

/**
 * Request to approve or reject a tool call.
 */
public record ToolApprovalRequest(
        String sessionId,
        String toolCallId,
        boolean approved,
        String reason
) {}
