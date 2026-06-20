package io.kairo.code.service;

import java.util.List;
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
        TOOL_OUTPUT_CHUNK,
        AGENT_DONE,
        AGENT_ERROR,
        SESSION_RESTORED,
        TODOS_UPDATED,
        CONTEXT_COMPACTED,
        /** Emitted when plan is ready for user confirmation (PLAN_PENDING state). */
        PLAN_READY,
        /** Emitted after a successful workspace revert. */
        REVERTED,
        /** Tells frontend to clear execution-phase messages from UI. */
        CLEAR_EXECUTION_MESSAGES,
        /** Emitted when expert-team triage demotes the request to single-agent ReAct. */
        MODE_DEMOTED,
        /** Emitted when a single-agent session auto-escalates to expert-team coordination. */
        MODE_ESCALATED,
        /**
         * Emitted by {@code TeamSessionPayload} when a peer agent in the same team has sent
         * this session a message via {@code MessageBus}. {@code content} carries the body and
         * {@code resultMetadata} carries {@code fromSessionId} and {@code messageId}.
         */
        PEER_MESSAGE,
        /** Emitted when a stopped session is resumed (phase reset from FAILED_* to IDLE). */
        SESSION_RESUMED,
        /** Keepalive signal during long model calls. Frontend updates lastEventAt but renders nothing. */
        HEARTBEAT,
        /** Emitted when skills are auto-discovered and injected from user input. */
        SKILL_ACTIVATED,
        /** Emitted when a user message is queued because the agent is busy. */
        MESSAGE_QUEUED,
        /**
         * Emitted by a child agent (spawned via the task tool) to report its internal progress
         * to the parent session's event stream. Carries the child's tool calls and results so
         * the frontend can render a live view of what the subagent is doing.
         *
         * <p>{@code toolName} holds the child's tool name (e.g. "bash", "read"),
         * {@code content} holds a human-readable summary, and {@code resultMetadata} carries
         * structured fields: {@code taskId}, {@code taskDescription}, {@code childEventType}
         * ("TOOL_CALL" / "TOOL_RESULT" / "TEXT_CHUNK"), {@code childToolName},
         * {@code childIsError}, {@code childElapsedMs}.
         */
        SUBAGENT_EVENT
    }

    public static AgentEvent thinking(String sessionId) {
        return new AgentEvent(EventType.AGENT_THINKING, sessionId, null, null, null,
                false, null, null, null, null, null, null, null, System.currentTimeMillis());
    }

    public static AgentEvent heartbeat(String sessionId) {
        return new AgentEvent(EventType.HEARTBEAT, sessionId, null, null, null,
                false, null, null, null, null, null, null, null, System.currentTimeMillis());
    }

    public static AgentEvent queued(String sessionId, int position) {
        return new AgentEvent(EventType.MESSAGE_QUEUED, sessionId,
                "Message queued (position " + position + ")", null, null,
                false, null, null, null, null, null, null,
                java.util.Map.of("queuePosition", position), System.currentTimeMillis());
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

    /**
     * Streaming output chunk from a tool (typically bash). {@code content} carries the
     * incremental text; {@code toolCallId} links it to the in-flight tool card in the UI.
     */
    public static AgentEvent toolOutputChunk(String sessionId, String toolCallId, String content) {
        return new AgentEvent(EventType.TOOL_OUTPUT_CHUNK, sessionId, content, null, null,
                false, toolCallId, null, null, null, null, null, null, System.currentTimeMillis());
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
     * with {messages: [...], running: boolean, todos: [...], resumable: boolean}.
     * {@code todosJson} should be a JSON array string (e.g. {@code "[]"} when no todos exist).
     * {@code resumable} is true when the bound session is in a resumable (FAILED_*) phase, so the
     * client can restore the "Resume" affordance after a page reload.
     */
    public static AgentEvent sessionRestored(
            String sessionId, String messagesJson, boolean running, String todosJson, boolean resumable) {
        String todos = (todosJson == null || todosJson.isBlank()) ? "[]" : todosJson;
        String payload = "{\"messages\":" + messagesJson
                + ",\"running\":" + running
                + ",\"todos\":" + todos
                + ",\"resumable\":" + resumable + "}";
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
     * Create a CONTEXT_COMPACTED event signalling that context compaction has occurred.
     * The {@code content} field holds a JSON object
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

    /**
     * Create a PLAN_READY event signalling that the plan has been generated and awaits
     * user confirmation. {@code content} holds a JSON object with plan overview.
     *
     * @param sessionId the session ID
     * @param planOverview the plan overview/summary text
     */
    public static AgentEvent planReady(String sessionId, String planOverview) {
        return new AgentEvent(EventType.PLAN_READY, sessionId, planOverview, null, null,
                false, null, null, null, null, null, null, null,
                System.currentTimeMillis());
    }

    /**
     * PLAN_READY variant that carries a {@code teamId} so the frontend Canvas
     * can auto-attach to the running expert team without command-palette interaction.
     * Used by the Experts preset in {@code TeamSessionPayload}.
     */
    public static AgentEvent planReady(String sessionId, String planOverview, String teamId) {
        Map<String, Object> meta = teamId == null ? null : Map.of("teamId", teamId);
        return new AgentEvent(EventType.PLAN_READY, sessionId, planOverview, null, null,
                false, null, null, null, null, null, null, meta,
                System.currentTimeMillis());
    }

    /**
     * PLAN_READY variant that carries full plan metadata (teamId + DAG steps) so the
     * frontend Canvas can populate the DAG immediately without waiting for a separate
     * TEAM_EVENT subscription.
     */
    public static AgentEvent planReady(String sessionId, String planOverview,
                                       Map<String, Object> metadata) {
        return new AgentEvent(EventType.PLAN_READY, sessionId, planOverview, null, null,
                false, null, null, null, null, null, null, metadata,
                System.currentTimeMillis());
    }

    /** Create a REVERTED event signalling that the workspace was successfully reverted. */
    public static AgentEvent reverted(String sessionId) {
        return new AgentEvent(EventType.REVERTED, sessionId, null, null, null,
                false, null, null, null, null, null, null, null,
                System.currentTimeMillis());
    }

    /** Create a CLEAR_EXECUTION_MESSAGES event telling the frontend to clear execution-phase messages. */
    public static AgentEvent clearExecutionMessages(String sessionId) {
        return new AgentEvent(EventType.CLEAR_EXECUTION_MESSAGES, sessionId, null, null, null,
                false, null, null, null, null, null, null, null,
                System.currentTimeMillis());
    }

    /**
     * Create a MODE_DEMOTED event signalling that the expert-team triage has demoted
     * the request to a single-agent ReAct loop.
     *
     * @param sessionId the session ID
     * @param reason human-readable explanation of the demotion
     */
    public static AgentEvent modeDemoted(String sessionId, String reason) {
        return new AgentEvent(EventType.MODE_DEMOTED, sessionId, reason, null, null,
                false, null, null, null, null, null, null, null,
                System.currentTimeMillis());
    }

    public static AgentEvent modeEscalated(String sessionId, String reason) {
        return new AgentEvent(EventType.MODE_ESCALATED, sessionId, reason, null, null,
                false, null, null, null, null, null, null, null,
                System.currentTimeMillis());
    }

    public static AgentEvent subagentEvent(String parentSessionId,
                                              String taskId, String taskDescription,
                                              String childEventType, String childToolName,
                                              boolean childIsError) {
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("taskId", taskId);
        meta.put("taskDescription", taskDescription);
        meta.put("childEventType", childEventType);
        if (childToolName != null) meta.put("childToolName", childToolName);
        meta.put("childIsError", childIsError);
        String summary = childEventType + (childToolName != null ? ": " + childToolName : "");
        return new AgentEvent(EventType.SUBAGENT_EVENT, parentSessionId, summary,
                childToolName, null, false, null, null, null, null, null, null, meta,
                System.currentTimeMillis());
    }

    public static AgentEvent skillActivated(String sessionId, List<String> skillNames,
                                               List<Double> scores) {
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("skills", skillNames);
        meta.put("scores", scores);
        String summary = String.join(", ", skillNames);
        return new AgentEvent(EventType.SKILL_ACTIVATED, sessionId, summary,
                null, null, false, null, null, null, null, null, null, meta,
                System.currentTimeMillis());
    }

    public static AgentEvent sessionResumed(String sessionId) {
        return new AgentEvent(EventType.SESSION_RESUMED, sessionId,
                "Session resumed from interrupted state", null, null,
                false, null, null, null, null, null, null, null,
                System.currentTimeMillis());
    }

    /**
     * Create a PEER_MESSAGE event for a message delivered from another team member's session
     * via {@link io.kairo.api.team.MessageBus}. {@code content} holds the message body;
     * {@code resultMetadata} carries {@code fromSessionId} and {@code messageId} so the UI can
     * render attribution and dedupe re-deliveries.
     */
    public static AgentEvent peerMessage(String sessionId, String fromSessionId,
                                         String content, String messageId) {
        return peerMessage(sessionId, fromSessionId, content, messageId, null, null);
    }

    /**
     * Peer message carrying the originating expert step. {@code stepId}/{@code teamId} let the
     * frontend bind the chat bubble to a live {@code expertTeamStore} step and render a
     * collapsible expert card instead of plain text.
     */
    public static AgentEvent peerMessage(String sessionId, String fromSessionId,
                                         String content, String messageId,
                                         String stepId, String teamId) {
        Map<String, Object> meta = new java.util.HashMap<>();
        meta.put("fromSessionId", fromSessionId == null ? "" : fromSessionId);
        meta.put("messageId", messageId == null ? "" : messageId);
        if (stepId != null) meta.put("stepId", stepId);
        if (teamId != null) meta.put("teamId", teamId);
        return new AgentEvent(EventType.PEER_MESSAGE, sessionId, content, null, null,
                false, null, null, null, null, null, null, meta,
                System.currentTimeMillis());
    }
}
