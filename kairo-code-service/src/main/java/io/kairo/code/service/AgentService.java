package io.kairo.code.service;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentDiagnostics;
import io.kairo.api.tool.ApprovalResult;
import io.kairo.api.tracing.Tracer;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentFactory;
import io.kairo.code.core.CodeAgentFactory.SessionOptions;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.code.core.stats.ToolUsageTracker;
import io.kairo.code.core.hook.CheckpointWriterHook;
import io.kairo.code.core.hook.ContextCompactionHook;
import io.kairo.code.service.agent.AgentRuntimeContext;
import io.kairo.code.service.agent.AgentSessionPayload;
import io.kairo.code.service.agent.MessageRequest;
import io.kairo.code.service.agent.SessionPayload;
import io.kairo.code.service.agent.TeamSessionPayload;
import io.kairo.code.service.agent.tools.NoNarrationTool;
import io.kairo.code.service.concurrency.AgentConcurrencyController;
import io.kairo.code.service.team.TriageGate;
import io.kairo.code.core.evolution.FailurePatternTracker;
import io.kairo.code.core.evolution.LearnedLessonStore;
import io.kairo.code.core.evolution.ReflectionPipeline;
import io.kairo.code.core.team.SwarmCoordinator;
import io.kairo.code.service.workspace.WorkspaceSnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import io.kairo.code.core.task.TaskToolDependencies;
import io.kairo.code.core.workspace.WorktreeWorkspaceProvider;
import io.kairo.code.core.workspace.WorktreeLifecycle;
import io.kairo.code.service.agent.ServerChildSessionSpawner;
import io.kairo.code.service.agent.AutoMergePrompter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Transport-agnostic agent service bridge.
 *
 * <p>Manages session lifecycle and exposes a {@link Flux}-based event API
 * that any transport layer (STOMP / SSE / CLI) can subscribe to.
 */
@Component
public class AgentService implements DisposableBean, InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final Map<String, Sinks.Many<AgentEvent>> eventSinks = new ConcurrentHashMap<>();
    private final Map<String, SessionEntry> sessions = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> runningState = new ConcurrentHashMap<>();
    private final Map<String, ToolProgressTracker> progressTrackers = new ConcurrentHashMap<>();
    /**
     * Last-activity timestamp per session (monotonic ns). Touched on
     * createSession + sendMessage so the idle reaper can tell live work
     * apart from abandoned tabs. Was added in M-IdleReaper after auditing
     * surfaced that sessions accumulated indefinitely (only destroySession
     * removed them) and the documented {@code KAIRO_SESSION_POOL_*} env
     * vars were read by upstream code paths kairo-code never invoked.
     */
    private final Map<String, AtomicLong> lastActivityNs = new ConcurrentHashMap<>();

    /** Background reaper that evicts sessions idle longer than {@link #idleTtlMillis}. */
    private volatile ScheduledExecutorService idleReaper;
    /** Idle TTL in millis. Read from {@code KAIRO_CODE_SESSION_IDLE_TTL_MINUTES} env (default 60). */
    private final long idleTtlMillis = resolveIdleTtlMillis();
    /** How often the reaper wakes up. 1/10 of TTL, bounded to keep tests reasonable. */
    private final long reaperPeriodMillis = Math.max(1_000L, Math.min(idleTtlMillis / 10, 60_000L));
    /**
     * Max concurrent sessions. Reads {@code KAIRO_CODE_SESSION_POOL_SIZE} (default 64).
     * Past this cap, createSession evicts the least-recently-active session before
     * accepting the new one — mirrors the LRU policy the upstream AgentSessionPool
     * uses. The active session itself is always safe (touched on the same call).
     *
     * <p>Non-final so tests can override via {@link #setSessionPoolSizeForTesting(int)}.
     * Production code never mutates it after construction.
     */
    private volatile int sessionPoolSize = resolveSessionPoolSize();

    /** Test-only — production should not call this. */
    void setSessionPoolSizeForTesting(int cap) {
        this.sessionPoolSize = cap;
    }

    private static long resolveIdleTtlMillis() {
        String env = System.getenv("KAIRO_CODE_SESSION_IDLE_TTL_MINUTES");
        if (env == null || env.isBlank()) return TimeUnit.MINUTES.toMillis(60);
        try {
            long mins = Long.parseLong(env.trim());
            if (mins <= 0) return TimeUnit.MINUTES.toMillis(60);
            return TimeUnit.MINUTES.toMillis(mins);
        } catch (NumberFormatException ignored) {
            return TimeUnit.MINUTES.toMillis(60);
        }
    }

    private static int resolveSessionPoolSize() {
        String env = System.getenv("KAIRO_CODE_SESSION_POOL_SIZE");
        if (env == null || env.isBlank()) return 64;
        try {
            int v = Integer.parseInt(env.trim());
            return v > 0 ? v : 64;
        } catch (NumberFormatException ignored) {
            return 64;
        }
    }

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

    /**
     * KairoEventBus — used by the Experts preset of {@link TeamSessionPayload} to project
     * SwarmCoordinator step events as {@code PEER_MESSAGE} into the Team Lead's sink.
     * Null when the kairo-core/event-bus bean is not on the classpath; the experts arm
     * tolerates this (warning logged inside TeamSessionPayload).
     */
    @Autowired(required = false)
    private io.kairo.api.event.KairoEventBus eventBus;

    /** Team-mode primitives — null when the kairo-code-server beans aren't on the classpath. */
    @Autowired(required = false)
    private io.kairo.code.core.team.TeamManager teamManager;

    @Autowired(required = false)
    private io.kairo.code.core.team.MessageBus messageBus;

    @Autowired
    private WorktreeManager worktreeManager;

    @Autowired
    private WorkspaceSnapshotService workspaceSnapshotService;

    private final SessionMetaPersistence sessionMetaPersistence = new SessionMetaPersistence();

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
        return createSession(config, workspaceId, useWorktree, "agent", null);
    }

    public String createSession(CodeAgentConfig config, String workspaceId, boolean useWorktree,
                                String sessionMode) {
        return createSession(config, workspaceId, useWorktree, sessionMode, null);
    }

    /**
     * Create a new session bound to a workspace with explicit session mode and permission mode.
     *
     * @param config         agent config (its {@code workingDir} is the workspace dir)
     * @param workspaceId    owning workspace id (may be null for legacy tests)
     * @param useWorktree    whether to provision a per-session git worktree
     * @param sessionMode    ignored (unified mode); kept for backward compat
     * @param permissionMode "bypass" to auto-approve all tool calls (headless/API); null for normal
     * @return the new session ID
     */
    public String createSession(CodeAgentConfig config, String workspaceId, boolean useWorktree,
                                String sessionMode, String permissionMode) {
        // Unified mode: legacy modes collapse to "agent", except "experts" which
        // enables auto-escalation to multi-agent fan-out.
        boolean explicitExperts = "experts".equalsIgnoreCase(sessionMode);
        String normalizedMode = explicitExperts ? "experts" : "agent";
        if (sessionMode != null && !sessionMode.isBlank()
                && !"agent".equals(sessionMode) && !"chat".equals(sessionMode)
                && !explicitExperts) {
            log.info("Legacy mode '{}' normalized to 'agent' (unified mode)", sessionMode);
        }

        // Enforce the LRU cap BEFORE we allocate a new sessionId — bursty
        // traffic (50 users hitting the server at once) used to grow the map
        // unboundedly because TTL eviction is asynchronous.
        evictLruIfFull();

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
                    config.thinkingBudget(),
                    config.llmClassifier()
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
        if ("bypass".equalsIgnoreCase(permissionMode)) {
            approvalHandler.setAutoApprove(true);
            log.info("Session {} using bypass permission mode (auto-approve all tools)", sessionId);
        }
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
        Consumer<SessionPhase> persistPhaseFn = p -> persistPhase(finalWorkingDir, p);

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

        // When the user explicitly selects Experts mode, force IDLE so the
        // escalation check in AgentSessionPayload.handleMessage() can fire.
        // A stale phase (e.g. PLAN_PENDING from a previous session) would
        // otherwise block the auto-escalation gate.
        if (explicitExperts && phaseRef.get() != SessionPhase.IDLE
                && phaseRef.get() != SessionPhase.COMPLETED) {
            log.info("Forcing phase IDLE for explicit experts mode (was {})", phaseRef.get());
            phaseRef.set(SessionPhase.IDLE);
            persistPhase(finalWorkingDir, SessionPhase.IDLE);
        }

        try {
            // Web sessions are interactive (like a REPL) — skip the one-shot/batch hooks that
            // force tool calls on text-only responses.
            // Hook list: bridge + compaction + plan-pending always; FailurePatternTracker
            // only when mode=experts (M-Experts-Upgrade self-evolution port). Agent and Team
            // modes' hook lists are bit-for-bit identical to pre-change behavior.
            java.util.List<Object> hooks = new java.util.ArrayList<>();
            hooks.add(bridgeHook);
            hooks.add(compactionHook);
            hooks.add(planHook);
            if (finalWorkingDir != null) {
                final CodeAgentConfig effectiveCfg = config;
                final Path kairoDir = Path.of(finalWorkingDir, ".kairo-code");
                final LearnedLessonStore lessonStore = LearnedLessonStore.fromKairoDir(kairoDir);
                FailurePatternTracker failureTracker = new FailurePatternTracker(strike -> {
                    AgentRuntimeContext.emitSerialized(sink, AgentEvent.textChunk(sessionId,
                            "⚠ Tool '" + strike.toolName() + "' has failed 3 consecutive times. "
                                    + "Generating lesson in background…"));
                    ReflectionPipeline.generateAndSave(strike, effectiveCfg, lessonStore);
                });
                hooks.add(failureTracker);
            }
            CheckpointWriterHook checkpointHook = null;
            if (finalWorkingDir != null) {
                checkpointHook = new CheckpointWriterHook(
                        Path.of(finalWorkingDir),
                        new com.fasterxml.jackson.databind.ObjectMapper(),
                        null);
                hooks.add(checkpointHook);
            }
            SessionOptions opts = SessionOptions.empty()
                    .asReplSession()
                    .withApprovalHandler(approvalHandler)
                    .withHooks(hooks);
            if (tracer != null) {
                // Wrap so every span carries session.id + langfuse.session.id +
                // langfuse.user.id. Without this Langfuse can't group multi-turn
                // chats into one Session view — was the #1 user pain on the
                // existing OTLP integration.
                opts = opts.withTracer(
                        new io.kairo.code.core.observability.SessionAwareTracer(
                                tracer, sessionId, workspaceId));
            }
            // Chunk sink: forward streaming tool output (e.g. bash stdout) to the
            // event sink so the frontend can render it incrementally.
            final String chunkSessionId = sessionId;
            final ToolProgressTracker chunkTracker = progressTracker;
            java.util.function.BiConsumer<String, String> chunkSink = (toolCallId, chunkText) -> {
                if (toolCallId != null) {
                    AgentRuntimeContext.emitSerialized(sink, 
                            AgentEvent.toolOutputChunk(chunkSessionId, toolCallId, chunkText));
                }
            };
            // Wire the task tool so Agent mode can spawn sub-agents for parallel work.
            // WorktreeWorkspaceProvider manages per-task worktrees; AutoMergePrompter auto-merges
            // child changes (no terminal to prompt in server mode).
            if (finalWorkingDir != null && !finalWorkingDir.isBlank()) {
                Path kairoDir = Path.of(System.getProperty("user.home"), ".kairo-code");
                WorktreeLifecycle lifecycle = new WorktreeLifecycle(
                        kairoDir.resolve("worktrees"), "git");
                WorktreeWorkspaceProvider workspaceProvider =
                        new WorktreeWorkspaceProvider(Path.of(finalWorkingDir), lifecycle);
                io.kairo.api.model.ModelProvider mp =
                        CodeAgentFactory.buildModelProvider(config.apiKey(), config.baseUrl());
                TaskToolDependencies taskDeps = new TaskToolDependencies(
                        workspaceProvider,
                        new ServerChildSessionSpawner(config, mp, sink, sessionId),
                        new AutoMergePrompter());
                opts = opts.withTaskTool(taskDeps);
            }

            Map<String, Object> extraToolDeps = Map.of(
                    io.kairo.core.tool.ToolInvocationRunner.CHUNK_SINK_KEY, chunkSink);
            CodeAgentSession session = CodeAgentFactory.createSession(config, opts, extraToolDeps);

            // ── Unified payload: always AgentSessionPayload + optional escalation ──
            AgentSessionPayload agentPayload = new AgentSessionPayload(config, session, ctx);
            if (checkpointHook != null) {
                agentPayload.setCheckpointHook(checkpointHook);
            }

            // Auto-escalation only when the user explicitly selects mode="experts".
            // The HeuristicTriageGate is too broad for automatic detection, but works
            // fine as a passthrough when the user has already opted in.
            SessionPayload payload = agentPayload;
            if (explicitExperts && swarmCoordinator != null && triageGate != null
                    && teamManager != null && messageBus != null) {
                io.kairo.api.model.ModelProvider narratorModel =
                        CodeAgentFactory.buildModelProvider(config.apiKey(), config.baseUrl());
                agentPayload.setEscalationConfig(new AgentSessionPayload.EscalationConfig(
                        swarmCoordinator,
                        io.kairo.api.team.TeamConfig.defaults(),
                        triageGate,
                        eventBus,
                        teamManager,
                        messageBus,
                        config,
                        narratorModel));
                log.info("Session {} created with experts escalation enabled", sessionId);
            }

            SessionEntry entry = new SessionEntry(
                    sessionId, workspaceId, normalizedMode, payload,
                    approvalHandler, System.currentTimeMillis());
            sessions.put(sessionId, entry);
            lastActivityNs.put(sessionId, new AtomicLong(System.nanoTime()));

            sessionMetaPersistence.save(new SessionMetaPersistence.SessionMeta(
                    sessionId, workspaceId, config.workingDir(), config.modelName(),
                    config.baseUrl(), "", phaseRef.get().name(), normalizedMode,
                    System.currentTimeMillis()));

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
        // Bump idle timer — reaper won't evict an actively-used session.
        AtomicLong activity = lastActivityNs.get(sessionId);
        if (activity != null) activity.set(System.nanoTime());
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
    // M-Experts-Upgrade (#61): lifecycle now lives entirely on the payload. We CAS-check phase,
    // create the workspace snapshot (a service concern AgentService owns), then delegate the actual
    // execution to {@link SessionPayload#confirmBuild()}. The payload owns the slot acquisition,
    // agent.call, and phase transitions to COMPLETED / FAILED_EXECUTION via its own ctx.
    // Default Agent/Team payloads return Flux.empty() — only TeamSessionPayload with the
    // experts preset actually drives execution here.
    public boolean confirmBuild(String sessionId) {
        AtomicReference<SessionPhase> phaseRef = sessionPhases.get(sessionId);
        if (phaseRef == null || phaseRef.get() != SessionPhase.PLAN_PENDING) {
            log.warn("confirmBuild rejected for session {} — not in PLAN_PENDING (phase={})",
                    sessionId, phaseRef != null ? phaseRef.get() : "null");
            return false;
        }

        SessionEntry entry = sessions.get(sessionId);
        if (entry == null) {
            return false;
        }

        CodeAgentConfig cfg = entry.configOrNull();
        if (cfg == null || cfg.workingDir() == null) {
            log.warn("confirmBuild: no config/workingDir available for session {}", sessionId);
            return false;
        }
        String workingDir = cfg.workingDir();

        Sinks.Many<AgentEvent> sink = eventSinks.get(sessionId);
        if (sink == null) {
            log.warn("confirmBuild: no event sink for session {}", sessionId);
            return false;
        }

        log.info("Plan confirmed for session {} — delegating to payload.confirmBuild()", sessionId);

        // Create workspace snapshot before execution begins (kept here — snapshot lifecycle
        // is an AgentService concern, not a payload concern).
        if (workspaceSnapshotService.isGitWorkspace(workingDir)) {
            workspaceSnapshotService.createSnapshot(workingDir);
        }

        // Drop the snapshot once execution completes successfully. The payload transitions
        // to COMPLETED on success — observe via its emitted AGENT_DONE.
        Flux<AgentEvent> execution = entry.payload().confirmBuild()
                .doOnNext(evt -> {
                    if (evt.type() == AgentEvent.EventType.AGENT_DONE) {
                        dropSnapshotIfPresent(workingDir);
                    }
                });

        // confirmBuild() emits directly onto the shared session sink (it returns
        // sharedSink.asFlux() and drives the coordinator internally). We subscribe ONLY to
        // run the snapshot-drop side effect above — re-emitting events here would feed the
        // shared sink back into itself (an infinite loop once emits are serialized).
        execution
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        evt -> { /* already on shared sink; nothing to forward */ },
                        err -> {
                            log.warn("confirmBuild delegation error for session {}: {}",
                                    sessionId, err.getMessage());
                            AgentRuntimeContext.emitSerialized(sink, AgentEvent.error(sessionId,
                                    err.getMessage(), "CONFIRM_BUILD_ERROR"));
                        });

        return true;
    }

    /**
     * Return a hot Flux of all events for the given session. The returned Flux stays alive until
     * the session is destroyed. Used by the WS handler to receive async events (PEER_MESSAGE from
     * swarm bridge, confirmBuild terminal events) that arrive outside of handleMessage lifecycles.
     */
    public Flux<AgentEvent> sessionEvents(String sessionId) {
        Sinks.Many<AgentEvent> sink = eventSinks.get(sessionId);
        if (sink == null) {
            return Flux.empty();
        }
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

            // Phase transition BEFORE payload.stop(): the run's doFinally races to
            // compareAndSet(PLANNING, IDLE) once stop() disposes it. Landing the FAILED_*
            // phase first means that compareAndSet no longer matches, so a stopped run
            // deterministically stays resumable (resumeSession accepts FAILED_*).
            //
            // Use compareAndSet (not set): if the run completed naturally in the window between
            // reading `current` and this transition (doFinally already moved PLANNING→IDLE), the
            // CAS fails and we must NOT clobber that legitimate IDLE with a spurious FAILED_*.
            AtomicReference<SessionPhase> phaseRef = sessionPhases.get(sessionId);
            if (phaseRef != null) {
                SessionPhase current = phaseRef.get();
                SessionPhase failed =
                        current == SessionPhase.EXECUTING
                                ? SessionPhase.FAILED_EXECUTION
                                : current == SessionPhase.PLANNING ? SessionPhase.FAILED_PLANNING : null;
                if (failed != null && phaseRef.compareAndSet(current, failed)) {
                    CodeAgentConfig cfg = entry.configOrNull();
                    if (cfg != null) persistPhase(cfg.workingDir(), failed);
                    log.info("Session {} stopped during {} — transitioned to {}",
                            sessionId, current, failed);
                } else {
                    log.info("Session {} stopped (phase={})", sessionId, phaseRef.get());
                }
            } else {
                log.info("Session {} stopped", sessionId);
            }

            // Delegate cancellation to the payload (sets runningState=false, interrupts agent)
            entry.payload().stop();
        } catch (Exception e) {
            log.warn("Error stopping session {}", sessionId, e);
        }
    }

    /**
     * Resume a session that was interrupted (FAILED_EXECUTION or FAILED_PLANNING).
     * Resets the phase to IDLE and then drives a "Continue from where you left off"
     * message through the normal execution path so the agent actually resumes work
     * (previously it only flipped the phase flag, requiring the user to manually send
     * a follow-up message).
     */
    public boolean resumeSession(String sessionId) {
        SessionEntry entry = sessions.get(sessionId);
        if (entry == null) {
            log.warn("resumeSession: session {} not found", sessionId);
            return false;
        }

        AtomicReference<SessionPhase> phaseRef = sessionPhases.get(sessionId);
        if (phaseRef == null) {
            log.warn("resumeSession: no phase tracking for session {}", sessionId);
            return false;
        }

        SessionPhase current = phaseRef.get();
        if (current != SessionPhase.FAILED_EXECUTION && current != SessionPhase.FAILED_PLANNING) {
            log.info("resumeSession: session {} phase is {} -- nothing to resume", sessionId, current);
            return false;
        }

        phaseRef.set(SessionPhase.IDLE);
        CodeAgentConfig cfg = entry.configOrNull();
        if (cfg != null) {
            persistPhase(cfg.workingDir(), SessionPhase.IDLE);
        }

        Sinks.Many<AgentEvent> sink = eventSinks.get(sessionId);
        if (sink != null) {
            AgentRuntimeContext.emitSerialized(sink, AgentEvent.sessionResumed(sessionId));
        }

        log.info("Session {} resumed from {} -- injecting continuation message", sessionId, current);

        // Drive the agent with a continuation prompt so it actually picks up where
        // it left off. The subscription is fire-and-forget (events flow to the sink
        // which the WS transport already subscribes to). Wrapped in try-catch because
        // unit tests may construct AgentService without Spring wiring (no concurrency
        // controller etc.), and the resume flag itself is the important contract.
        try {
            sendMessage(sessionId, "Continue from where you left off. "
                    + "Review what was done so far and complete the remaining work.")
                    .subscribe(
                            event -> { /* events already routed via sink */ },
                            err -> log.warn("Resume continuation failed for session {}: {}",
                                    sessionId, err.getMessage()));
        } catch (Exception e) {
            log.warn("Resume continuation could not start for session {}: {}",
                    sessionId, e.getMessage());
        }
        return true;
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
                AgentRuntimeContext.emitSerialized(sink, AgentEvent.reverted(sessionId));
                AgentRuntimeContext.emitSerialized(sink, AgentEvent.clearExecutionMessages(sessionId));
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
            AgentRuntimeContext.emitSerialized(sink, AgentEvent.reverted(sessionId));
            AgentRuntimeContext.emitSerialized(sink, AgentEvent.clearExecutionMessages(sessionId));
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
        lastActivityNs.remove(sessionId);
        ToolProgressTracker tracker = progressTrackers.remove(sessionId);
        if (tracker != null) {
            tracker.clear();
        }
        diagnosticsTrackers.remove(sessionId);

        // Clean up plan-pending state
        sessionPhases.remove(sessionId);
        planOverviews.remove(sessionId);
        sessionMetaPersistence.remove(sessionId);

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

        SessionPhase phase = getSessionPhase(sessionId);
        boolean resumable =
                phase == SessionPhase.FAILED_PLANNING || phase == SessionPhase.FAILED_EXECUTION;

        log.info("Session {} bound: {} messages, running={}, resumable={}",
                sessionId, messages.size(), running, resumable);
        return AgentEvent.sessionRestored(sessionId, messagesJson, running, todosJson, resumable);
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

            // The checkpoint file is workingDir-scoped (one per workspace), shared by every
            // session that runs there. Without this guard a freshly-created session ("New Chat")
            // would adopt the PREVIOUS session's conversation on bind. Only restore a checkpoint
            // written at/after this session was created; an older checkpoint belongs to a prior
            // session in the same workspace.
            JsonNode tsNode = root.get("timestamp");
            if (tsNode != null && tsNode.isTextual()) {
                try {
                    long checkpointMs = java.time.Instant.parse(tsNode.asText()).toEpochMilli();
                    if (checkpointMs < entry.createdAt()) {
                        return List.of();
                    }
                } catch (java.time.format.DateTimeParseException ignored) {
                    // Unparseable timestamp — fall through and restore (preserve prior behavior).
                }
            }

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
                || !java.util.Objects.equals(current.modelName(), defaults.modelName())
                // thinkingBudget moves through CodeAgentConfig into the
                // AnthropicProvider extended-thinking request param. Without
                // it in the staleness check, changing thinkingBudget alone
                // silently no-ops (Settings save persists fine but next chat
                // still uses the old budget). Including it here pulls in the
                // rebuild path so the new budget actually wires through.
                || !java.util.Objects.equals(current.thinkingBudget(), defaults.thinkingBudget());
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
                defaults.thinkingBudget(),
                current.llmClassifier());
    
        Sinks.Many<AgentEvent> sink = eventSinks.get(entry.sessionId());
        WebSocketApprovalHandler approvalHandler = entry.approvalHandler();
        String sid = entry.sessionId();
        AtomicReference<SessionPhase> phaseRef = sessionPhases.get(sid);
        if (phaseRef == null) {
            phaseRef = new AtomicReference<>(SessionPhase.IDLE);
            sessionPhases.put(sid, phaseRef);
        }

        AgentEventBridgeHook bridgeHook = new AgentEventBridgeHook(
                sink, sid, approvalHandler.announcedToolCallIds(), rebuilt.workingDir(),
                progressTrackers.get(sid), diagnosticsTrackerFor(sid));
        ContextCompactionHook compactionHook = buildCompactionHook(sink, sid);
        // Plan-pending intercept hook MUST be on the rebuilt session too. Without it,
        // exit_plan_mode tool calls don't transition the session into PLAN_PENDING —
        // the user loses the second-pass approval gate and the agent free-runs after
        // any post-rebuild plan. Tracked as P1-5 follow-up to the chat-path audit.
        PlanPendingInterceptHook planHook = new PlanPendingInterceptHook(
                sink, sid, rebuilt.workingDir(), phaseRef,
                overview -> planOverviews.put(sid, overview),
                () -> persistPhase(rebuilt.workingDir(), SessionPhase.PLAN_PENDING));

        SessionOptions opts = SessionOptions.empty()
                .asReplSession()
                .withApprovalHandler(approvalHandler)
                .withHooks(List.of(bridgeHook, compactionHook, planHook));
        if (tracer != null) {
            // Same session-id stamping as createSession — see Langfuse rationale there.
            opts = opts.withTracer(
                    new io.kairo.code.core.observability.SessionAwareTracer(
                            tracer, sid, entry.workspaceId()));
        }

        try {
            ToolProgressTracker rebuildTracker = progressTrackers.get(sid);
            Map<String, Object> rebuildExtraDeps = Map.of();
            if (rebuildTracker != null) {
                java.util.function.BiConsumer<String, String> rebuildChunkSink = (toolCallId, chunkText) -> {
                    if (toolCallId != null) {
                        AgentRuntimeContext.emitSerialized(sink, AgentEvent.toolOutputChunk(sid, toolCallId, chunkText));
                    }
                };
                rebuildExtraDeps = Map.of(
                        io.kairo.core.tool.ToolInvocationRunner.CHUNK_SINK_KEY, rebuildChunkSink);
            }
            CodeAgentSession newSession = CodeAgentFactory.createSession(rebuilt, opts, rebuildExtraDeps);
            Consumer<SessionPhase> persistPhaseFn = p -> persistPhase(rebuilt.workingDir(), p);
            AgentRuntimeContext newCtx = new AgentRuntimeContext(
                    sid, sink, running != null ? running : new AtomicBoolean(false),
                    phaseRef != null ? phaseRef : new AtomicReference<>(SessionPhase.IDLE),
                    persistPhaseFn, concurrencyController);
    
            AgentSessionPayload newPayload = new AgentSessionPayload(rebuilt, newSession, newCtx);
            if (swarmCoordinator != null && triageGate != null
                    && teamManager != null && messageBus != null) {
                io.kairo.api.model.ModelProvider narratorModel =
                        CodeAgentFactory.buildModelProvider(rebuilt.apiKey(), rebuilt.baseUrl());
                newPayload.setEscalationConfig(new AgentSessionPayload.EscalationConfig(
                        swarmCoordinator,
                        io.kairo.api.team.TeamConfig.defaults(),
                        triageGate,
                        eventBus,
                        teamManager,
                        messageBus,
                        rebuilt,
                        narratorModel));
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
            Sinks.EmitResult emit = AgentRuntimeContext.emitSerialized(sink, 
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

    void persistPhase(String sessionId, String workingDir, SessionPhase phase) {
        persistPhase(workingDir, phase);
        sessionMetaPersistence.updatePhase(sessionId, phase);
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
                                Sinks.EmitResult emit = AgentRuntimeContext.emitSerialized(sink, event);
                                if (emit.isFailure()) {
                                    log.debug(
                                            "Skipped TOOL_PROGRESS for {} ({}): {}",
                                            sessionId,
                                            inflight.toolCallId(),
                                            emit);
                                } else {
                                    // Touch the agent's diagnostics so StallDetector doesn't
                                    // kill the parent while a long-running tool (e.g. task
                                    // subagent) is executing. The heartbeat proves the session
                                    // is alive even though the agent thread is blocked.
                                    touchAgentDiagnostics(sessionId);
                                }
                            });
                });
    }

    private void touchAgentDiagnostics(String sessionId) {
        SessionEntry entry = sessions.get(sessionId);
        if (entry == null) return;
        try {
            var diag = entry.session().agent().diagnostics();
            if (diag != null) {
                // Use reflection to call recordEvent on the package-private
                // MutableDiagnostics interface. This keeps StallDetector from killing
                // the parent session while a subagent tool blocks its thread.
                java.lang.reflect.Method m = diag.getClass().getMethod("recordEvent", String.class);
                m.invoke(diag, "tool_progress");
            }
        } catch (Exception e) {
            // Best-effort; don't let diagnostics failure break the heartbeat loop.
        }
    }

    @Override
    public void destroy() {
        if (progressTickSubscription != null) {
            progressTickSubscription.dispose();
            progressTickSubscription = null;
        }
        if (idleReaper != null) {
            idleReaper.shutdownNow();
            idleReaper = null;
        }
    }

    @Override
    public void afterPropertiesSet() {
        startIdleReaper();
        // Delay rehydrate by 5s to let Spring finish wiring all beans (defaultConfig
        // is set by ServerConfig @Bean which may not be ready during afterPropertiesSet).
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kairo-session-rehydrate");
            t.setDaemon(true);
            return t;
        }).schedule(this::rehydrateSessions, 5, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void rehydrateSessions() {
        List<SessionMetaPersistence.SessionMeta> pending = sessionMetaPersistence.loadNonTerminal();
        if (pending.isEmpty()) {
            log.info("No sessions to rehydrate on startup");
            return;
        }
        log.info("Rehydrating {} session(s) from previous run", pending.size());
        int restored = 0;
        for (SessionMetaPersistence.SessionMeta meta : pending) {
            try {
                if (defaultConfig == null) {
                    log.warn("Cannot rehydrate session {} -- no default config available yet", meta.sessionId());
                    continue;
                }
                CodeAgentConfig config = new CodeAgentConfig(
                        defaultConfig.apiKey(),
                        meta.baseUrl() != null && !meta.baseUrl().isBlank() ? meta.baseUrl() : defaultConfig.baseUrl(),
                        meta.modelName() != null && !meta.modelName().isBlank() ? meta.modelName() : defaultConfig.modelName(),
                        Integer.MAX_VALUE,
                        meta.workingDir(),
                        null, 0, 0,
                        defaultConfig.thinkingBudget(),
                        defaultConfig.llmClassifier());
                String sid = createSession(config, meta.workspaceId(), false, meta.mode(), null);
                log.info("Rehydrated session {} (original={}, workspace={})", sid, meta.sessionId(), meta.workspaceId());
                restored++;
            } catch (Exception e) {
                log.warn("Failed to rehydrate session {}: {}", meta.sessionId(), e.getMessage());
                sessionMetaPersistence.remove(meta.sessionId());
            }
        }
        log.info("Rehydrated {}/{} sessions", restored, pending.size());
    }

    void startIdleReaper() {
        // Single-threaded daemon — reap is cheap (just a map walk + remove).
        idleReaper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kairo-session-reaper");
            t.setDaemon(true);
            return t;
        });
        idleReaper.scheduleAtFixedRate(
                this::reapIdleSessions,
                reaperPeriodMillis,
                reaperPeriodMillis,
                TimeUnit.MILLISECONDS);
        log.info("Idle session reaper started (ttl={}ms, period={}ms)", idleTtlMillis, reaperPeriodMillis);
    }

    /**
     * Visible for testing. Walks the session map and removes anything with no
     * activity for {@link #idleTtlMillis}. Skips sessions still marked running
     * — a long-running tool call may legitimately not touch the bump for a
     * while, killing it mid-flight would lose user work.
     */
    void reapIdleSessions() {
        long now = System.nanoTime();
        long thresholdNs = TimeUnit.MILLISECONDS.toNanos(idleTtlMillis);
        int reaped = 0;
        for (String sid : sessions.keySet()) {
            AtomicLong lastNs = lastActivityNs.get(sid);
            if (lastNs == null) {
                // Defensive: a session without a tracked activity time is bogus.
                lastActivityNs.put(sid, new AtomicLong(now));
                continue;
            }
            if (now - lastNs.get() < thresholdNs) continue;
            AtomicBoolean running = runningState.get(sid);
            if (running != null && running.get()) continue;
            try {
                destroySession(sid);
                reaped++;
            } catch (RuntimeException e) {
                log.warn("Idle reaper: failed to destroy session {} — {}", sid, e.getMessage());
            }
        }
        if (reaped > 0) log.info("Idle reaper evicted {} session(s)", reaped);
    }

    /** Visible for testing — bypass the @PostConstruct so tests can drive reap manually. */
    long idleTtlMillis() {
        return idleTtlMillis;
    }

    /** Visible for testing. */
    int sessionPoolSize() {
        return sessionPoolSize;
    }

    /**
     * If the session map is at capacity, evict the least-recently-active idle
     * session to make room. Running sessions are never evicted — a long bash
     * tool needs to finish even if it pushes us over the cap. If every session
     * is running, this becomes a no-op and createSession continues; the
     * pool-size guarantee is best-effort under contention.
     *
     * <p>Visible for testing.
     */
    void evictLruIfFull() {
        if (sessions.size() < sessionPoolSize) return;
        String victim = null;
        long victimNs = Long.MAX_VALUE;
        for (Map.Entry<String, AtomicLong> e : lastActivityNs.entrySet()) {
            AtomicBoolean running = runningState.get(e.getKey());
            if (running != null && running.get()) continue;
            long ts = e.getValue().get();
            if (ts < victimNs) {
                victimNs = ts;
                victim = e.getKey();
            }
        }
        if (victim != null) {
            log.info("Session pool at cap ({}), evicting LRU session {}", sessionPoolSize, victim);
            destroySession(victim);
        }
    }

    /** Visible for testing — register an entry in the activity tracker. */
    void touchActivity(String sessionId) {
        lastActivityNs.computeIfAbsent(sessionId, k -> new AtomicLong()).set(System.nanoTime());
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
     * Mid-session metrics snapshot: tool call counts, redundant file reads,
     * iterations without tools, hook interventions, plus best-effort
     * {@code tokensUsed} / {@code iterations} / {@code durationMillis}.
     *
     * <p>Returns {@code null} when the session is unknown. Returns an
     * "empty" snapshot when the session exists but had no
     * {@code SessionMetricsCollector} registered (e.g. legacy REPL paths
     * — see {@link CodeAgentFactory}: collector is auto-registered only
     * for non-REPL sessions, i.e. all WebSocket / HTTP sessions).
     *
     * <p>Mirrors the schema written to {@code KAIRO_SESSION_RESULT.json}
     * by {@code SessionResultWriterHook} so the external-runner protocol
     * (CLI batch mode) and REST polling (web mode) speak the same shape.
     */
    public SessionMetricsSnapshot getSessionMetrics(String sessionId) {
        SessionEntry entry = sessions.get(sessionId);
        if (entry == null) {
            return null;
        }
        io.kairo.code.core.hook.SessionMetricsCollector collector = null;
        try {
            collector = entry.session().sessionMetricsCollector();
        } catch (RuntimeException ignored) {
            // sessionMetricsCollector() may throw if the agent isn't a CodeAgentSession-backed payload.
        }

        Map<String, Integer> toolCallCounts = Map.of();
        List<Map<String, Object>> redundantReads = List.of();
        int iterationsNoTool = 0;
        if (collector != null) {
            toolCallCounts = collector.toolCallCountsSnapshot();
            redundantReads = collector.redundantReads().stream()
                    .map(e -> Map.<String, Object>of("file", e.getKey(), "count", e.getValue()))
                    .toList();
            iterationsNoTool = collector.iterationsWithoutToolsCount();
        }

        // tokensUsed + iterations come from AgentDiagnostics, which DefaultReActAgent
        // now wires to its own atomics (kairo upstream M-EV10 fix). Pre-fix this was
        // always 0; if the agent doesn't surface diagnostics we still return 0 rather
        // than fail.
        long tokensUsed = 0L;
        int iterations = 0;
        try {
            io.kairo.api.agent.AgentDiagnostics agentDiag = entry.session().agent().diagnostics();
            if (agentDiag != null) {
                tokensUsed = agentDiag.totalTokensConsumed();
                iterations = agentDiag.currentIteration();
            }
        } catch (RuntimeException ignored) {
            // Same defensive pattern as the SessionMetricsCollector lookup above.
        }
        long durationMs = Math.max(0, System.currentTimeMillis() - entry.createdAt());

        return new SessionMetricsSnapshot(
                sessionId,
                tokensUsed,
                iterations,
                durationMs,
                iterationsNoTool,
                toolCallCounts,
                redundantReads);
    }

    /**
     * Per-session metrics snapshot. Schema mirrors {@code KAIRO_SESSION_RESULT.json}
     * so {@code kairo-code-eval}'s {@code AgentRunner.read_session_result} can
     * consume either source interchangeably.
     */
    public record SessionMetricsSnapshot(
            String sessionId,
            long tokensUsed,
            int iterations,
            long durationMillis,
            int iterationsWithoutTools,
            Map<String, Integer> toolCallCounts,
            List<Map<String, Object>> redundantReads) {}

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
     * <p>Unified mode: {@code sessionMode} is always {@code "agent"}.
     * Auto-escalation to expert team is handled internally by
     * {@link AgentSessionPayload} when escalation criteria are met.
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
         * chat-mode sessions (AgentSessionPayload) or team-mode sessions including the
         * experts preset (via TeamSessionPayload.config). Returns null if no config is
         * reachable.
         */
        public CodeAgentConfig configOrNull() {
            if (payload instanceof AgentSessionPayload asp) {
                return asp.config();
            }
            if (payload instanceof TeamSessionPayload tsp) {
                return tsp.config();
            }
            return null;
        }
    }
}
