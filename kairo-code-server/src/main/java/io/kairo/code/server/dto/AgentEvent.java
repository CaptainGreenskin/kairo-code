package io.kairo.code.server.dto;

import java.util.Map;

/**
 * Event pushed to the client via STOMP topic `/topic/session/{sessionId}`.
 */
public record AgentEvent(
        EventType type,
        String sessionId,
        String content,
        String toolName,
        Map<String, Object> toolInput,
        boolean requiresApproval,
        String toolCallId,
        String toolResult,
        Long tokenUsage,
        Double cost,
        String errorMessage,
        String errorType,
        long timestamp
) {

    public enum EventType {
        AGENT_THINKING,
        TEXT_CHUNK,
        TOOL_CALL,
        TOOL_RESULT,
        AGENT_DONE,
        AGENT_ERROR
    }

    public static AgentEvent thinking(String sessionId) {
        return new AgentEvent(EventType.AGENT_THINKING, sessionId, null, null, null,
                false, null, null, null, null, null, null, System.currentTimeMillis());
    }

    public static AgentEvent textChunk(String sessionId, String text) {
        return new AgentEvent(EventType.TEXT_CHUNK, sessionId, text, null, null,
                false, null, null, null, null, null, null, System.currentTimeMillis());
    }

    public static AgentEvent toolCall(String sessionId, String toolName,
                                      Map<String, Object> input, String toolCallId, boolean requiresApproval) {
        return new AgentEvent(EventType.TOOL_CALL, sessionId, null, toolName, input,
                requiresApproval, toolCallId, null, null, null, null, null, System.currentTimeMillis());
    }

    public static AgentEvent toolResult(String sessionId, String toolCallId, String result) {
        return new AgentEvent(EventType.TOOL_RESULT, sessionId, null, null, null,
                false, toolCallId, result, null, null, null, null, System.currentTimeMillis());
    }

    public static AgentEvent done(String sessionId, long tokens, double cost) {
        return new AgentEvent(EventType.AGENT_DONE, sessionId, null, null, null,
                false, null, null, tokens, cost, null, null, System.currentTimeMillis());
    }

    public static AgentEvent error(String sessionId, String message, String type) {
        return new AgentEvent(EventType.AGENT_ERROR, sessionId, null, null, null,
                false, null, null, null, null, message, type, System.currentTimeMillis());
    }
}
