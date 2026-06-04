package io.kairo.code.service.agent;

import io.kairo.api.agent.Agent;
import io.kairo.api.event.KairoEventBus;
import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.model.ModelProvider;
import io.kairo.api.team.TeamConfig;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.api.team.MessageBus;
import io.kairo.api.team.TeamManager;
import io.kairo.code.core.team.SwarmCoordinator;
import io.kairo.code.service.AgentEvent;
import io.kairo.code.service.SessionPhase;
import io.kairo.code.service.concurrency.AgentConcurrencyException;
import io.kairo.code.service.concurrency.AgentSlot;
import io.kairo.code.service.team.TriageGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.time.Duration;

/**
 * Single-agent session payload wrapping a {@link CodeAgentSession} and its
 * {@link CodeAgentConfig}.
 *
 * <p>This is the default payload for "agent" mode sessions. It owns the full
 * message lifecycle: phase state machine, concurrency slot acquisition, agent
 * subscription, event emission via the shared sink, and plan-refinement queuing.
 *
 * <p>Prior to this refactor, all this logic lived in
 * {@link io.kairo.code.service.AgentService#sendMessage}. Now the payload is
 * self-sufficient and the service simply calls {@link #handleMessage(MessageRequest)}.
 */
public final class AgentSessionPayload implements SessionPayload {

    private static final Logger log = LoggerFactory.getLogger(AgentSessionPayload.class);

    /** Maximum pending refinement messages per session. */
    private static final int MAX_REFINEMENT_QUEUE_SIZE = 5;

    private static final Set<String> CODE_KEYWORDS = Set.of(
            "refactor", "implement", "test", "fix", "review", "debug",
            "build", "deploy", "api", "database", "migration", "endpoint",
            "compile", "lint", "code", "function", "class", "module",
            "commit", "merge", "branch", "ci", "cd", "pipeline",
            "重构", "实现", "测试", "修复", "审查", "调试",
            "编译", "部署", "接口", "数据库", "代码", "模块",
            "函数", "构建", "优化", "迁移", "架构"
    );

    private final CodeAgentConfig config;
    private final CodeAgentSession session;
    private volatile Agent agent;
    private final AgentRuntimeContext ctx;

    // ── Auto-escalation to expert team ──────────────────────────────────────────
    private volatile EscalationConfig escalationConfig;
    private volatile TeamSessionPayload escalatedDelegate;

    // ── Payload-owned state ──────────────────────────────────────────────────────
    private final AtomicBoolean refineProcessing = new AtomicBoolean(false);
    private final ConcurrentLinkedDeque<Msg> refineQueue = new ConcurrentLinkedDeque<>();
    private final AtomicReference<Disposable> currentRun = new AtomicReference<>();

    /**
     * Full constructor with runtime context for lifecycle ownership.
     */
    public AgentSessionPayload(CodeAgentConfig config, CodeAgentSession session,
                               AgentRuntimeContext ctx) {
        this.config = Objects.requireNonNull(config, "config");
        this.session = Objects.requireNonNull(session, "session");
        this.agent = session.agent();
        this.ctx = Objects.requireNonNull(ctx, "ctx");
    }

    /**
     * Inject escalation configuration so this payload can auto-upgrade to expert team
     * coordination when the task warrants it. Called by {@code AgentService} after construction.
     */
    public void setEscalationConfig(EscalationConfig cfg) {
        this.escalationConfig = cfg;
    }

    /**
     * Collaborators needed to construct a {@link TeamSessionPayload} with an Experts preset
     * on the fly. All fields are required; the record is constructed once by
     * {@code AgentService.createSession()} and injected via {@link #setEscalationConfig}.
     */
    public record EscalationConfig(
            SwarmCoordinator coordinator,
            TeamConfig teamConfig,
            TriageGate triageGate,
            KairoEventBus eventBus,
            TeamManager teamManager,
            MessageBus messageBus,
            CodeAgentConfig agentConfig,
            ModelProvider narratorModelProvider
    ) {}

    // ── SessionPayload contract ──────────────────────────────────────────────────

    /**
     * Handle an incoming user message with full lifecycle management.
     *
     * <p>Phase state machine:
     * <ul>
     *   <li>PLAN_PENDING → enqueue refinement</li>
     *   <li>FAILED_EXECUTION → reject (revert required)</li>
     *   <li>FAILED_PLANNING → transition to PLANNING and proceed</li>
     *   <li>IDLE / COMPLETED → transition to PLANNING and proceed</li>
     * </ul>
     *
     * <p>Main path: CAS runningState false→true, acquire concurrency slot,
     * subscribe to agent.call(), emit events via shared sink, cleanup in doFinally.
     */
    @Override
    public Flux<AgentEvent> handleMessage(MessageRequest request) {
        // ── Delegate to escalated expert team if already upgraded ──
        if (escalatedDelegate != null) {
            return escalatedDelegate.handleMessage(request);
        }

        String sessionId = ctx.sessionId();
        AtomicReference<SessionPhase> phaseRef = ctx.phaseRef();
        SessionPhase phase = phaseRef.get();

        // ── Auto-escalate to expert team for complex code tasks ──
        if (escalationConfig != null
                && (phase == SessionPhase.IDLE || phase == SessionPhase.COMPLETED)
                && escalationConfig.triageGate().shouldFanOut(request.text())
                && isCodeRelated(request.text())) {
            return escalate(request);
        }

        // ── FAILED_EXECUTION: reject until revert ──
        if (phase == SessionPhase.FAILED_EXECUTION) {
            return Flux.just(AgentEvent.error(sessionId,
                    "Previous execution interrupted, revert recommended",
                    "REVERT_REQUIRED"));
        }

        // ── PLAN_PENDING: route to refinement ──
        if (phase == SessionPhase.PLAN_PENDING) {
            Msg refinementMsg = buildUserMsg(request);
            return enqueueRefinement(refinementMsg);
        }

        // ── FAILED_PLANNING: allow retry — transition back to PLANNING ──
        if (phase == SessionPhase.FAILED_PLANNING) {
            phaseRef.set(SessionPhase.PLANNING);
            ctx.persistPhase().accept(SessionPhase.PLANNING);
        }

        // ── IDLE or COMPLETED: start a new planning cycle ──
        if (phase == SessionPhase.IDLE || phase == SessionPhase.COMPLETED) {
            phaseRef.set(SessionPhase.PLANNING);
            ctx.persistPhase().accept(SessionPhase.PLANNING);
        }

        // ── CAS running state ──
        AtomicBoolean running = ctx.runningState();
        if (!running.compareAndSet(false, true)) {
            return Flux.just(AgentEvent.error(sessionId,
                    "Session is already running", "SESSION_BUSY"));
        }

        Sinks.Many<AgentEvent> sink = ctx.sharedSink();

        // Build Msg and capture local agent reference
        Msg userMsg = buildUserMsg(request);
        final Agent localAgent = this.agent;

        // Emit thinking indicator via shared sink
        ctx.emit(AgentEvent.thinking(sessionId));

        // Acquire concurrency slot
        AgentSlot slot;
        try {
            slot = ctx.concurrency().acquire(sessionId);
        } catch (AgentConcurrencyException e) {
            ctx.emit(AgentEvent.error(sessionId, e.getMessage(), e.reason().name()));
            running.set(false);
            return sink.asFlux();
        }

        // Thinking delta consumer (for reasoning models)
        Consumer<String> thinkingConsumer = delta -> {
            if (delta != null && !delta.isEmpty()) {
                ctx.emit(AgentEvent.thinkingChunk(sessionId, delta));
            }
        };

        long startedAtMs = System.currentTimeMillis();

        // Subscribe to agent.call() — terminal events (DONE/ERROR) are emitted by
        // AgentEventBridgeHook.onSessionEnd(), not here.
        Disposable disposable = localAgent.call(userMsg)
                .contextWrite(reactor.util.context.Context.of(
                        io.kairo.core.agent.ReasoningPhase.THINKING_DELTA_KEY,
                        thinkingConsumer))
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(signal -> {
                    slot.close();
                    running.set(false);
                    currentRun.set(null);
                    long elapsedMs = System.currentTimeMillis() - startedAtMs;
                    log.info("agent.terminal session={} signal={} elapsedMs={}",
                            sessionId, signal, elapsedMs);
                    // Phase transition: if still PLANNING (hook didn't intercept) mark idle.
                    // If PLAN_PENDING was set by the hook, don't touch it.
                    phaseRef.compareAndSet(SessionPhase.PLANNING, SessionPhase.IDLE);
                    // Drain any queued refinement messages
                    drainRefineQueue();
                })
                .subscribe(
                        responseMsg -> {
                            // No-op: terminal emit happens in onSessionEnd hook.
                        },
                        err -> {
                            log.debug("agent.call error for session {}: {}",
                                    sessionId, err.getMessage());
                            // Transition to FAILED_PLANNING on error during planning
                            phaseRef.compareAndSet(SessionPhase.PLANNING, SessionPhase.FAILED_PLANNING);
                        }
                );

        currentRun.set(disposable);
        return sink.asFlux();
    }

    @Override
    public void stop() {
        if (escalatedDelegate != null) {
            escalatedDelegate.stop();
            return;
        }
        Disposable d = currentRun.getAndSet(null);
        if (d != null && !d.isDisposed()) {
            d.dispose();
        }
        ctx.runningState().set(false);
        refineQueue.clear();
        session.agent().interrupt();
    }

    @Override
    public boolean isRunning() {
        if (escalatedDelegate != null) {
            return escalatedDelegate.isRunning();
        }
        return ctx.runningState().get();
    }

    @Override
    public SessionPhase getState() {
        if (escalatedDelegate != null) {
            return escalatedDelegate.getState();
        }
        return ctx.phaseRef().get();
    }

    @Override
    public Flux<AgentEvent> confirmBuild() {
        if (escalatedDelegate != null) {
            return escalatedDelegate.confirmBuild();
        }
        return Flux.empty();
    }

    // ── Agent rebuild support ────────────────────────────────────────────────────

    /**
     * Replace the live agent with a freshly built one (e.g. after credential update).
     *
     * @param fresh the new agent instance
     * @return snapshot of the new agent's identity
     * @throws IllegalStateException if the session is currently running
     */
    public AgentSnapshot rebuildAgent(Agent fresh) {
        if (ctx.runningState().get()) {
            throw new IllegalStateException("Cannot rebuild agent while session is running");
        }
        this.agent = fresh;
        return new AgentSnapshot(fresh.name(), config.modelName());
    }

    /**
     * Lightweight snapshot of the agent identity after a rebuild.
     */
    public record AgentSnapshot(String agentName, String model) {}

    // ── Plan refinement queue ────────────────────────────────────────────────────

    /**
     * Enqueue a refinement message while in PLAN_PENDING state.
     * Messages are processed serially (one LLM call at a time).
     */
    private Flux<AgentEvent> enqueueRefinement(Msg userMsg) {
        String sessionId = ctx.sessionId();
        Sinks.Many<AgentEvent> sink = ctx.sharedSink();

        // Bounded queue: drop-newest when full
        if (refineQueue.size() >= MAX_REFINEMENT_QUEUE_SIZE) {
            log.warn("Plan refinement queue full for session {} — dropping newest message",
                    sessionId);
            return Flux.just(AgentEvent.error(sessionId,
                    "Too many refinement requests pending (max " + MAX_REFINEMENT_QUEUE_SIZE + ")",
                    "REFINEMENT_QUEUE_FULL"));
        }
        refineQueue.addLast(userMsg);

        // Try to acquire the refinement lock (serialized LLM calls)
        if (!refineProcessing.compareAndSet(false, true)) {
            // Already processing a refinement — message is queued and will be picked up
            return sink.asFlux();
        }

        // Process the queue on boundedElastic
        processRefinementQueue(sink);
        return sink.asFlux();
    }

    /**
     * Drain the refinement queue one message at a time. Runs on boundedElastic.
     */
    private void processRefinementQueue(Sinks.Many<AgentEvent> sink) {
        String sessionId = ctx.sessionId();

        reactor.core.publisher.Mono.fromRunnable(() -> {
            try {
                while (true) {
                    Msg msg = refineQueue.pollFirst();
                    if (msg == null) break;

                    // Check phase — if no longer PLAN_PENDING, stop processing
                    if (ctx.phaseRef().get() != SessionPhase.PLAN_PENDING) {
                        break;
                    }

                    ctx.emit(AgentEvent.thinking(sessionId));

                    // Acquire concurrency slot before calling agent
                    AgentSlot slot = null;
                    try {
                        slot = ctx.concurrency().acquire(sessionId);
                    } catch (AgentConcurrencyException e) {
                        ctx.emit(AgentEvent.error(sessionId, e.getMessage(), e.reason().name()));
                        break;
                    }

                    // Call the agent for refinement (still in plan mode context)
                    try {
                        this.agent.call(msg).block(Duration.ofMinutes(5));
                    } catch (Exception e) {
                        log.warn("Plan refinement call failed for session {}: {}",
                                sessionId, e.getMessage());
                        ctx.emit(AgentEvent.error(sessionId,
                                "Plan refinement failed: " + e.getMessage(), "REFINEMENT_ERROR"));
                    } finally {
                        slot.close();
                    }
                }
            } finally {
                refineProcessing.set(false);
            }
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    /**
     * Called from doFinally to drain any queued refinement messages that arrived
     * while the main run was still in flight (race window where phase transitions
     * to PLAN_PENDING just before doFinally fires).
     */
    private void drainRefineQueue() {
        if (ctx.phaseRef().get() != SessionPhase.PLAN_PENDING) {
            return;
        }
        if (refineQueue.isEmpty()) {
            return;
        }
        if (!refineProcessing.compareAndSet(false, true)) {
            return; // already being processed
        }
        processRefinementQueue(ctx.sharedSink());
    }

    // ── Accessors for backward compatibility with AgentService internals ──

    /** The agent configuration for this session. */
    public CodeAgentConfig config() {
        return config;
    }

    /** The underlying session (agent + tool runtime state). */
    public CodeAgentSession session() {
        return session;
    }

    // ── Auto-escalation ───────────────────────────────────────────────────────

    /**
     * Construct an {@link TeamSessionPayload} with the Experts preset on the fly and delegate
     * the current message to it. This is a one-way transition: once escalated, all subsequent
     * messages go through the delegate.
     */
    private Flux<AgentEvent> escalate(MessageRequest request) {
        String sessionId = ctx.sessionId();
        log.info("Auto-escalating session {} to expert team", sessionId);

        Sinks.Many<AgentEvent> sink = ctx.sharedSink();
        ctx.emit(AgentEvent.modeEscalated(sessionId,
                "Task escalated to expert team for collaborative execution"));

        TeamSessionPayload.ExpertsPresetConfig presetCfg =
                new TeamSessionPayload.ExpertsPresetConfig(
                        escalationConfig.coordinator(),
                        escalationConfig.teamConfig(),
                        escalationConfig.triageGate(),
                        this,
                        TeamSessionPayload.NarratorSettings.defaults(),
                        escalationConfig.eventBus(),
                        escalationConfig.narratorModelProvider());

        this.escalatedDelegate = new TeamSessionPayload(
                config, session, ctx,
                escalationConfig.teamManager(),
                escalationConfig.messageBus(),
                presetCfg);

        return escalatedDelegate.handleMessage(request);
    }

    private boolean isCodeRelated(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String kw : CODE_KEYWORDS) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }

    /** @return the escalated delegate, or {@code null} if still in single-agent mode. */
    public TeamSessionPayload escalatedDelegate() {
        return escalatedDelegate;
    }

    /**
     * Build the user {@link Msg} from a {@link MessageRequest}.
     * Extracted here so AgentService.sendMessage can use it during the transition period.
     */
    public Msg buildUserMsg(MessageRequest request) {
        if (request.hasPrebuiltMsg()) {
            return request.prebuiltMsg();
        }
        if (request.hasImage()) {
            byte[] bytes = Base64.getDecoder().decode(request.imageData());
            return Msg.builder()
                    .role(MsgRole.USER)
                    .addContent(new Content.TextContent(request.text()))
                    .addContent(new Content.ImageContent(null, request.imageMediaType(), bytes))
                    .build();
        }
        return Msg.of(MsgRole.USER, request.text());
    }
}
