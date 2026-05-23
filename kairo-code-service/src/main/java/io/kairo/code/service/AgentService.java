package io.kairo.code.service;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentDiagnostics;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.tool.ApprovalResult;
import io.kairo.api.tracing.Tracer;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentFactory;
import io.kairo.code.core.CodeAgentFactory.SessionOptions;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.code.core.stats.ToolUsageTracker;
import io.kairo.code.core.hook.ContextCompactionHook;
import io.kairo.code.service.agent.AgentRuntimeContext;
import io.kairo.code.service.agent.AgentSessionPayload;
import io.kairo.code.service.agent.MessageRequest;
import io.kairo.code.service.agent.SessionPayload;
import io.kairo.code.service.agent.TeamSessionPayload;
import io.kairo.code.service.concurrency.AgentConcurrencyController;
import io.kairo.code.service.team.TriageGate;
import io.kairo.code.core.team.SwarmCoordinator;
import io.kairo.code.service.concurrency.AgentConcurrencyException;
import io.kairo.code.service.concurrency.AgentSlot;
import io.kairo.code.service.workspace.WorkspaceSnapshotService;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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

    // ── Plan-pending state machine ──────────────────────────────────────────────
    /** Per-session lifecycle phase. */
    private final Map<String, AtomicReference<SessionPhase>> sessionPhases = new ConcurrentHashMap<>();
    /** Per-session plan overview captured by the intercept hook. */
    private final Map<String, String> planOverviews = new ConcurrentHashMap<>();
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

    @Autowired(required = false)
    private SwarmCoordinator swarmCoordinator;

    @Autowired(required = false)
    private TriageGate triageGate;

    @Autowired
    private WorktreeManager worktreeManager;

    @Autowired
    private WorkspaceSnapshotService workspaceSnapshotService;

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
        // Single source of truth — was duplicating ProviderRegistry's switch and
        // silently dropping glm / qianwen on the floor (they'd hit propsBaseUrl
        // null and end up calling whatever default the model client picked).
        return io.kairo.code.core.config.ProviderRegistry.resolveBaseUrl(provider);
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
     * Create a new session bound to a workspace (defaults to "agent" mode).
     */
    public String createSession(CodeAgentConfig config, String workspaceId, boolean useWorktree) {
        return createSession(config, workspaceId, useWorktree, "agent");
    }

    /**
     * Create a new session bound to a workspace with explicit session mode.
     * If {@code useWorktree} is true and the workspace is a git repo, the session runs in a
     * fresh worktree on branch {@code kairo/<sid8>}; otherwise the session runs directly in
     * {@code config.workingDir()}.
     *
     * @param config       agent config (its {@code workingDir} is the workspace dir)
     * @param workspaceId  owning workspace id (may be null for legacy tests)
     * @param useWorktree  whether to provision a per-session git worktree
     * @param sessionMode  "agent" for single-agent, "experts" for expert-team
     * @return the new session ID
     */
    public String createSession(CodeAgentConfig config, String workspaceId, boolean useWorktree,
                                String sessionMode) {
        // v2.3 mode normalization: "chat" → "agent"
        String normalizedMode = (sessionMode == null || sessionMode.isBlank()) ? "agent" : sessionMode;
        if ("chat".equals(normalizedMode)) {
            normalizedMode = "agent";
        }

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

        log.info("Creating session {} (workspace={}, model={}, workingDir={}, worktree={}, mode={})",
                sessionId, workspaceId, config.modelName(), config.workingDir(), useWorktree, normalizedMode);

        // autoCancel=false: the sink must survive WS subscriber disconnect.
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

        ContextCompactionHook compactionHook = buildCompactionHook(sink, sessionId);

        // ── Build AgentRuntimeContext ───────────────────────────────────────────────
        AtomicBoolean running = new AtomicBoolean(false);
        runningState.put(sessionId, running);

        AtomicReference<SessionPhase> phaseRef = new AtomicReference<>(SessionPhase.IDLE);
        sessionPhases.put(sessionId, phaseRef);

        String finalWorkingDir = config.workingDir();
        Consumer<SessionPhase> persistPhaseFn = "experts".equals(normalizedMode)
                ? p -> persistPhase(finalWorkingDir, p)
                : p -> {};

        AgentRuntimeContext ctx = new AgentRuntimeContext(
                sessionId, sink, running, phaseRef, persistPhaseFn, concurrencyController);

        // ── Plan-pending intercept hook ────────────────────────────────────────────
        PlanPendingInterceptHook planHook = new PlanPendingInterceptHook(
                sink, sessionId, finalWorkingDir, ctx.phaseRef(),
                overview -> planOverviews.put(sessionId, overview),
                () -> persistPhase(finalWorkingDir, SessionPhase.PLAN_PENDING));

        // Crash recovery: if a previous session left behind a persisted phase
        // (e.g. EXECUTING/PLANNING), detect and transition to FAILED_EXECUTION.
        recoverSessionPhase(config.workingDir(), ctx.phaseRef());

        try {
            // Web sessions are interactive (like a REPL) — skip the one-shot/batch hooks that
            // force tool calls on text-only responses.
            SessionOptions opts = SessionOptions.empty()
                    .asReplSession()
                    .withApprovalHandler(approvalHandler)
                    .withHooks(List.of(bridgeHook, compactionHook, planHook));
            if (tracer != null) {
                opts = opts.withTracer(tracer);
            }
            CodeAgentSession session = CodeAgentFactory.createSession(config, opts);

            // ── Payload creation via switch expression ───────────────────────────────
            SessionPayload payload = switch (normalizedMode) {
                case "experts" -> {
                    if (swarmCoordinator == null || triageGate == null) {
                        throw new IllegalStateException(
                                "experts mode unavailable: SwarmCoordinator not configured");
                    }
                    AgentSessionPayload fb = new AgentSessionPayload(config, session, ctx);
                    TeamSessionPayload teamPayload = new TeamSessionPayload(
                            swarmCoordinator, io.kairo.api.team.TeamConfig.defaults(),
                            sessionId, triageGate, ctx, fb);
                    yield teamPayload;
                }
                case "agent" -> new AgentSessionPayload(config, session, ctx);
                default -> throw new IllegalArgumentException(
                        "Unknown sessionMode: " + normalizedMode);
            };

            SessionEntry entry = new SessionEntry(
                    sessionId, workspaceId, normalizedMode, payload,
                    approvalHandler, System.currentTimeMillis());
            sessions.put(sessionId, entry);

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
        return sendMessage(sessionId, MessageRequest.text(text));
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
            return sendMessage(sessionId, new MessageRequest(text, imageData, imageMediaType));
        }
        return sendMessage(sessionId, text);
    }

    private Flux<AgentEvent> sendMessage(String sessionId, MessageRequest request) {
        SessionEntry entry = sessions.get(sessionId);
        if (entry == null) {
            return Flux.just(AgentEvent.error(sessionId,
                    "Session not found: " + sessionId, "SESSION_NOT_FOUND"));
        }
        entry = rebuildIfStale(entry);
        return entry.payload().handleMessage(request);
    }

    // ── Confirm build (PLAN_PENDING → EXECUTING) ────────────────────────────────

    /**
     * Confirm the plan and begin execution. Only transitions from PLAN_PENDING to EXECUTING.
     * This is the ONLY way to start execution — no keyword detection, only explicit action.
     *
     * @param sessionId the session ID
     * @return true if confirmation accepted, false if session not in PLAN_PENDING
     */
    // TODO(M-team-lifecycle): confirmBuild still manages its own lifecycle (running CAS, slot, agent.call)
    // instead of delegating through SessionPayload.handleMessage. This is the last lifecycle path that
    // bypasses the payload. Should be extracted into a dedicated payload method in M-team-lifecycle.
    public boolean confirmBuild(String sessionId) {
        AtomicReference<SessionPhase> phaseRef = sessionPhases.get(sessionId);
        if (phaseRef == null || !phaseRef.compareAndSet(SessionPhase.PLAN_PENDING, SessionPhase.EXECUTING)) {
            log.warn("confirmBuild rejected for session {} — not in PLAN_PENDING (phase={})",
                    sessionId, phaseRef != null ? phaseRef.get() : "null");
            return false;
        }

        SessionEntry entry = sessions.get(sessionId);
        if (entry == null) {
            phaseRef.set(SessionPhase.PLAN_PENDING); // rollback
            return false;
        }

        // Safe config/workingDir resolution (works for both agent and experts mode)
        CodeAgentConfig cfg = entry.configOrNull();
        if (cfg == null || cfg.workingDir() == null) {
            log.warn("confirmBuild: no config/workingDir available for session {} — rolling back", sessionId);
            phaseRef.set(SessionPhase.PLAN_PENDING);
            return false;
        }
        String workingDir = cfg.workingDir();

        log.info("Plan confirmed for session {} — transitioning to EXECUTING", sessionId);
        persistPhase(workingDir, SessionPhase.EXECUTING);

        // Create workspace snapshot before execution begins
        if (workspaceSnapshotService.isGitWorkspace(workingDir)) {
            workspaceSnapshotService.createSnapshot(workingDir);
        }

        // Acquire running state
        AtomicBoolean running = runningState.get(sessionId);
        if (running == null || !running.compareAndSet(false, true)) {
            log.error("confirmBuild: session {} already running — inconsistent state", sessionId);
            return false;
        }

        Sinks.Many<AgentEvent> sink = eventSinks.get(sessionId);
        if (sink == null) {
            running.set(false);
            phaseRef.set(SessionPhase.PLAN_PENDING);
            return false;
        }

        sink.tryEmitNext(AgentEvent.thinking(sessionId));

        // Send confirmation message to the agent so it resumes execution.
        // The agent is still in plan mode (exit_plan_mode was skipped); send a synthetic
        // user message that tells it the plan was approved and it should now execute.
        Msg confirmMsg = Msg.of(MsgRole.USER,
                "Plan confirmed by user. Proceed with execution now. "
                        + "Call exit_plan_mode if still in plan mode, then execute the plan.");

        // Get agent safely from payload (supports both agent and experts mode)
        Agent agent;
        if (entry.payload() instanceof AgentSessionPayload asp) {
            agent = asp.session().agent();
        } else if (entry.payload() instanceof TeamSessionPayload tsp) {
            agent = tsp.fallback().session().agent();
        } else {
            log.warn("confirmBuild: unknown payload type for session {}", sessionId);
            running.set(false);
            phaseRef.set(SessionPhase.PLAN_PENDING);
            return false;
        }
        AgentSlot slot;
        try {
            slot = concurrencyController.acquire(sessionId);
        } catch (AgentConcurrencyException e) {
            sink.tryEmitNext(AgentEvent.error(sessionId, e.getMessage(), e.reason().name()));
            running.set(false);
            phaseRef.set(SessionPhase.PLAN_PENDING);
            return false;
        }

        long startedAtMs = System.currentTimeMillis();

        agent.call(confirmMsg)
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(signal -> {
                    slot.close();
                    running.set(false);
                    long elapsedMs = System.currentTimeMillis() - startedAtMs;
                    log.info("agent.terminal(confirmBuild) session={} signal={} elapsedMs={}",
                            sessionId, signal, elapsedMs);
                    // On successful completion: drop snapshot and clean up
                    if (phaseRef.compareAndSet(SessionPhase.EXECUTING, SessionPhase.COMPLETED)) {
                        persistPhase(workingDir, SessionPhase.COMPLETED);
                        // Drop the snapshot stash entry on successful completion
                        dropSnapshotIfPresent(workingDir);
                    }
                })
                .subscribe(
                        responseMsg -> { /* terminal emit via hook */ },
                        err -> {
                            log.warn("confirmBuild execution error for session {}: {}",
                                    sessionId, err.getMessage());
                            phaseRef.compareAndSet(SessionPhase.EXECUTING, SessionPhase.FAILED_EXECUTION);
                            persistPhase(workingDir, SessionPhase.FAILED_EXECUTION);
                        }
                );

        return true;
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
            // Delegate cancellation to the payload (sets runningState=false, interrupts agent)
            entry.payload().stop();

            // Phase transition: stop() during EXECUTING → FAILED_EXECUTION
            AtomicReference<SessionPhase> phaseRef = sessionPhases.get(sessionId);
            if (phaseRef != null) {
                SessionPhase current = phaseRef.get();
                if (current == SessionPhase.EXECUTING) {
                    phaseRef.set(SessionPhase.FAILED_EXECUTION);
                    CodeAgentConfig cfg = entry.configOrNull();
                    if (cfg != null) persistPhase(cfg.workingDir(), SessionPhase.FAILED_EXECUTION);
                    log.info("Session {} stopped during EXECUTING — transitioned to FAILED_EXECUTION",
                            sessionId);
                } else if (current == SessionPhase.PLANNING) {
                    phaseRef.set(SessionPhase.FAILED_PLANNING);
                    CodeAgentConfig cfg = entry.configOrNull();
                    if (cfg != null) persistPhase(cfg.workingDir(), SessionPhase.FAILED_PLANNING);
                    log.info("Session {} stopped during PLANNING — transitioned to FAILED_PLANNING",
                            sessionId);
                } else {
                    log.info("Session {} stopped (phase={})", sessionId, current);
                }
            } else {
                log.info("Session {} stopped", sessionId);
            }
        } catch (Exception e) {
            log.warn("Error stopping session {}", sessionId, e);
        }
    }

    // ── Revert mechanism ─────────────────────────────────────────────────────────

    /**
     * Revert a session's workspace to the pre-execution snapshot.
     *
     * <p>Only valid when the session phase is FAILED_EXECUTION or COMPLETED.
     * Rejects EXECUTING sessions — the user must stop first.
     *
     * <p>After a successful revert:
     * <ul>
     *   <li>plan.md is PRESERVED (git-tracked, restored by stash apply)</li>
     *   <li>dag.json is PRESERVED if it exists</li>
     *   <li>snapshot.ref is CLEARED (deleted)</li>
     *   <li>REVERTED event emitted</li>
     *   <li>CLEAR_EXECUTION_MESSAGES event emitted (frontend clears execution-phase messages)</li>
     *   <li>Phase transitions to PLAN_PENDING</li>
     * </ul>
     *
     * @param sessionId the session ID
     * @return true if revert was successful
     */
    public boolean revertSession(String sessionId) {
        SessionEntry entry = sessions.get(sessionId);
        if (entry == null) {
            log.warn("revertSession: session {} not found", sessionId);
            return false;
        }

        AtomicReference<SessionPhase> phaseRef = sessionPhases.get(sessionId);
        if (phaseRef == null) {
            log.warn("revertSession: no phase tracking for session {}", sessionId);
            return false;
        }

        SessionPhase current = phaseRef.get();
        if (current == SessionPhase.EXECUTING) {
            log.warn("revertSession rejected for session {} — still EXECUTING, stop first", sessionId);
            return false;
        }
        if (current != SessionPhase.FAILED_EXECUTION && current != SessionPhase.COMPLETED) {
            log.warn("revertSession rejected for session {} — phase {} not revertible", sessionId, current);
            return false;
        }

        String workspaceDir = entry.config().workingDir();

        // Read snapshot ref from .kairo-session/snapshot.ref
        Path snapshotRefPath = Path.of(workspaceDir, SESSION_DIR, "snapshot.ref");
        String snapshotRef = null;
        boolean snapshotReadable = false;
        try {
            if (Files.exists(snapshotRefPath)) {
                String content = Files.readString(snapshotRefPath).trim();
                if (!content.isBlank()) {
                    snapshotRef = content;
                    snapshotReadable = true;
                }
            }
        } catch (IOException e) {
            log.warn("revertSession: failed to read snapshot.ref for session {}: {} — "
                    + "falling back to soft revert", sessionId, e.getMessage());
        }

        // Soft revert path: no usable snapshot, so we can't restore the workspace —
        // but the user still needs an escape hatch out of FAILED_EXECUTION. Clear
        // the phase, leave the workspace untouched, and emit the same events as a
        // normal revert so the UI updates consistently.
        if (!snapshotReadable) {
            log.info("revertSession: no usable snapshot.ref for session {} in {} — "
                    + "performing soft revert (phase only, workspace untouched)",
                    sessionId, workspaceDir);
            phaseRef.set(SessionPhase.IDLE);
            // Clear stale phase.txt so future restarts don't re-block this session
            try {
                Path phaseFile = Path.of(workspaceDir, SESSION_DIR, PHASE_FILE);
                Files.deleteIfExists(phaseFile);
            } catch (IOException e) {
                log.debug("Could not delete phase.txt after soft revert: {}", e.getMessage());
            }
            Sinks.Many<AgentEvent> sink = eventSinks.get(sessionId);
            if (sink != null) {
                sink.tryEmitNext(AgentEvent.reverted(sessionId));
                sink.tryEmitNext(AgentEvent.clearExecutionMessages(sessionId));
            }
            return true;
        }

        // Hard revert path: snapshot exists, restore workspace via WorkspaceSnapshotService
        boolean reverted = workspaceSnapshotService.revert(workspaceDir, snapshotRef);
        if (!reverted) {
            log.error("revertSession: revert failed for session {} (ref={})", sessionId, snapshotRef);
            return false;
        }

        // Transition phase to PLAN_PENDING
        phaseRef.set(SessionPhase.PLAN_PENDING);
        persistPhase(workspaceDir, SessionPhase.PLAN_PENDING);

        // Delete snapshot.ref (already deleted by revert(), but ensure idempotency)
        try {
            Files.deleteIfExists(snapshotRefPath);
        } catch (IOException e) {
            log.debug("Could not delete snapshot.ref after revert: {}", e.getMessage());
        }

        // Emit events
        Sinks.Many<AgentEvent> sink = eventSinks.get(sessionId);
        if (sink != null) {
            sink.tryEmitNext(AgentEvent.reverted(sessionId));
            sink.tryEmitNext(AgentEvent.clearExecutionMessages(sessionId));
        }

        log.info("Session {} reverted successfully (ref={}) — phase now PLAN_PENDING", sessionId, snapshotRef);
        return true;
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

        // Clean up plan-pending state
        sessionPhases.remove(sessionId);
        planOverviews.remove(sessionId);

        Sinks.Many<AgentEvent> sink = eventSinks.remove(sessionId);
        if (sink != null) {
            sink.tryEmitComplete();
        }

        try {
            entry.payload().stop();
        } catch (Exception e) {
            log.debug("Error stopping payload for session {}", sessionId, e);
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

        CodeAgentConfig cfg = entry.configOrNull();
        String todosJson = cfg != null ? TodoStorage.readJson(cfg.workingDir()) : "[]";

        log.info("Session {} bound: {} messages, running={}", sessionId, messages.size(), running);
        return AgentEvent.sessionRestored(sessionId, messagesJson, running, todosJson);
    }

    private List<Map<String, Object>> readCheckpointMessages(SessionEntry entry) {
        CodeAgentConfig cfg = entry.configOrNull();
        if (cfg == null || cfg.workingDir() == null) {
            return List.of();
        }
        Path workingDir = Path.of(cfg.workingDir());
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
                .map(e -> {
                    CodeAgentConfig cfg = e.configOrNull();
                    String workDir = cfg != null ? cfg.workingDir() : null;
                    String modelName = cfg != null ? cfg.modelName() : null;
                    boolean isGit = workspaceSnapshotService != null
                            && workDir != null
                            && workspaceSnapshotService.isGitWorkspace(workDir);
                    return new SessionInfo(
                            e.sessionId(),
                            workDir,
                            modelName,
                            e.createdAt(),
                            isRunning(e.sessionId()),
                            e.workspaceId(),
                            isGit);
                })
                .toList();
    }

    private boolean isRunning(String sessionId) {
        AtomicBoolean state = runningState.get(sessionId);
        return state != null && state.get();
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
        CodeAgentConfig current = entry.configOrNull();
        if (current == null) {
            return entry;
        }
        boolean stale = !java.util.Objects.equals(current.apiKey(), defaults.apiKey())
                || !java.util.Objects.equals(current.baseUrl(), defaults.baseUrl())
                || !java.util.Objects.equals(current.modelName(), defaults.modelName());
        if (!stale) {
            return entry;
        }
    
        // Don't rebuild while running
        AtomicBoolean running = runningState.get(entry.sessionId());
        if (running != null && running.get()) {
            log.warn("Skip rebuild for running session sid={}", entry.sessionId());
            return entry;
        }
    
        log.info(
                "Rebuilding session {} after credential update (model {} \u2192 {}, baseUrl {} \u2192 {})",
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
            String sid = entry.sessionId();
            AtomicReference<SessionPhase> phaseRef = sessionPhases.get(sid);
            Consumer<SessionPhase> persistPhaseFn = "experts".equals(entry.sessionMode())
                    ? p -> persistPhase(rebuilt.workingDir(), p)
                    : p -> {};
            AgentRuntimeContext newCtx = new AgentRuntimeContext(
                    sid, sink, running != null ? running : new AtomicBoolean(false),
                    phaseRef != null ? phaseRef : new AtomicReference<>(SessionPhase.IDLE),
                    persistPhaseFn, concurrencyController);
    
            // Pattern-match on sealed payload type
            SessionPayload newPayload;
            if (entry.payload() instanceof TeamSessionPayload t) {
                AgentSessionPayload newFallback = new AgentSessionPayload(rebuilt, newSession, newCtx);
                newPayload = new TeamSessionPayload(
                        t.coordinator(), t.teamConfig(), sid,
                        triageGate, newCtx, newFallback);
            } else {
                newPayload = new AgentSessionPayload(rebuilt, newSession, newCtx);
            }
    
            SessionEntry newEntry = new SessionEntry(
                    sid, entry.workspaceId(), entry.sessionMode(),
                    newPayload, approvalHandler, entry.createdAt());
            sessions.put(sid, newEntry);
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

    // ── Plan persistence & crash recovery ───────────────────────────────────────

    private static final String SESSION_DIR = ".kairo-session";
    private static final String PHASE_FILE = "phase.txt";

    /**
     * Persist the current session phase to {@code {workspaceDir}/.kairo-session/phase.txt}.
     * Called on significant state transitions so that crash recovery can detect interrupted ops.
     */
    void persistPhase(String workingDir, SessionPhase phase) {
        if (workingDir == null) return;
        try {
            Path dir = Path.of(workingDir, SESSION_DIR);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(PHASE_FILE), phase.name());
        } catch (IOException e) {
            log.debug("Failed to persist phase for workingDir {}: {}", workingDir, e.getMessage());
        }
    }

    /**
     * Read the snapshot ref from disk and drop the associated stash entry.
     * Used on successful completion to prevent stash accumulation.
     */
    private void dropSnapshotIfPresent(String workingDir) {
        if (workingDir == null) return;
        Path refFile = Path.of(workingDir, SESSION_DIR, "snapshot.ref");
        try {
            if (!Files.exists(refFile)) return;
            String ref = Files.readString(refFile).trim();
            if (!ref.isBlank()) {
                workspaceSnapshotService.dropSnapshot(workingDir, ref);
            }
        } catch (IOException e) {
            log.debug("Failed to read/drop snapshot ref in {}: {}", workingDir, e.getMessage());
        }
    }

    /**
     * On session creation, check if a previous session left behind persisted state.
     *
     * <p>Recovery rules:
     * <ul>
     *   <li>EXECUTING/PLANNING + snapshot.ref present → FAILED_EXECUTION (user can revert)</li>
     *   <li>EXECUTING/PLANNING + no snapshot.ref → IDLE (revert impossible, no point blocking)</li>
     *   <li>PLAN_PENDING → PLAN_PENDING (plan is on disk, safe to resume)</li>
     *   <li>FAILED_EXECUTION + snapshot.ref present → FAILED_EXECUTION (preserve block)</li>
     *   <li>FAILED_EXECUTION + no snapshot.ref → IDLE (auto-clear stale lock; revert can't help)</li>
     * </ul>
     *
     * <p>The "no snapshot" branches delete the phase.txt so the session is fully unblocked
     * and doesn't keep tripping recovery on every restart.
     */
    private void recoverSessionPhase(String workingDir, AtomicReference<SessionPhase> phaseRef) {
        if (workingDir == null) return;
        Path phaseFile = Path.of(workingDir, SESSION_DIR, PHASE_FILE);
        if (!Files.exists(phaseFile)) return;

        try {
            String persisted = Files.readString(phaseFile).trim();
            SessionPhase previous = SessionPhase.valueOf(persisted);
            boolean hasSnapshot = Files.exists(Path.of(workingDir, SESSION_DIR, "snapshot.ref"));

            if (previous == SessionPhase.EXECUTING || previous == SessionPhase.PLANNING) {
                if (hasSnapshot) {
                    phaseRef.set(SessionPhase.FAILED_EXECUTION);
                    Files.writeString(phaseFile, SessionPhase.FAILED_EXECUTION.name());
                    log.warn("Previous execution interrupted in workingDir={} (was {}). "
                            + "Forcing FAILED_EXECUTION. Snapshot available — revert recommended.",
                            workingDir, previous);
                } else {
                    phaseRef.set(SessionPhase.IDLE);
                    Files.deleteIfExists(phaseFile);
                    log.warn("Previous execution interrupted in workingDir={} (was {}) but no "
                            + "snapshot.ref found — auto-recovering to IDLE (revert is not possible).",
                            workingDir, previous);
                }
            } else if (previous == SessionPhase.PLAN_PENDING) {
                phaseRef.set(SessionPhase.PLAN_PENDING);
            } else if (previous == SessionPhase.FAILED_EXECUTION) {
                if (hasSnapshot) {
                    phaseRef.set(SessionPhase.FAILED_EXECUTION);
                } else {
                    // Stale failed state with no revertible snapshot — unblock the user.
                    phaseRef.set(SessionPhase.IDLE);
                    Files.deleteIfExists(phaseFile);
                    log.warn("Stale FAILED_EXECUTION in workingDir={} with no snapshot.ref — "
                            + "auto-recovering to IDLE.", workingDir);
                }
            }
        } catch (IllegalArgumentException e) {
            log.debug("Unknown persisted phase in {}: {}", phaseFile, e.getMessage());
        } catch (IOException e) {
            log.debug("Failed to read persisted phase from {}: {}", phaseFile, e.getMessage());
        }
    }

    /**
     * Return the current session phase for a given session, or null if unknown.
     * Exposed for WebSocket handler / controller integration.
     */
    public SessionPhase getSessionPhase(String sessionId) {
        AtomicReference<SessionPhase> ref = sessionPhases.get(sessionId);
        return ref != null ? ref.get() : null;
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
     *
     * <p>Prefers {@link Agent#diagnostics()} from kairo-core (authoritative source maintained by
     * the agent runtime) when available, falling back to the legacy {@link SessionDiagnosticsTracker}
     * for agents that don't implement the SPI yet.
     */
    public SessionDiagnostics getSessionDiagnostics(String sessionId) {
        SessionEntry entry = sessions.get(sessionId);
        if (entry == null) {
            return null;
        }
        Sinks.Many<AgentEvent> sink = eventSinks.get(sessionId);
        int wsClients = sink != null ? sink.currentSubscriberCount() : 0;

        // Prefer kairo-core's AgentDiagnostics when the agent implements it.
        AgentDiagnostics coreDiag = entry.session().agent().diagnostics();
        if (coreDiag != null) {
            long lastEpochMs = coreDiag.lastEventAt() != null
                    ? coreDiag.lastEventAt().toEpochMilli() : 0L;
            return new SessionDiagnostics(
                    sessionId,
                    coreDiag.running(),
                    lastEpochMs,
                    coreDiag.msSinceLastEvent(),
                    coreDiag.eventCounts(),
                    wsClients);
        }

        // Legacy fallback: local tracker maintained by AgentEventBridgeHook.
        // TODO: remove once all agent implementations expose diagnostics()
        SessionDiagnosticsTracker tracker = diagnosticsTrackers.get(sessionId);
        long lastEventAt = tracker != null ? tracker.lastEventAt() : 0L;
        long now = System.currentTimeMillis();
        long msSince = lastEventAt > 0 ? Math.max(0, now - lastEventAt) : Long.MAX_VALUE;
        Map<String, Long> counts = tracker != null ? tracker.snapshotCounts() : Map.of();
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
     *
     * <p>The polymorphic {@link SessionPayload} encapsulates either a single-agent
     * ({@link AgentSessionPayload}) or expert-team execution context.
     * {@code sessionMode} is {@code "agent"} for single-agent, {@code "experts"}
     * for expert-team mode.
     */
    public record SessionEntry(
            String sessionId,
            String workspaceId,
            String sessionMode,
            SessionPayload payload,
            WebSocketApprovalHandler approvalHandler,
            long createdAt
    ) {

        // ── Backward-compatible accessors ──

        /**
         * The agent configuration for this session.
         * Only valid for "agent" mode sessions backed by {@link AgentSessionPayload}.
         */
        public CodeAgentConfig config() {
            return agentPayload().config();
        }

        /**
         * The underlying code-agent session.
         * Only valid for "agent" mode sessions backed by {@link AgentSessionPayload}.
         */
        public CodeAgentSession session() {
            return agentPayload().session();
        }

        /** Shorthand for {@code session().agent()}. */
        public Agent agent() {
            return session().agent();
        }

        AgentSessionPayload agentPayload() {
            if (payload instanceof AgentSessionPayload asp) {
                return asp;
            }
            throw new IllegalStateException(
                    "Session " + sessionId + " is in '" + sessionMode
                    + "' mode — config()/session() require 'agent' mode");
        }

        /**
         * Payload-agnostic config accessor. Returns the underlying agent config for
         * either chat-mode sessions (AgentSessionPayload) or experts-mode sessions
         * (via TeamSessionPayload.fallback). Returns null if no config is reachable.
         */
        public CodeAgentConfig configOrNull() {
            if (payload instanceof AgentSessionPayload asp) {
                return asp.config();
            }
            if (payload instanceof TeamSessionPayload tsp) {
                AgentSessionPayload fb = tsp.fallback();
                return fb != null ? fb.config() : null;
            }
            return null;
        }
    }
}
