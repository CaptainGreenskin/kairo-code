package io.kairo.code.service;

import io.kairo.api.agent.Agent;
import io.kairo.api.tool.ApprovalResult;
import io.kairo.api.tracing.Tracer;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentFactory;
import io.kairo.code.core.CodeAgentFactory.SessionOptions;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.code.core.stats.ToolUsageTracker;
import io.kairo.code.service.agent.AgentRuntimeContext;
import io.kairo.code.service.agent.AgentSessionPayload;
import io.kairo.code.service.agent.MessageRequest;
import io.kairo.code.service.agent.SessionPayload;
import io.kairo.code.service.agent.TeamSessionPayload;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import io.kairo.code.core.task.TaskToolDependencies;
import io.kairo.code.core.workspace.WorktreeWorkspaceProvider;
import io.kairo.code.core.workspace.WorktreeLifecycle;
import io.kairo.code.service.agent.ServerChildSessionSpawner;
import io.kairo.code.service.agent.AutoMergePrompter;
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

    private final SessionRegistry registry;
    private final SessionDiagnosticsService diagnosticsService;

    @Autowired
    public AgentService(SessionRegistry registry, SessionDiagnosticsService diagnosticsService) {
        this.registry = registry;
        this.diagnosticsService = diagnosticsService;
    }

    public AgentService() {
        this.registry = new SessionRegistry();
        this.diagnosticsService = new SessionDiagnosticsService(this.registry);
    }

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
    private io.kairo.api.team.TeamManager teamManager;

    @Autowired(required = false)
    private io.kairo.api.team.MessageBus messageBus;

    @Autowired
    private WorktreeManager worktreeManager;

    @Autowired(required = false)
    private io.kairo.code.core.evolution.KairoEvolutionHook evolutionHook;

    @Autowired(required = false)
    private io.kairo.skill.SkillContentInjector skillContentInjector;

    @Autowired
    private WorkspaceSnapshotService workspaceSnapshotService;

    private final SessionMetaPersistence sessionMetaPersistence = new SessionMetaPersistence();
    private final SessionIndexService sessionIndexService = new SessionIndexService();

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

        registry.evictLruIfFull();

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

        Sinks.Many<AgentEvent> sink = Sinks.many()
                .multicast()
                .onBackpressureBuffer(Queues.SMALL_BUFFER_SIZE, false);

        WebSocketApprovalHandler approvalHandler = new WebSocketApprovalHandler(sink, sessionId);
        if ("bypass".equalsIgnoreCase(permissionMode)) {
            approvalHandler.setAutoApprove(true);
            log.info("Session {} using bypass permission mode (auto-approve all tools)", sessionId);
        }
        ToolProgressTracker progressTracker = new ToolProgressTracker();
        approvalHandler.setProgressTracker(progressTracker);
        SessionDiagnosticsTracker diagTracker = new SessionDiagnosticsTracker();
        diagnosticsService.startProgressTickerIfNeeded();
        AgentEventBridgeHook bridgeHook = new AgentEventBridgeHook(
                sink, sessionId, approvalHandler.announcedToolCallIds(),
                config.workingDir(), progressTracker, diagTracker);

        CompactionEventBridgeHook compactionBridge = new CompactionEventBridgeHook(sink, sessionId);

        // ── Build AgentRuntimeContext ───────────────────────────────────────────────
        AtomicBoolean running = new AtomicBoolean(false);
        AtomicReference<SessionPhase> phaseRef = new AtomicReference<>(SessionPhase.IDLE);

        String finalWorkingDir = config.workingDir();
        // Persist phase to session-specific directory
        final String phaseSid = sessionId;
        Consumer<SessionPhase> persistPhaseFn = p -> {
            if (finalWorkingDir != null) {
                Path phaseFile = Path.of(finalWorkingDir).resolve(".kairo-session")
                        .resolve(phaseSid).resolve("phase.txt");
                persistPhaseToFile(phaseFile, p);
            }
        };

        AgentRuntimeContext ctx = new AgentRuntimeContext(
                sessionId, sink, running, phaseRef, persistPhaseFn, concurrencyController);

        // ── Plan-pending intercept hook ────────────────────────────────────────────
        final String planSid = sessionId;
        PlanPendingInterceptHook planHook = new PlanPendingInterceptHook(
                sink, sessionId, finalWorkingDir, ctx.phaseRef(),
                overview -> {
                    SessionContext sctx = registry.get(planSid);
                    if (sctx != null) sctx.setPlanOverview(overview);
                },
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
            hooks.add(compactionBridge);
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
                if (evolutionHook != null) {
                    final int evoThreshold = 8;
                    hooks.add(new Object() {
                        @io.kairo.api.hook.HookHandler(io.kairo.api.hook.HookPhase.PRE_COMPLETE)
                        public io.kairo.api.hook.HookResult<io.kairo.api.hook.PreCompleteEvent>
                                onPreComplete(io.kairo.api.hook.PreCompleteEvent event) {
                            int msgCount = event.conversationHistory() != null
                                    ? event.conversationHistory().size() : 0;
                            if (msgCount >= evoThreshold * 2) {
                                AgentRuntimeContext.emitSerialized(sink,
                                        AgentEvent.textChunk(sessionId,
                                                "<evolution-event type=\"review_starting\" />"));
                            }
                            return io.kairo.api.hook.HookResult.proceed(event);
                        }
                    });
                    hooks.add(evolutionHook);
                }
                if (skillContentInjector != null) {
                    hooks.add(skillContentInjector);
                }
            }
            if (finalWorkingDir != null) {
                // Session isolation: migrate legacy, ensure dir, GC via framework provider
                var sessionStorage = new io.kairo.core.session.FileSessionStorageProvider(
                        Path.of(finalWorkingDir).resolve(".kairo-session"));
                sessionStorage.migrateLegacy();
                sessionStorage.ensureSession(sessionId);
                sessionStorage.gc();
            }
            ToolUsageTracker usageTracker = new ToolUsageTracker();
            SessionOptions opts = SessionOptions.empty()
                    .asReplSession()
                    .withSessionId(sessionId)
                    .withApprovalHandler(approvalHandler)
                    .withToolUsageTracker(usageTracker)
                    .withHooks(hooks);
            if (teamManager != null && messageBus != null) {
                opts = opts.withTeamPrimitives(teamManager, messageBus);
            }
            if (tracer != null) {
                // Wrap so every span carries session.id + langfuse.session.id +
                // langfuse.user.id. Without this Langfuse can't group multi-turn
                // chats into one Session view — was the #1 user pain on the
                // existing OTLP integration.
                opts = opts.withTracer(
                        new io.kairo.core.tracing.SessionAwareTracer(
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
                        CodeAgentFactory.buildModelProvider(config.apiKey(), config.baseUrl(), config.modelName());
                var subagentRegistry = new io.kairo.code.core.task.SubagentRegistry();
                var notificationQueue = new io.kairo.code.core.task.BackgroundTaskNotificationQueue();
                TaskToolDependencies taskDeps = new TaskToolDependencies(
                        workspaceProvider,
                        new ServerChildSessionSpawner(config, mp, sink, sessionId,
                                0, workspaceProvider),
                        new AutoMergePrompter(),
                        null,
                        (taskId2, desc, result, error, durationMs) -> {
                            String status = error == null ? "completed" : "failed";
                            String notification = "<task-notification task_id=\"" + taskId2
                                    + "\" description=\"" + desc + "\" status=\"" + status
                                    + "\" duration_ms=\"" + durationMs + "\"> "
                                    + (result != null ? result : (error != null ? error.getMessage() : ""))
                                    + " </task-notification>";
                            notificationQueue.offer(notification);
                            sink.tryEmitNext(AgentEvent.textChunk(sessionId, notification));
                        },
                        subagentRegistry,
                        config,
                        io.kairo.code.core.GlobalSharedTaskList.INSTANCE,
                        notificationQueue);
                opts = opts.withTaskTool(taskDeps);
            }

            Map<String, Object> extraToolDeps = Map.of(
                    io.kairo.core.tool.ToolInvocationRunner.CHUNK_SINK_KEY, chunkSink);
            CodeAgentSession session = CodeAgentFactory.createSession(config, opts, extraToolDeps);
            bridgeHook.setCostTracker(session.costTracker());

            // ── Unified payload: always AgentSessionPayload + optional escalation ──
            AgentSessionPayload agentPayload = new AgentSessionPayload(config, session, ctx);

            // Auto-escalation only when the user explicitly selects mode="experts".
            // The HeuristicTriageGate is too broad for automatic detection, but works
            // fine as a passthrough when the user has already opted in.
            SessionPayload payload = agentPayload;
            if (explicitExperts && swarmCoordinator != null && triageGate != null
                    && teamManager != null && messageBus != null) {
                io.kairo.api.model.ModelProvider narratorModel =
                        CodeAgentFactory.buildModelProvider(config.apiKey(), config.baseUrl(), config.modelName());
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

            SessionContext sessionContext = new SessionContext(
                    sessionId, entry, sink, running, progressTracker, phaseRef);
            sessionContext.setDiagnosticsTracker(diagTracker);
            registry.register(sessionContext);

            sessionMetaPersistence.save(new SessionMetaPersistence.SessionMeta(
                    sessionId, workspaceId, config.workingDir(), config.modelName(),
                    config.baseUrl(), "", phaseRef.get().name(), normalizedMode,
                    System.currentTimeMillis()));

            sessionIndexService.upsert(new SessionIndexEntry(
                    sessionId, null, workspaceId, config.workingDir(),
                    config.modelName(), SessionIndexEntry.STATUS_ACTIVE,
                    System.currentTimeMillis(), System.currentTimeMillis(),
                    0, false));

            log.info("Session {} created successfully", sessionId);
            return sessionId;
        } catch (Exception e) {
            log.error("Failed to create session {}", sessionId, e);
            sink.tryEmitComplete();
            registry.unregister(sessionId);
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
     * Manually trigger context compaction for a session. Emits a {@code CONTEXT_COMPACTED}
     * event and sends a compaction prompt to the agent so it summarizes the conversation.
     */
    public Flux<AgentEvent> compactSession(String sessionId) {
        SessionContext sctx = registry.get(sessionId);
        if (sctx == null) {
            return Flux.just(AgentEvent.error(sessionId,
                    "Session not found: " + sessionId, "SESSION_NOT_FOUND"));
        }
        Sinks.Many<AgentEvent> sink = sctx.eventSink();
        SessionEntry entry = sctx.entry();
        int maxTokens = 100_000;
        if (entry.configOrNull() != null) {
            maxTokens = io.kairo.core.model.ModelRegistry.getContextWindow(
                    entry.configOrNull().modelName());
        }
        AgentRuntimeContext.emitSerialized(sink,
                AgentEvent.contextCompacted(sessionId, maxTokens, maxTokens));
        log.info("Manual compaction triggered for session {}", sessionId);
        String compactPrompt = "The conversation context is getting large. "
                + "Please summarize all work done so far — including what has been tried, "
                + "what succeeded, what failed, and the current state of the task — in a "
                + "concise summary (around 200 words). Then continue working.";
        io.kairo.api.message.Msg compactionMsg = io.kairo.api.message.Msg.builder()
                .role(io.kairo.api.message.MsgRole.USER)
                .addContent(new io.kairo.api.message.Content.TextContent(compactPrompt))
                .metadata("kairo.kind", "compaction")
                .metadata("kairo.compaction.beforeTokens", maxTokens)
                .metadata("kairo.compaction.maxTokens", maxTokens)
                .build();
        return sendMessage(sessionId, new MessageRequest(compactionMsg));
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
        SessionContext sctx = registry.get(sessionId);
        if (sctx == null) {
            return Flux.just(AgentEvent.error(sessionId,
                    "Session not found: " + sessionId, "SESSION_NOT_FOUND"));
        }
        sctx.touch();
        SessionEntry entry = rebuildIfStale(sctx.entry());
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
        SessionContext sctx = registry.get(sessionId);
        if (sctx == null) {
            log.warn("confirmBuild rejected for session {} — not found", sessionId);
            return false;
        }
        AtomicReference<SessionPhase> phaseRef = sctx.phase();
        if (phaseRef.get() != SessionPhase.PLAN_PENDING) {
            log.warn("confirmBuild rejected for session {} — not in PLAN_PENDING (phase={})",
                    sessionId, phaseRef.get());
            return false;
        }

        SessionEntry entry = sctx.entry();
        CodeAgentConfig cfg = entry.configOrNull();
        if (cfg == null || cfg.workingDir() == null) {
            log.warn("confirmBuild: no config/workingDir available for session {}", sessionId);
            return false;
        }
        String workingDir = cfg.workingDir();
        Sinks.Many<AgentEvent> sink = sctx.eventSink();

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
        return registry.sessionEvents(sessionId);
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
        SessionContext sctx = registry.get(sessionId);
        if (sctx == null) {
            return false;
        }

        ApprovalResult result = approved
                ? ApprovalResult.allow()
                : ApprovalResult.denied(reason != null ? reason : "User denied");

        return sctx.entry().approvalHandler().resolveApproval(toolCallId, result, editedArgs);
    }

    /**
     * Interrupt the current agent execution.
     */
    public void stopAgent(String sessionId) {
        SessionContext stopCtx = registry.get(sessionId);
        if (stopCtx == null) {
            return;
        }

        SessionEntry entry = stopCtx.entry();
        try {
            entry.approvalHandler().cancelAll();

            AtomicReference<SessionPhase> phaseRef = stopCtx.phase();
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
        SessionContext sctx = registry.get(sessionId);
        if (sctx == null) {
            log.warn("resumeSession: session {} not found", sessionId);
            return false;
        }
        SessionEntry entry = sctx.entry();

        AtomicReference<SessionPhase> phaseRef = sctx.phase();
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

        AgentRuntimeContext.emitSerialized(sctx.eventSink(), AgentEvent.sessionResumed(sessionId));

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
        SessionContext sctx = registry.get(sessionId);
        if (sctx == null) {
            log.warn("revertSession: session {} not found", sessionId);
            return false;
        }
        SessionEntry entry = sctx.entry();

        AtomicReference<SessionPhase> phaseRef = sctx.phase();
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
            AgentRuntimeContext.emitSerialized(sctx.eventSink(), AgentEvent.reverted(sessionId));
            AgentRuntimeContext.emitSerialized(sctx.eventSink(), AgentEvent.clearExecutionMessages(sessionId));
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

        AgentRuntimeContext.emitSerialized(sctx.eventSink(), AgentEvent.reverted(sessionId));
        AgentRuntimeContext.emitSerialized(sctx.eventSink(), AgentEvent.clearExecutionMessages(sessionId));

        log.info("Session {} reverted successfully (ref={}) — phase now PLAN_PENDING", sessionId, snapshotRef);
        return true;
    }

    /**
     * Destroy a session and clean up all resources.
     */
    public boolean destroySession(String sessionId) {
        SessionContext sctx = registry.unregister(sessionId);
        if (sctx == null) {
            return false;
        }

        log.info("Destroying session {}", sessionId);
        SessionEntry entry = sctx.entry();
        entry.approvalHandler().cancelAll();
        sctx.progressTracker().clear();
        sctx.eventSink().tryEmitComplete();

        sessionMetaPersistence.remove(sessionId);
        sessionIndexService.updateStatus(sessionId, SessionIndexEntry.STATUS_IDLE);

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
        SessionContext bindCtx = registry.get(sessionId);
        if (bindCtx == null) {
            log.warn("bindSession: session {} not found", sessionId);
            return null;
        }

        SessionEntry entry = bindCtx.entry();
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
        // Only send todos if this session has history (avoid showing stale todos from other sessions)
        String todosJson = (cfg != null && !messages.isEmpty())
                ? TodoStorage.readJson(cfg.workingDir()) : "[]";

        SessionPhase phase = getSessionPhase(sessionId);
        boolean resumable =
                phase == SessionPhase.FAILED_PLANNING || phase == SessionPhase.FAILED_EXECUTION;

        log.info("Session {} bound: {} messages, running={}, resumable={}",
                sessionId, messages.size(), running, resumable);
        return AgentEvent.sessionRestored(sessionId, messagesJson, running, todosJson, resumable);
    }

    private List<Map<String, Object>> readCheckpointMessages(SessionEntry entry) {
        CodeAgentConfig cfg = entry.configOrNull();
        if (cfg == null || cfg.workingDir() == null) return List.of();

        // Session-isolated checkpoint: .kairo-session/{sessionId}/iterations/
        var sessionStorage = new io.kairo.core.session.FileSessionStorageProvider(
                Path.of(cfg.workingDir()).resolve(".kairo-session"));
        // Use session-scoped checkpoint store; falls back to legacy if session dir doesn't exist
        Path sessionIterDir = sessionStorage.sessionDir(entry.sessionId()).resolve("iterations");
        io.kairo.api.agent.IterationCheckpointStore store;
        if (java.nio.file.Files.isDirectory(sessionIterDir)) {
            store = sessionStorage.checkpointStore(entry.sessionId());
        } else {
            store = new io.kairo.core.agent.checkpoint.JsonFileIterationCheckpointStore(
                    Path.of(cfg.workingDir()).resolve(".kairo-session").resolve("iterations"),
                    new io.kairo.core.session.SessionSerializer());
        }
        var opt = store.loadLast().block();
        if (opt == null || opt.isEmpty()) return List.of();

        var checkpoint = opt.get();
        if (checkpoint.timestamp().toEpochMilli() < entry.createdAt()) return List.of();

        List<io.kairo.api.message.Msg> msgs = checkpoint.messages();
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        Map<String, Object> currentAssistant = null;
        List<Map<String, Object>> currentToolCalls = null;

        for (io.kairo.api.message.Msg msg : msgs) {
            String role = msg.role().name().toLowerCase();
            if ("system".equals(role)) continue;

            if ("user".equals(role)) {
                flushAssistant(result, currentAssistant, currentToolCalls);
                currentAssistant = null;
                currentToolCalls = null;

                Map<String, Object> userMsg = new java.util.LinkedHashMap<>();
                userMsg.put("id", msg.id());
                userMsg.put("role", "user");
                userMsg.put("content", extractText(msg));
                userMsg.put("toolCalls", List.of());
                userMsg.put("timestamp", msg.timestamp() != null
                        ? msg.timestamp().toEpochMilli() : System.currentTimeMillis());
                applyMetadata(userMsg, msg);
                result.add(userMsg);

            } else if ("assistant".equals(role)) {
                flushAssistant(result, currentAssistant, currentToolCalls);

                currentAssistant = new java.util.LinkedHashMap<>();
                currentAssistant.put("id", msg.id());
                currentAssistant.put("role", "assistant");
                currentAssistant.put("content", extractText(msg));
                currentAssistant.put("timestamp", msg.timestamp() != null
                        ? msg.timestamp().toEpochMilli() : System.currentTimeMillis());
                String thinking = extractThinking(msg);
                if (!thinking.isEmpty()) currentAssistant.put("thinking", thinking);

                currentToolCalls = new java.util.ArrayList<>();
                for (io.kairo.api.message.Content c : msg.contents()) {
                    if (c instanceof io.kairo.api.message.Content.ToolUseContent tu) {
                        Map<String, Object> tc = new java.util.LinkedHashMap<>();
                        tc.put("id", tu.toolId());
                        tc.put("toolName", tu.toolName());
                        tc.put("input", tu.input() != null ? tu.input() : Map.of());
                        tc.put("status", "done");
                        tc.put("requiresApproval", false);
                        currentToolCalls.add(tc);
                    }
                }
                applyMetadata(currentAssistant, msg);

            } else if ("tool".equals(role)) {
                if (currentToolCalls != null) {
                    for (io.kairo.api.message.Content c : msg.contents()) {
                        if (c instanceof io.kairo.api.message.Content.ToolResultContent tr) {
                            for (Map<String, Object> tc : currentToolCalls) {
                                if (tr.toolUseId() != null && tr.toolUseId().equals(tc.get("id"))) {
                                    tc.put("result", tr.content());
                                    if (tr.isError()) tc.put("isError", true);
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
        flushAssistant(result, currentAssistant, currentToolCalls);

        int start = Math.max(0, result.size() - 50);
        return result.subList(start, result.size());
    }

    private static void flushAssistant(List<Map<String, Object>> result,
                                        Map<String, Object> assistant,
                                        List<Map<String, Object>> toolCalls) {
        if (assistant == null) return;
        if (toolCalls != null && !toolCalls.isEmpty()) assistant.put("toolCalls", toolCalls);
        result.add(assistant);
    }

    private static String extractText(io.kairo.api.message.Msg msg) {
        StringBuilder sb = new StringBuilder();
        for (io.kairo.api.message.Content c : msg.contents()) {
            if (c instanceof io.kairo.api.message.Content.TextContent t) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(t.text());
            }
        }
        return sb.toString();
    }

    private static String extractThinking(io.kairo.api.message.Msg msg) {
        StringBuilder sb = new StringBuilder();
        for (io.kairo.api.message.Content c : msg.contents()) {
            if (c instanceof io.kairo.api.message.Content.ThinkingContent t) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(t.thinking());
            }
        }
        return sb.toString();
    }

    private static void applyMetadata(Map<String, Object> frontendMsg,
                                       io.kairo.api.message.Msg msg) {
        Map<String, Object> meta = msg.metadata();
        if (meta == null || meta.isEmpty()) return;

        Object kind = meta.get("kairo.kind");
        if ("compaction".equals(kind)) {
            frontendMsg.put("kind", "compaction");
            Map<String, Object> compactionMeta = new java.util.LinkedHashMap<>();
            Object bt = meta.get("kairo.compaction.beforeTokens");
            Object mt = meta.get("kairo.compaction.maxTokens");
            compactionMeta.put("beforeTokens", bt instanceof Number n ? n.intValue() : 0);
            compactionMeta.put("maxTokens", mt instanceof Number n ? n.intValue() : 0);
            frontendMsg.put("compactionMeta", compactionMeta);
        }
    }

    public Map<String, Map<String, Object>> getSessionToolStats(String sessionId) {
        return diagnosticsService.getSessionToolStats(sessionId);
    }

    /**
     * Return the configured default working directory, or null if not set.
     */
    public String getDefaultWorkingDir() {
        return defaultConfig != null ? defaultConfig.workingDir() : null;
    }

    public SessionIndexService getSessionIndexService() {
        return sessionIndexService;
    }

    /**
     * Return a list of active session summaries.
     */
    public List<SessionInfo> listSessions() {
        return registry.list().stream()
                .map(ctx -> {
                    SessionEntry e = ctx.entry();
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
                            ctx.running().get(),
                            e.workspaceId(),
                            isGit);
                })
                .toList();
    }

    private boolean isRunning(String sessionId) {
        SessionContext sctx = registry.get(sessionId);
        return sctx != null && sctx.running().get();
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
        String sid = entry.sessionId();
        SessionContext sctx = registry.get(sid);
        if (sctx != null && sctx.running().get()) {
            log.warn("Skip rebuild for running session sid={}", sid);
            return entry;
        }
    
        log.info(
                "Rebuilding session {} after credential update (model {} \u2192 {}, baseUrl {} \u2192 {})",
                sid, current.modelName(),
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
    
        Sinks.Many<AgentEvent> sink = sctx != null ? sctx.eventSink() : null;
        if (sink == null) {
            log.warn("Skip rebuild for session {} — no event sink", sid);
            return entry;
        }
        WebSocketApprovalHandler approvalHandler = entry.approvalHandler();
        AtomicReference<SessionPhase> phaseRef = sctx.phase();

        AgentEventBridgeHook bridgeHook = new AgentEventBridgeHook(
                sink, sid, approvalHandler.announcedToolCallIds(), rebuilt.workingDir(),
                sctx.progressTracker(), sctx.diagnostics());
        CompactionEventBridgeHook compactionBridge = new CompactionEventBridgeHook(sink, sid);
        // Plan-pending intercept hook MUST be on the rebuilt session too. Without it,
        // exit_plan_mode tool calls don't transition the session into PLAN_PENDING —
        // the user loses the second-pass approval gate and the agent free-runs after
        // any post-rebuild plan. Tracked as P1-5 follow-up to the chat-path audit.
        PlanPendingInterceptHook planHook = new PlanPendingInterceptHook(
                sink, sid, rebuilt.workingDir(), phaseRef,
                overview -> sctx.setPlanOverview(overview),
                () -> persistPhase(rebuilt.workingDir(), SessionPhase.PLAN_PENDING));

        SessionOptions opts = SessionOptions.empty()
                .asReplSession()
                .withApprovalHandler(approvalHandler)
                .withHooks(List.of(bridgeHook, compactionBridge, planHook));
        if (tracer != null) {
            // Same session-id stamping as createSession — see Langfuse rationale there.
            opts = opts.withTracer(
                    new io.kairo.core.tracing.SessionAwareTracer(
                            tracer, sid, entry.workspaceId()));
        }

        try {
            java.util.function.BiConsumer<String, String> rebuildChunkSink = (toolCallId, chunkText) -> {
                if (toolCallId != null) {
                    AgentRuntimeContext.emitSerialized(sink, AgentEvent.toolOutputChunk(sid, toolCallId, chunkText));
                }
            };
            Map<String, Object> rebuildExtraDeps = Map.of(
                    io.kairo.core.tool.ToolInvocationRunner.CHUNK_SINK_KEY, rebuildChunkSink);
            CodeAgentSession newSession = CodeAgentFactory.createSession(rebuilt, opts, rebuildExtraDeps);
            bridgeHook.setCostTracker(newSession.costTracker());
            Consumer<SessionPhase> persistPhaseFn = p -> persistPhase(rebuilt.workingDir(), p);
            AgentRuntimeContext newCtx = new AgentRuntimeContext(
                    sid, sink, sctx.running(), phaseRef, persistPhaseFn, concurrencyController);
    
            AgentSessionPayload newPayload = new AgentSessionPayload(rebuilt, newSession, newCtx);
            if (swarmCoordinator != null && triageGate != null
                    && teamManager != null && messageBus != null) {
                io.kairo.api.model.ModelProvider narratorModel =
                        CodeAgentFactory.buildModelProvider(rebuilt.apiKey(), rebuilt.baseUrl(), rebuilt.modelName());
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
            sctx.setEntry(newEntry);
            return newEntry;
        } catch (Exception e) {
            log.error("Failed to rebuild session {} with new credentials", sid, e);
            return entry;
        }
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

    private static void persistPhaseToFile(Path phaseFile, SessionPhase phase) {
        try {
            Files.createDirectories(phaseFile.getParent());
            Files.writeString(phaseFile, phase.name());
        } catch (IOException e) {
            // best-effort persistence
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
        SessionContext sctx = registry.get(sessionId);
        return sctx != null ? sctx.phase().get() : null;
    }

    @Override
    public void destroy() {
        // Registry and DiagnosticsService manage their own lifecycle via @PreDestroy / DisposableBean.
    }

    @Override
    public void afterPropertiesSet() {
        registry.setDestroyCallback(this::destroySession);
        registry.startReaper();
        sessionIndexService.migrateIfAbsent();
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
        sessionIndexService.reconcile(registry.sessionIds());
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
        SessionContext sctx = registry.get(sessionId);
        if (sctx == null) {
            return new SessionState(sessionId, false, false, List.of(), List.of());
        }
        SessionEntry entry = sctx.entry();
        boolean running = sctx.running().get();
        var approvalSnapshot = entry.approvalHandler().pendingApprovalsSnapshot();
        List<PendingApproval> pending = new java.util.ArrayList<>(approvalSnapshot.size());
        approvalSnapshot.forEach(
                (id, req) ->
                        pending.add(
                                new PendingApproval(
                                        id,
                                        req.toolName(),
                                        req.args() != null ? req.args() : Map.of())));

        ToolProgressTracker tracker = sctx.progressTracker();
        List<RunningTool> running_ = new java.util.ArrayList<>();
        tracker.snapshotIfStale(
                0L,
                inflight ->
                        running_.add(
                                new RunningTool(
                                        inflight.toolCallId(),
                                        inflight.toolName(),
                                        inflight.phase().name(),
                                        inflight.elapsedMs())));
        return new SessionState(sessionId, true, running, pending, running_);
    }

    public SessionDiagnostics getSessionDiagnostics(String sessionId) {
        return diagnosticsService.getSessionDiagnostics(sessionId);
    }

    public SessionMetricsSnapshot getSessionMetrics(String sessionId) {
        return diagnosticsService.getSessionMetrics(sessionId);
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

    public SessionDiagnosticsTracker diagnosticsTrackerFor(String sessionId) {
        return diagnosticsService.diagnosticsTrackerFor(sessionId);
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
