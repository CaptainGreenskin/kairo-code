package io.kairo.code.service;

import io.kairo.api.agent.Agent;
import io.kairo.api.exception.AgentInterruptedException;
import io.kairo.api.exception.ModelRateLimitException;
import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.model.ApiErrorType;
import io.kairo.api.model.ApiException;
import io.kairo.api.tool.ApprovalResult;
import io.kairo.api.tracing.Tracer;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentFactory;
import io.kairo.code.core.CodeAgentFactory.SessionOptions;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.code.core.stats.ToolUsageTracker;
import io.kairo.code.core.hook.ContextCompactionHook;
import io.kairo.code.service.concurrency.AgentConcurrencyController;
import io.kairo.code.service.concurrency.AgentConcurrencyException;
import io.kairo.code.service.concurrency.AgentSlot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Transport-agnostic agent service bridge.
 *
 * <p>Manages session lifecycle and exposes a {@link Flux}-based event API
 * that any transport layer (STOMP / SSE / CLI) can subscribe to.
 */
@Component
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final Map<String, Sinks.Many<AgentEvent>> eventSinks = new ConcurrentHashMap<>();
    private final Map<String, SessionEntry> sessions = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> runningState = new ConcurrentHashMap<>();

    @Autowired
    private AgentConcurrencyController concurrencyController;

    @Autowired(required = false)
    private Tracer tracer;

    private volatile CodeAgentConfig defaultConfig;

    /**
     * Hot-update the default config for new sessions.
     * Does not affect running sessions.
     */
    public void updateDefaultConfig(String apiKey, String model, String provider,
                                     String baseUrl, String workingDir, Integer thinkingBudget) {
        String resolvedBaseUrl = resolveBaseUrl(provider, baseUrl);
        this.defaultConfig = new CodeAgentConfig(
                apiKey, resolvedBaseUrl, model, 50, workingDir, null, 0, 0, thinkingBudget
        );
        log.info("Updated default agent config (model={}, provider={})", model, provider);
    }

    private String resolveBaseUrl(String provider, String propsBaseUrl) {
        if (propsBaseUrl != null && !propsBaseUrl.isBlank()) {
            return propsBaseUrl;
        }
        if (provider == null || provider.isBlank()) {
            return propsBaseUrl;
        }
        return switch (provider.toLowerCase()) {
            case "openai" -> "https://api.openai.com";
            case "anthropic" -> "https://api.anthropic.com";
            default -> propsBaseUrl;
        };
    }

    /**
     * Create a new session. Returns the session ID.
     */
    public String createSession(CodeAgentConfig config) {
        String sessionId = UUID.randomUUID().toString();

        log.info("Creating session {} (model={}, workingDir={})",
                sessionId, config.modelName(), config.workingDir());

        Sinks.Many<AgentEvent> sink = Sinks.many().multicast().onBackpressureBuffer();
        eventSinks.put(sessionId, sink);

        AgentEventBridgeHook bridgeHook = new AgentEventBridgeHook(sink, sessionId);
        WebSocketApprovalHandler approvalHandler = new WebSocketApprovalHandler();

        // Register a ContextCompactionHook with a listener that surfaces CONTEXT_COMPACTED
        // events on the session sink. Supplying it via SessionOptions causes CodeAgentFactory
        // to skip its default auto-registration so this listener-equipped instance is the
        // single source of truth for compaction in this session.
        ContextCompactionHook compactionHook = buildCompactionHook(sink, sessionId);

        try {
            SessionOptions opts = SessionOptions.empty()
                    .withApprovalHandler(approvalHandler)
                    .withHooks(List.of(bridgeHook, compactionHook));
            if (tracer != null) {
                opts = opts.withTracer(tracer);
            }
            CodeAgentSession session = CodeAgentFactory.createSession(config, opts);

            SessionEntry entry = new SessionEntry(sessionId, config, session, approvalHandler);
            sessions.put(sessionId, entry);
            runningState.put(sessionId, new AtomicBoolean(false));

            log.info("Session {} created successfully", sessionId);
            return sessionId;
        } catch (Exception e) {
            log.error("Failed to create session {}", sessionId, e);
            sink.tryEmitComplete();
            eventSinks.remove(sessionId);
            throw e;
        }
    }

    /**
     * Send a user message to a session. Returns a Flux of events for this message.
     *
     * <p>Event sequence: AGENT_THINKING → TEXT_CHUNK* → TOOL_CALL* → TOOL_RESULT* → AGENT_DONE
     * or → AGENT_ERROR
     */
    public Flux<AgentEvent> sendMessage(String sessionId, String text) {
        Msg userMsg = Msg.of(MsgRole.USER, text);
        return sendMessage(sessionId, userMsg);
    }

    /**
     * Send a user message with an optional image attachment to a session.
     *
     * @param sessionId     the session ID
     * @param text          the text message
     * @param imageData     base64-encoded image data (nullable)
     * @param imageMediaType MIME type of the image, e.g. "image/png" (nullable)
     */
    public Flux<AgentEvent> sendMessage(String sessionId, String text,
                                         String imageData, String imageMediaType) {
        if (imageData != null && !imageData.isBlank()) {
            byte[] bytes = Base64.getDecoder().decode(imageData);
            Msg userMsg = Msg.builder()
                    .role(MsgRole.USER)
                    .addContent(new Content.TextContent(text))
                    .addContent(new Content.ImageContent(null, imageMediaType, bytes))
                    .build();
            return sendMessage(sessionId, userMsg);
        }
        return sendMessage(sessionId, text);
    }

    private Flux<AgentEvent> sendMessage(String sessionId, Msg userMsg) {
        SessionEntry entry = sessions.get(sessionId);
        if (entry == null) {
            return Flux.just(AgentEvent.error(sessionId,
                    "Session not found: " + sessionId, "SESSION_NOT_FOUND"));
        }

        AtomicBoolean running = runningState.get(sessionId);
        if (running == null || !running.compareAndSet(false, true)) {
            return Flux.just(AgentEvent.error(sessionId,
                    "Session is already running", "SESSION_BUSY"));
        }

        Sinks.Many<AgentEvent> sink = eventSinks.get(sessionId);
        if (sink == null) {
            runningState.put(sessionId, new AtomicBoolean(false));
            return Flux.just(AgentEvent.error(sessionId,
                    "Event sink not found for session: " + sessionId, "INTERNAL_ERROR"));
        }

        Agent agent = entry.session().agent();

        return Flux.<AgentEvent>create(emitter -> {
                    // Emit thinking event
                    emitter.next(AgentEvent.thinking(sessionId));

                    AtomicBoolean completed = new AtomicBoolean(false);

                    AgentSlot slot;
                    try {
                        slot = concurrencyController.acquire(sessionId);
                    } catch (AgentConcurrencyException e) {
                        completed.set(true);
                        emitter.next(AgentEvent.error(sessionId, e.getMessage(), e.reason().name()));
                        emitter.complete();
                        return;
                    }

                    agent.call(userMsg)
                            .subscribeOn(Schedulers.boundedElastic())
                            .doFinally(signal -> {
                                slot.close();
                                runningState.put(sessionId, new AtomicBoolean(false));
                                if (!completed.get()) {
                                    if (signal != reactor.core.publisher.SignalType.ON_ERROR) {
                                        emitter.next(AgentEvent.done(sessionId, 0, 0.0));
                                    }
                                }
                                emitter.complete();
                            })
                            .subscribe(
                                    responseMsg -> {
                                        completed.set(true);
                                        // Token usage not directly available on Msg;
                                        // future enhancement: capture from hook events.
                                        emitter.next(AgentEvent.done(sessionId, 0, 0.0));
                                    },
                                    error -> {
                                        completed.set(true);
                                        String errorMsg = error.getMessage();
                                        String errorType = classifyError(error);
                                        if (error instanceof AgentInterruptedException) {
                                            errorType = "INTERRUPTED";
                                            errorMsg = "Agent execution was interrupted";
                                        }
                                        emitter.next(AgentEvent.error(sessionId, errorMsg, errorType));
                                        emitter.error(error);
                                    }
                            );
                })
                .mergeWith(sink.asFlux());
    }

    /**
     * Approve or reject a pending tool call.
     */
    public boolean approveTool(String sessionId, String toolCallId, boolean approved, String reason) {
        SessionEntry entry = sessions.get(sessionId);
        if (entry == null) {
            return false;
        }

        ApprovalResult result = approved
                ? ApprovalResult.allow()
                : ApprovalResult.denied(reason != null ? reason : "User denied");

        return entry.approvalHandler().resolveApproval(toolCallId, result);
    }

    /**
     * Interrupt the current agent execution.
     */
    public void stopAgent(String sessionId) {
        SessionEntry entry = sessions.get(sessionId);
        if (entry == null) {
            return;
        }

        try {
            entry.session().agent().interrupt();
            runningState.put(sessionId, new AtomicBoolean(false));
            log.info("Session {} stopped", sessionId);
        } catch (Exception e) {
            log.warn("Error stopping session {}", sessionId, e);
        }
    }

    /**
     * Destroy a session and clean up all resources.
     */
    public boolean destroySession(String sessionId) {
        SessionEntry entry = sessions.remove(sessionId);
        if (entry == null) {
            return false;
        }

        log.info("Destroying session {}", sessionId);
        entry.approvalHandler().cancelAll();
        runningState.remove(sessionId);

        Sinks.Many<AgentEvent> sink = eventSinks.remove(sessionId);
        if (sink != null) {
            sink.tryEmitComplete();
        }

        try {
            entry.session().agent().interrupt();
        } catch (Exception e) {
            log.debug("Error interrupting agent for session {}", sessionId, e);
        }
        return true;
    }

    /**
     * Bind to an existing session and return its history for client-side restore.
     * Reads the checkpoint.json file to reconstruct the conversation.
     *
     * @return an AgentEvent of type SESSION_RESTORED with messages as JSON array in content,
     *         or null if session not found.
     */
    public AgentEvent bindSession(String sessionId) {
        SessionEntry entry = sessions.get(sessionId);
        if (entry == null) {
            log.warn("bindSession: session {} not found", sessionId);
            return null;
        }

        boolean running = isRunning(sessionId);
        List<Map<String, Object>> messages = readCheckpointMessages(entry);
        String messagesJson;
        try {
            messagesJson = new ObjectMapper().writeValueAsString(messages);
        } catch (IOException e) {
            log.error("Failed to serialize messages for session {}", sessionId, e);
            messagesJson = "[]";
        }

        log.info("Session {} bound: {} messages, running={}", sessionId, messages.size(), running);
        return AgentEvent.sessionRestored(sessionId, messagesJson, running);
    }

    private List<Map<String, Object>> readCheckpointMessages(SessionEntry entry) {
        Path workingDir = Path.of(entry.config().workingDir());
        Path checkpointPath = workingDir.resolve(".kairo-session").resolve("checkpoint.json");
        if (!Files.exists(checkpointPath)) {
            return List.of();
        }

        try {
            String json = Files.readString(checkpointPath);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);
            JsonNode messagesNode = root.get("messages");
            if (messagesNode == null || !messagesNode.isArray()) {
                return List.of();
            }

            // Convert checkpoint messages to frontend-friendly format.
            // Skip tool-result messages (role=tool) — they're embedded in assistant toolCalls.
            List<Map<String, Object>> result = new java.util.ArrayList<>();
            String currentAssistantId = null;
            Map<String, Object> currentAssistant = null;
            List<Map<String, Object>> currentToolCalls = null;

            for (JsonNode msg : messagesNode) {
                String role = msg.has("role") ? msg.get("role").asText() : "";
                String content = msg.has("content") ? msg.get("content").asText("") : "";

                if ("user".equals(role)) {
                    // Flush any pending assistant message
                    if (currentAssistant != null) {
                        if (currentToolCalls != null && !currentToolCalls.isEmpty()) {
                            currentAssistant.put("toolCalls", currentToolCalls);
                        }
                        result.add(currentAssistant);
                    }
                    currentAssistant = null;
                    currentToolCalls = null;

                    Map<String, Object> userMsg = new java.util.LinkedHashMap<>();
                    userMsg.put("id", java.util.UUID.randomUUID().toString());
                    userMsg.put("role", "user");
                    userMsg.put("content", content);
                    userMsg.put("toolCalls", List.of());
                    userMsg.put("timestamp", System.currentTimeMillis());
                    result.add(userMsg);
                } else if ("assistant".equals(role)) {
                    // Flush previous assistant message
                    if (currentAssistant != null) {
                        if (currentToolCalls != null && !currentToolCalls.isEmpty()) {
                            currentAssistant.put("toolCalls", currentToolCalls);
                        }
                        result.add(currentAssistant);
                    }

                    currentAssistantId = java.util.UUID.randomUUID().toString();
                    currentAssistant = new java.util.LinkedHashMap<>();
                    currentAssistant.put("id", currentAssistantId);
                    currentAssistant.put("role", "assistant");
                    currentAssistant.put("content", content);
                    currentToolCalls = new java.util.ArrayList<>();

                    // Extract toolCalls from the message
                    JsonNode tcNode = msg.get("toolCalls");
                    if (tcNode != null && tcNode.isArray()) {
                        for (JsonNode tc : tcNode) {
                            Map<String, Object> toolCall = new java.util.LinkedHashMap<>();
                            toolCall.put("id", tc.has("id") ? tc.get("id").asText() : "");
                            toolCall.put("toolName", tc.has("name") ? tc.get("name").asText() : "");
                            toolCall.put("input", tc.has("input") ? mapper.convertValue(tc.get("input"), Map.class) : Map.of());
                            toolCall.put("status", "done");
                            toolCall.put("requiresApproval", false);
                            currentToolCalls.add(toolCall);
                        }
                    }
                }
                // Skip "tool" role messages — results are in toolCalls above
            }

            // Flush last assistant message
            if (currentAssistant != null) {
                if (currentToolCalls != null && !currentToolCalls.isEmpty()) {
                    currentAssistant.put("toolCalls", currentToolCalls);
                }
                result.add(currentAssistant);
            }

            // Return last 50 messages to avoid overwhelming the client
            int start = Math.max(0, result.size() - 50);
            return result.subList(start, result.size());
        } catch (IOException e) {
            log.warn("Failed to read checkpoint for session {}: {}", entry.sessionId(), e.getMessage());
            return List.of();
        }
    }

    /**
     * Return tool-usage stats for a session, or null if not found.
     * Each entry maps tool name to a map of {calls, successes, totalMillis, successRate, avgMillis}.
     */
    public Map<String, Map<String, Object>> getSessionToolStats(String sessionId) {
        SessionEntry entry = sessions.get(sessionId);
        if (entry == null) {
            return null;
        }
        ToolUsageTracker tracker = entry.session().toolUsageTracker();
        if (tracker == null) {
            return Map.of();
        }
        return tracker.snapshot().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        e -> {
                            ToolUsageTracker.ToolStat s = e.getValue();
                            return Map.<String, Object>of(
                                    "calls", s.calls(),
                                    "successes", s.successes(),
                                    "totalMillis", s.totalMillis(),
                                    "successRate", s.successRate(),
                                    "avgMillis", s.avgMillis()
                            );
                        }
                ));
    }

    /**
     * Return the configured default working directory, or null if not set.
     */
    public String getDefaultWorkingDir() {
        return defaultConfig != null ? defaultConfig.workingDir() : null;
    }

    /**
     * Return a list of active session summaries.
     */
    public List<SessionInfo> listSessions() {
        return sessions.values().stream()
                .map(e -> new SessionInfo(
                        e.sessionId(),
                        e.config().workingDir(),
                        e.config().modelName(),
                        e.createdAt(),
                        isRunning(e.sessionId())))
                .toList();
    }

    private boolean isRunning(String sessionId) {
        AtomicBoolean state = runningState.get(sessionId);
        return state != null && state.get();
    }

    /**
     * Classify a throwable into a frontend-friendly error type string.
     */
    private static String classifyError(Throwable error) {
        if (error instanceof ModelRateLimitException) {
            return "RATE_LIMITED";
        }
        if (error instanceof ApiException apiEx) {
            ApiErrorType type = apiEx.getErrorType();
            if (type == null) {
                return "PROVIDER_ERROR";
            }
            return switch (type) {
                case AUTHENTICATION_ERROR -> "AUTH_FAILURE";
                case RATE_LIMITED -> "RATE_LIMITED";
                case BUDGET_EXCEEDED -> "QUOTA_EXCEEDED";
                case SERVER_ERROR -> "PROVIDER_ERROR";
                default -> "UNKNOWN";
            };
        }
        return error.getClass().getSimpleName();
    }

    /**
     * Build a {@link ContextCompactionHook} configured with environment-driven defaults plus a
     * listener that emits {@code CONTEXT_COMPACTED} events to the given sink whenever the hook
     * decides to inject a compaction prompt.
     */
    private static ContextCompactionHook buildCompactionHook(
            Sinks.Many<AgentEvent> sink, String sessionId) {
        // Use the public constructor with environment defaults via a temporary throwaway
        // instance to read the configured max-tokens; then replace with a listener-equipped
        // instance that also captures maxTokens for inclusion in the event payload.
        ContextCompactionHook probe = new ContextCompactionHook();
        int maxTokens = probe.maxContextTokens();
        return ContextCompactionHook.withListener(beforeTokens -> {
            Sinks.EmitResult emit = sink.tryEmitNext(
                    AgentEvent.contextCompacted(sessionId, beforeTokens, maxTokens));
            if (emit.isFailure()) {
                log.warn("Failed to emit CONTEXT_COMPACTED for session {}: {}", sessionId, emit);
            }
        });
    }

    /**
     * Holds a session and its associated state.
     */
    public record SessionEntry(
            String sessionId,
            CodeAgentConfig config,
            CodeAgentSession session,
            WebSocketApprovalHandler approvalHandler,
            long createdAt
    ) {
        public SessionEntry(String sessionId, CodeAgentConfig config,
                            CodeAgentSession session, WebSocketApprovalHandler approvalHandler) {
            this(sessionId, config, session, approvalHandler, System.currentTimeMillis());
        }

        public Agent agent() {
            return session.agent();
        }
    }
}
