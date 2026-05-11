package io.kairo.code.service;

import java.util.Map;

/**
 * Event pushed to the client via any transport (STOMP / SSE / CLI).
 *
 * <p>{@code resultMetadata} carries structured tags for TOOL_RESULT and TOOL_PROGRESS events:
 * for TOOL_RESULT it surfaces {@code failureReason} (TIMEOUT / HANDLER_ERROR / …) so the UI
 * can render a typed chip; for TOOL_PROGRESS it carries {@code phase} + {@code elapsedMs}
 * so the UI can show a live waiting indicator.
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
        Map<String, Object> resultMetadata,
        long timestamp
) {

    public enum EventType {
        AGENT_THINKING,
        TEXT_CHUNK,
        TOOL_CALL,
        TOOL_RESULT,
        TOOL_PROGRESS,
        AGENT_DONE,
        AGENT_ERROR,
        SESSION_RESTORED,
        TODOS_UPDATED,
        CONTEXT_COMPACTED
    }

    public static AgentEvent thinking(String sessionId) {
        return new AgentEvent(EventType.AGENT_THINKING, sessionId, null, null, null,
                false, null, null, null, null, null, null, null, System.currentTimeMillis());
    }

    /** Streaming reasoning_content delta from thinking models (GLM-5.1, o1, Claude thinking). */
    public static AgentEvent thinkingChunk(String sessionId, String text) {
        return new AgentEvent(EventType.AGENT_THINKING, sessionId, text, null, null,
                false, null, null, null, null, null, null, null, System.currentTimeMillis());
    }

    public static AgentEvent textChunk(String sessionId, String text) {
        return new AgentEvent(EventType.TEXT_CHUNK, sessionId, text, null, null,
                false, null, null, null, null, null, null, null, System.currentTimeMillis());
    }

    public static AgentEvent toolCall(String sessionId, String toolName,
                                      Map<String, Object> input, String toolCallId, boolean requiresApproval) {
        return new AgentEvent(EventType.TOOL_CALL, sessionId, null, toolName, input,
                requiresApproval, toolCallId, null, null, null, null, null, null, System.currentTimeMillis());
    }

    public static AgentEvent toolResult(String sessionId, String toolCallId, String result) {
        return toolResult(sessionId, toolCallId, result, null);
    }

    /**
     * Create a TOOL_RESULT event with structured metadata (e.g. {@code failureReason} from
     * {@link io.kairo.api.tool.FailureReason}). The frontend reads {@code resultMetadata} to
     * render distinct chips for timeout / handler-error / interrupted, etc.
     */
    public static AgentEvent toolResult(String sessionId, String toolCallId, String result,
                                         Map<String, Object> resultMetadata) {
        return new AgentEvent(EventType.TOOL_RESULT, sessionId, null, null, null,
                false, toolCallId, result, null, null, null, null, resultMetadata,
                System.currentTimeMillis());
    }

    /**
     * Heartbeat for a long-running tool. Emitted every ~5s once a tool exceeds a threshold
     * (default 30s). {@code resultMetadata} contains {@code phase} (EXECUTING /
     * AWAITING_APPROVAL / STREAMING) and {@code elapsedMs} (long); {@code toolName} echoes
     * the tool's name so the UI can dedupe cards by id without holding extra state.
     */
    public static AgentEvent toolProgress(String sessionId, String toolCallId, String toolName,
                                          String phase, long elapsedMs) {
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("phase", phase);
        meta.put("elapsedMs", elapsedMs);
        return new AgentEvent(EventType.TOOL_PROGRESS, sessionId, null, toolName, null,
                false, toolCallId, null, null, null, null, null, meta,
                System.currentTimeMillis());
    }

    public static AgentEvent done(String sessionId, long tokens, double cost) {
        return new AgentEvent(EventType.AGENT_DONE, sessionId, null, null, null,
                false, null, null, tokens, cost, null, null, null, System.currentTimeMillis());
    }

    public static AgentEvent error(String sessionId, String message, String type) {
        return new AgentEvent(EventType.AGENT_ERROR, sessionId, null, null, null,
                false, null, null, null, null, message, type, null, System.currentTimeMillis());
    }

    /**
     * Create a SESSION_RESTORED event. The {@code content} field holds a JSON object
     * with {messages: [...], running: boolean, todos: [...]}.
     * {@code todosJson} should be a JSON array string (e.g. {@code "[]"} when no todos exist).
     */
    public static AgentEvent sessionRestored(String sessionId, String messagesJson, boolean running, String todosJson) {
        String todos = (todosJson == null || todosJson.isBlank()) ? "[]" : todosJson;
        String payload = "{\"messages\":" + messagesJson
                + ",\"running\":" + running
                + ",\"todos\":" + todos + "}";
        return new AgentEvent(EventType.SESSION_RESTORED, sessionId, payload, null, null,
                false, null, null, null, null, null, null, null, System.currentTimeMillis());
    }

    /**
     * Create a TODOS_UPDATED event. The {@code content} field holds the full todos JSON array
     * snapshot (Claude Code schema: {@code [{id, subject, description?, activeForm?, status}]}).
     */
    public static AgentEvent todosUpdated(String sessionId, String todosJson) {
        return new AgentEvent(EventType.TODOS_UPDATED, sessionId, todosJson, null, null,
                false, null, null, null, null, null, null, null, System.currentTimeMillis());
    }

    /**
     * Create a CONTEXT_COMPACTED event signalling that {@link io.kairo.code.core.hook.ContextCompactionHook}
     * has just injected a compaction request. The {@code content} field holds a JSON object
     * {@code {"beforeTokens":N,"maxTokens":N,"ratio":0.xx}} where {@code ratio = beforeTokens / maxTokens}.
     * The {@code tokenUsage} field also carries {@code beforeTokens} for downstream consumers.
     */
    public static AgentEvent contextCompacted(String sessionId, int beforeTokens, int maxTokens) {
        double ratio = maxTokens > 0 ? Math.min(1.0, (double) beforeTokens / maxTokens) : 0.0;
        String payload = "{\"beforeTokens\":" + beforeTokens
                + ",\"maxTokens\":" + maxTokens
                + ",\"ratio\":" + String.format(java.util.Locale.ROOT, "%.4f", ratio) + "}";
        return new AgentEvent(EventType.CONTEXT_COMPACTED, sessionId, payload, null, null,
                false, null, null, (long) beforeTokens, null, null, null, null,
                System.currentTimeMillis());
    }
}
