package io.kairo.code.service;

import java.util.List;
import java.util.Map;

/**
 * Event pushed to the client via any transport (STOMP / SSE / CLI).
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
        AGENT_ERROR,
        SESSION_RESTORED,
        PLAN_STEPS,
        PLAN_STEP_DONE
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

    /**
     * Create a SESSION_RESTORED event. The {@code content} field holds a JSON object
     * with {messages: [...], running: boolean}.
     */
    public static AgentEvent sessionRestored(String sessionId, String messagesJson, boolean running) {
        String payload = "{\"messages\":" + messagesJson + ",\"running\":" + running + "}";
        return new AgentEvent(EventType.SESSION_RESTORED, sessionId, payload, null, null,
                false, null, null, null, null, null, null, System.currentTimeMillis());
    }

    /**
     * Create a PLAN_STEPS event. The {@code content} field holds a JSON array of step strings.
     */
    public static AgentEvent planSteps(String sessionId, List<String> steps) {
        String json = steps.stream()
                .map(s -> "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        return new AgentEvent(EventType.PLAN_STEPS, sessionId, json, null, null,
                false, null, null, null, null, null, null, System.currentTimeMillis());
    }

    /**
     * Create a PLAN_STEP_DONE event. The {@code content} field holds the step index.
     */
    public static AgentEvent planStepDone(String sessionId, int stepIndex) {
        return new AgentEvent(EventType.PLAN_STEP_DONE, sessionId, String.valueOf(stepIndex), null, null,
                false, null, null, null, null, null, null, System.currentTimeMillis());
    }
}
