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
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.util.concurrent.Queues;

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
public class AgentService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final Map<String, Sinks.Many<AgentEvent>> eventSinks = new ConcurrentHashMap<>();
    private final Map<String, SessionEntry> sessions = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> runningState = new ConcurrentHashMap<>();
    private final Map<String, ToolProgressTracker> progressTrackers = new ConcurrentHashMap<>();
    /** Per-session telemetry counters, updated by {@link AgentEventBridgeHook} on each emit
     *  and read by {@code SessionDiagnosticsController}. Lazily created on first read/write. */
    private final Map<String, SessionDiagnosticsTracker> diagnosticsTrackers = new ConcurrentHashMap<>();
    /** Heartbeat tick subscription, started lazily on first session and cancelled on shutdown. */
    private volatile reactor.core.Disposable progressTickSubscription;
    /** Tools older than this emit a heartbeat on every tick; set on the conservative side so
     *  short tools never produce visual noise. */
    private static final long PROGRESS_THRESHOLD_MS = 30_000L;
    /** Heartbeat interval — picked to be cheap on the wire but responsive enough that the user
     *  sees the elapsed counter advance in seconds, not minutes. */
    private static final java.time.Duration PROGRESS_TICK = java.time.Duration.ofSeconds(5);

    @Autowired
    private AgentConcurrencyController concurrencyController;

    @Autowired(required = false)
    private Tracer tracer;

    @Autowired
    private WorktreeManager worktreeManager;

    private volatile CodeAgentConfig defaultConfig;

    /**
     * Hot-update the default config for new sessions.
     * Does not affect running sessions.
     */
    public void updateDefaultConfig(String apiKey, String model, String provider,
                                     String baseUrl, String workingDir, Integer thinkingBudget) {
        String resolvedBaseUrl = resolveBaseUrl(provider, baseUrl);
        this.defaultConfig = new CodeAgentConfig(
                apiKey, resolvedBaseUrl, model, Integer.MAX_VALUE, workingDir, null, 0, 0, thinkingBudget
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
     *
     * <p>Legacy form (no workspace binding). Equivalent to {@code createSession(config, null, false)}.
     */
    public String createSession(CodeAgentConfig config) {
        return createSession(config, null, false);
    }

    /**
     * Create a new session bound to a workspace. If {@code useWorktree} is true and the workspace
     * is a git repo, the session runs in a fresh worktree on branch {@code kairo/<sid8>}; otherwise
     * the session runs directly in {@code config.workingDir()}.
     *
     * @param config       agent config (its {@code workingDir} is the workspace dir)
     * @param workspaceId  owning workspace id (may be null for legacy tests)
     * @param useWorktree  whether to provision a per-session git worktree
     * @return the new session ID
     */
    public String createSession(CodeAgentConfig config, String workspaceId, boolean useWorktree) {
        String sessionId = UUID.randomUUID().toString();

        // Resolve effective cwd via worktree manager. Falls back to workspace dir when not a git
        // repo or when useWorktree is false. Mutate config only if a different cwd was returned —
        // the original CodeAgentConfig is a record, so we rebuild it.
        String effectiveCwd = (worktreeManager != null && useWorktree && config.workingDir() != null)
                ? worktreeManager.acquire(sessionId, config.workingDir(), true)
                : config.workingDir();
        if (effectiveCwd != null && !effectiveCwd.equals(config.workingDir())) {
            config = new CodeAgentConfig(
                    config.apiKey(),
                    config.baseUrl(),
                    config.modelName(),
                    config.maxIterations(),
                    effectiveCwd,
                    config.mcpConfig(),
                    config.toolBudgetForce(),
                    config.repetitiveToolThreshold(),
                    config.thinkingBudget()
            );
        }

        log.info("Creating session {} (workspace={}, model={}, workingDir={}, worktree={})",
                sessionId, workspaceId, config.modelName(), config.workingDir(), useWorktree);

        // autoCancel=false: the sink must survive WS subscriber disconnect. The default
        // (no-arg) variant is `(SMALL_BUFFER_SIZE, true)` which permanently terminates the
        // sink as soon as the lone subscriber drops — and any further emit (via BridgeHook,
        // WSAH, compaction listener) returns FAIL_CANCELLED. We hit this in production: a
        // model-side 401 onError'd the merged Flux, the WS handler unsubscribed, and the
        // rebuilt session (after the user fixed credentials) had a dead sink — every
        // TOOL_CALL/TOOL_RESULT silently dropped, which surfaced as phantom "Error" tool
        // cards on the UI because the frontend safety net flipped unmatched 'approved' cards
        // to error at AGENT_DONE. autoCancel=false keeps the sink valid across reconnects.
        Sinks.Many<AgentEvent> sink = Sinks.many()
                .multicast()
                .onBackpressureBuffer(Queues.SMALL_BUFFER_SIZE, false);
        eventSinks.put(sessionId, sink);

        WebSocketApprovalHandler approvalHandler = new WebSocketApprovalHandler(sink, sessionId);
        ToolProgressTracker progressTracker = new ToolProgressTracker();
        approvalHandler.setProgressTracker(progressTracker);
        progressTrackers.put(sessionId, progressTracker);
        startProgressTickerIfNeeded();
        AgentEventBridgeHook bridgeHook = new AgentEventBridgeHook(
                sink, sessionId, approvalHandler.announcedToolCallIds(),
                config.workingDir(), progressTracker, diagnosticsTrackerFor(sessionId));

        // Register a ContextCompactionHook with a listener that surfaces CONTEXT_COMPACTED
        // events on the session sink. Supplying it via SessionOptions causes CodeAgentFactory
        // to skip its default auto-registration so this listener-equipped instance is the
        // single source of truth for compaction in this session.
        ContextCompactionHook compactionHook = buildCompactionHook(sink, sessionId);

        try {
            // Web sessions are interactive (like a REPL) — skip the one-shot/batch hooks that
            // force tool calls on text-only responses (TextOnlyStallHook, NoWriteDetectedHook,
            // PlanWithoutActionHook, etc.). Without this, simple greetings like "你好" trigger a
            // forced iter 1 that calls SYSTEM_CHANGE tools and hangs at the approval gate.
            SessionOptions opts = SessionOptions.empty()
                    .asReplSession()
                    .withApprovalHandler(approvalHandler)
                    .withHooks(List.of(bridgeHook, compactionHook));
            if (tracer != null) {
                opts = opts.withTracer(tracer);
            }
            CodeAgentSession session = CodeAgentFactory.createSession(config, opts);

            SessionEntry entry = new SessionEntry(sessionId, config, session, approvalHandler, workspaceId);
            sessions.put(sessionId, entry);
            runningState.put(sessionId, new AtomicBoolean(false));

            log.info("Session {} created successfully", sessionId);
            return sessionId;
        } catch (Exception e) {
            log.error("Failed to create session {}", sessionId, e);
            sink.tryEmitComplete();
            eventSinks.remove(sessionId);
            if (worktreeManager != null) {
                worktreeManager.release(sessionId);
            }
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

        // If the user updated credentials via /api/config after this session was created, the
        // session's agent still holds a model provider built with the stale apiKey and will keep
        // returning 401. Rebuild the agent in place so Retry on the auth-failed message works.
        // Conversation context is sacrificed (the model provider has no swap-credentials hook),
        // but the session id, workspace, and UI message history are preserved.
        entry = rebuildIfStale(entry);

        Agent agent = entry.session().agent();

        // All lifecycle events (THINKING / DONE / ERROR) go through `sink` (autoCancel=false)
        // rather than the per-call Flux.create emitter. The emitter dies when the WS subscriber
        // disconnects mid-execution, so any AGENT_DONE emitted onto it after a brief network
        // blip lands nowhere — leaving the UI stuck on "running" forever even though the agent
        // already terminated. Routing through sink lets reconnecting subscribers continue to
        // receive in-flight events, and the doFinally still flips runningState so bindSession
        // reports the correct state on reconnect after completion.
        sink.tryEmitNext(AgentEvent.thinking(sessionId));

        AgentSlot slot;
        try {
            slot = concurrencyController.acquire(sessionId);
        } catch (AgentConcurrencyException e) {
            sink.tryEmitNext(AgentEvent.error(sessionId, e.getMessage(), e.reason().name()));
            return sink.asFlux();
        }

        java.util.function.Consumer<String> thinkingConsumer = delta -> {
            if (delta != null && !delta.isEmpty()) {
                sink.tryEmitNext(AgentEvent.thinkingChunk(sessionId, delta));
            }
        };

        // Single terminal emit point: subscribe captures error/response into refs, doFinally
        // decides exactly one event to emit based on the signal type. Previously both
        // subscribe(onNext) and doFinally could race to emit AGENT_DONE — fixed by routing all
        // terminal emits through doFinally's switch on SignalType.
        java.util.concurrent.atomic.AtomicReference<Throwable> errorRef = new java.util.concurrent.atomic.AtomicReference<>();
        long startedAtMs = System.currentTimeMillis();

        agent.call(userMsg)
                .contextWrite(reactor.util.context.Context.of(
                        io.kairo.core.agent.ReasoningPhase.THINKING_DELTA_KEY,
                        thinkingConsumer))
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(signal -> {
                    slot.close();
                    runningState.put(sessionId, new AtomicBoolean(false));
                    long elapsedMs = System.currentTimeMillis() - startedAtMs;
                    Throwable err = errorRef.get();
                    String terminalKind;
                    if (err != null) {
                        String errorMsg = err.getMessage();
                        String errorType = classifyError(err);
                        if (err instanceof AgentInterruptedException) {
                            errorType = "INTERRUPTED";
                            errorMsg = "Agent execution was interrupted";
                        }
                        sink.tryEmitNext(AgentEvent.error(sessionId, errorMsg, errorType));
                        terminalKind = "error";
                    } else if (signal == reactor.core.publisher.SignalType.CANCEL) {
                        sink.tryEmitNext(AgentEvent.error(sessionId, "Agent execution was cancelled", "CANCELLED"));
                        terminalKind = "cancelled";
                    } else {
                        sink.tryEmitNext(AgentEvent.done(sessionId, 0, 0.0));
                        terminalKind = "done";
                    }
                    log.info("agent.terminal session={} signal={} kind={} elapsedMs={}",
                            sessionId, signal, terminalKind, elapsedMs);
                })
                .subscribe(
                        responseMsg -> {
                            // No-op: terminal emit happens in doFinally. Capture result here only
                            // if we need it for sessionStore in the future.
                        },
                        errorRef::set
                );

        return sink.asFlux();
    }

    /**
     * Approve or reject a pending tool call.
     */
    public boolean approveTool(String sessionId, String toolCallId, boolean approved, String reason) {
        return approveTool(sessionId, toolCallId, approved, reason, null);
    }

    /**
     * Approve or reject a pending tool call, optionally with edited tool args (used by the
     * exit_plan_mode card so users can tweak plan items inline before approving).
     *
     * @param editedArgs shallow-merged into the pending tool's input map before the tool resumes;
     *     pass {@code null} when no edits are needed.
     */
    public boolean approveTool(
            String sessionId,
            String toolCallId,
            boolean approved,
            String reason,
            Map<String, Object> editedArgs) {
        SessionEntry entry = sessions.get(sessionId);
        if (entry == null) {
            return false;
        }

        ApprovalResult result = approved
                ? ApprovalResult.allow()
                : ApprovalResult.denied(reason != null ? reason : "User denied");

        return entry.approvalHandler().resolveApproval(toolCallId, result, editedArgs);
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
            // Unwind any pending approval blocks first (e.g. exit_plan_mode awaiting human
            // review). cancelAll() resolves each pending Sinks.One with denied so the in-flight
            // tool's Mono.block() returns instead of surfacing InterruptedException.
            entry.approvalHandler().cancelAll();
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
        ToolProgressTracker tracker = progressTrackers.remove(sessionId);
        if (tracker != null) {
            tracker.clear();
        }
        diagnosticsTrackers.remove(sessionId);

        Sinks.Many<AgentEvent> sink = eventSinks.remove(sessionId);
        if (sink != null) {
            sink.tryEmitComplete();
        }

        try {
            entry.session().agent().interrupt();
        } catch (Exception e) {
            log.debug("Error interrupting agent for session {}", sessionId, e);
        }
        if (worktreeManager != null) {
            worktreeManager.release(sessionId);
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

        String todosJson = TodoStorage.readJson(entry.config().workingDir());

        log.info("Session {} bound: {} messages, running={}", sessionId, messages.size(), running);
        return AgentEvent.sessionRestored(sessionId, messagesJson, running, todosJson);
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
                        isRunning(e.sessionId()),
                        e.workspaceId()))
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
     * If the session's baked credentials no longer match the current default config (because the
     * user updated /api/config after the session was created), rebuild the agent in place using the
     * latest credentials. Returns the same entry untouched when the credentials still match or when
     * no defaults are known yet. The returned entry is also written back into the {@code sessions}
     * map so subsequent lookups see the fresh agent.
     *
     * <p>workingDir is intentionally NOT compared here: it is workspace-scoped, not config-scoped.
     * defaultConfig.workingDir() holds the global ServerProperties value, which has nothing to do
     * with which workspace the session was created against. An earlier version included workingDir
     * in the staleness check and clobbered cnarch-sre-ai sessions back to the global default.
     */
    private SessionEntry rebuildIfStale(SessionEntry entry) {
        CodeAgentConfig defaults = this.defaultConfig;
        if (defaults == null) {
            return entry;
        }
        CodeAgentConfig current = entry.config();
        boolean stale = !java.util.Objects.equals(current.apiKey(), defaults.apiKey())
                || !java.util.Objects.equals(current.baseUrl(), defaults.baseUrl())
                || !java.util.Objects.equals(current.modelName(), defaults.modelName());
        if (!stale) {
            return entry;
        }

        log.info(
                "Rebuilding session {} after credential update (model {} → {}, baseUrl {} → {})",
                entry.sessionId(),
                current.modelName(),
                defaults.modelName(),
                current.baseUrl(),
                defaults.baseUrl());

        CodeAgentConfig rebuilt = new CodeAgentConfig(
                defaults.apiKey(),
                defaults.baseUrl(),
                defaults.modelName(),
                current.maxIterations(),
                current.workingDir(),
                current.mcpConfig(),
                current.toolBudgetForce(),
                current.repetitiveToolThreshold(),
                defaults.thinkingBudget());

        Sinks.Many<AgentEvent> sink = eventSinks.get(entry.sessionId());
        WebSocketApprovalHandler approvalHandler = entry.approvalHandler();
        AgentEventBridgeHook bridgeHook = new AgentEventBridgeHook(
                sink, entry.sessionId(), approvalHandler.announcedToolCallIds(), rebuilt.workingDir(),
                progressTrackers.get(entry.sessionId()), diagnosticsTrackerFor(entry.sessionId()));
        ContextCompactionHook compactionHook = buildCompactionHook(sink, entry.sessionId());

        SessionOptions opts = SessionOptions.empty()
                .asReplSession()
                .withApprovalHandler(approvalHandler)
                .withHooks(List.of(bridgeHook, compactionHook));
        if (tracer != null) {
            opts = opts.withTracer(tracer);
        }

        try {
            CodeAgentSession newSession = CodeAgentFactory.createSession(rebuilt, opts);
            SessionEntry newEntry = new SessionEntry(
                    entry.sessionId(),
                    rebuilt,
                    newSession,
                    approvalHandler,
                    entry.workspaceId());
            sessions.put(entry.sessionId(), newEntry);
            return newEntry;
        } catch (Exception e) {
            log.error("Failed to rebuild session {} with new credentials", entry.sessionId(), e);
            return entry;
        }
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
     * Lazily start the heartbeat scheduler. The first session creates it; the ticker stays alive
     * for the JVM lifetime (very cheap when no trackers have stale entries — the inner loop is
     * a single concurrent-map iteration over each session).
     */
    private synchronized void startProgressTickerIfNeeded() {
        if (progressTickSubscription != null && !progressTickSubscription.isDisposed()) {
            return;
        }
        progressTickSubscription =
                Flux.interval(PROGRESS_TICK, Schedulers.parallel())
                        .doOnNext(tick -> emitProgressHeartbeats())
                        .onErrorContinue(
                                (err, o) ->
                                        log.warn("TOOL_PROGRESS tick failed: {}", err.toString()))
                        .subscribe();
    }

    /** One pass over every session's tracker, emitting TOOL_PROGRESS for any stale tools. */
    private void emitProgressHeartbeats() {
        progressTrackers.forEach(
                (sessionId, tracker) -> {
                    if (tracker.isEmpty()) return;
                    Sinks.Many<AgentEvent> sink = eventSinks.get(sessionId);
                    if (sink == null) return;
                    tracker.snapshotIfStale(
                            PROGRESS_THRESHOLD_MS,
                            inflight -> {
                                AgentEvent event =
                                        AgentEvent.toolProgress(
                                                sessionId,
                                                inflight.toolCallId(),
                                                inflight.toolName(),
                                                inflight.phase().name(),
                                                inflight.elapsedMs());
                                Sinks.EmitResult emit = sink.tryEmitNext(event);
                                if (emit.isFailure()) {
                                    log.debug(
                                            "Skipped TOOL_PROGRESS for {} ({}): {}",
                                            sessionId,
                                            inflight.toolCallId(),
                                            emit);
                                }
                            });
                });
    }

    @Override
    public void destroy() {
        if (progressTickSubscription != null) {
            progressTickSubscription.dispose();
            progressTickSubscription = null;
        }
    }

    /**
     * Live snapshot of a session's runtime state for ops/debug surfaces.
     *
     * <p>This is intentionally derived state — the source of truth lives in the per-session sink,
     * approval handler, and progress tracker. The endpoint reads it on demand so dashboards and
     * the developer drawer don't need to subscribe to events.
     *
     * @param sessionId the session ID
     * @param exists    false when the session has been destroyed (other fields are empty)
     * @param running   true while a {@code sendMessage} flow is active
     * @param pendingApprovals one entry per tool blocked on user approval
     * @param runningTools     every tool currently in flight (started, no result yet)
     */
    public record SessionState(
            String sessionId,
            boolean exists,
            boolean running,
            List<PendingApproval> pendingApprovals,
            List<RunningTool> runningTools) {}

    public record PendingApproval(String toolCallId, String toolName, Map<String, Object> args) {}

    public record RunningTool(String toolCallId, String toolName, String phase, long elapsedMs) {}

    /**
     * Build a {@link SessionState} snapshot, suitable for serialisation by the state controller.
     * Returns a state with {@code exists=false} when {@code sessionId} is not in the live map.
     */
    public SessionState getSessionState(String sessionId) {
        SessionEntry entry = sessions.get(sessionId);
        if (entry == null) {
            return new SessionState(sessionId, false, false, List.of(), List.of());
        }
        boolean running = isRunning(sessionId);
        var approvalSnapshot = entry.approvalHandler().pendingApprovalsSnapshot();
        List<PendingApproval> pending = new java.util.ArrayList<>(approvalSnapshot.size());
        approvalSnapshot.forEach(
                (id, req) ->
                        pending.add(
                                new PendingApproval(
                                        id,
                                        req.toolName(),
                                        req.args() != null ? req.args() : Map.of())));

        ToolProgressTracker tracker = progressTrackers.get(sessionId);
        List<RunningTool> running_ = new java.util.ArrayList<>();
        if (tracker != null) {
            // threshold=0 → emit every entry, regardless of age, so the snapshot reflects
            // everything currently in flight (not just the heartbeat-eligible subset).
            tracker.snapshotIfStale(
                    0L,
                    inflight ->
                            running_.add(
                                    new RunningTool(
                                            inflight.toolCallId(),
                                            inflight.toolName(),
                                            inflight.phase().name(),
                                            inflight.elapsedMs())));
        }
        return new SessionState(sessionId, true, running, pending, running_);
    }

    /**
     * Build a {@link SessionDiagnostics} snapshot for the given session, or null when unknown.
     * Reads from the live tracker maintained by {@link AgentEventBridgeHook}.
     */
    public SessionDiagnostics getSessionDiagnostics(String sessionId) {
        SessionEntry entry = sessions.get(sessionId);
        if (entry == null) {
            return null;
        }
        SessionDiagnosticsTracker tracker = diagnosticsTrackers.get(sessionId);
        long lastEventAt = tracker != null ? tracker.lastEventAt() : 0L;
        long now = System.currentTimeMillis();
        long msSince = lastEventAt > 0 ? Math.max(0, now - lastEventAt) : Long.MAX_VALUE;
        Map<String, Long> counts = tracker != null ? tracker.snapshotCounts() : Map.of();
        Sinks.Many<AgentEvent> sink = eventSinks.get(sessionId);
        int wsClients = sink != null ? sink.currentSubscriberCount() : 0;
        return new SessionDiagnostics(
                sessionId, isRunning(sessionId), lastEventAt, msSince, counts, wsClients);
    }

    /**
     * Get-or-create the diagnostics tracker for a session. Used by {@link AgentEventBridgeHook}
     * via the wiring layer so each emitted event can update its counters.
     */
    public SessionDiagnosticsTracker diagnosticsTrackerFor(String sessionId) {
        return diagnosticsTrackers.computeIfAbsent(sessionId, k -> new SessionDiagnosticsTracker());
    }

    /**
     * Holds a session and its associated state.
     */
    public record SessionEntry(
            String sessionId,
            CodeAgentConfig config,
            CodeAgentSession session,
            WebSocketApprovalHandler approvalHandler,
            long createdAt,
            String workspaceId
    ) {
        public SessionEntry(String sessionId, CodeAgentConfig config,
                            CodeAgentSession session, WebSocketApprovalHandler approvalHandler) {
            this(sessionId, config, session, approvalHandler, System.currentTimeMillis(), null);
        }

        public SessionEntry(String sessionId, CodeAgentConfig config,
                            CodeAgentSession session, WebSocketApprovalHandler approvalHandler,
                            String workspaceId) {
            this(sessionId, config, session, approvalHandler, System.currentTimeMillis(), workspaceId);
        }

        public Agent agent() {
            return session.agent();
        }
    }
}
