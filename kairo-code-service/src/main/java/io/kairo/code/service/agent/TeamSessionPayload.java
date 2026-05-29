package io.kairo.code.service.agent;

import io.kairo.api.agent.Agent;
import io.kairo.api.event.KairoEvent;
import io.kairo.api.event.KairoEventBus;
import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.model.ModelConfig;
import io.kairo.api.model.ModelProvider;
import io.kairo.api.model.ModelResponse;
import io.kairo.api.team.TeamConfig;
import io.kairo.api.team.TeamEvent;
import io.kairo.api.team.TeamEventType;
import io.kairo.api.team.TeamResult;
import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolDefinition;
import io.kairo.api.tool.ToolSideEffect;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.code.core.team.MessageBus;
import io.kairo.code.core.team.SwarmCoordinator;
import io.kairo.code.core.team.TeamManager;
import io.kairo.code.service.AgentEvent;
import io.kairo.code.service.SessionPhase;
import io.kairo.code.service.agent.tools.NoNarrationTool;
import io.kairo.code.service.concurrency.AgentConcurrencyException;
import io.kairo.code.service.concurrency.AgentSlot;
import io.kairo.code.service.team.TriageGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Team-mode session payload — Claude-style {@code TeamCreate} live multi-agent collaboration, plus
 * the M-Experts-Upgrade preset that runs upstream {@link SwarmCoordinator} workers behind a
 * Team-Lead chat session.
 *
 * <p>Default Team mode (5-arg constructor, {@code preset == null}) is functionally identical to
 * the original M-Team payload: a long-lived orchestrator session whose tool registry has been
 * augmented with {@code team_create} / {@code send_message} / {@code team_delete}, with a
 * background peer-message poller draining the in-process {@link MessageBus} mailbox onto the
 * shared sink as {@link AgentEvent#peerMessage} events.
 *
 * <p>Experts preset (6-arg constructor, {@code preset != null}) layers on top:
 * <ul>
 *   <li>plan-gate state machine (PLAN_PENDING / EXECUTING / FAILED_EXECUTION) lifted from the now
 *       deleted {@code ExpertsSessionPayload};
 *   <li>{@link TriageGate} fallback that demotes short messages back to a single-agent payload;
 *   <li>{@link SwarmCoordinator}-backed plan / confirm / execute flow;
 *   <li>swarm-event bridge that subscribes to {@link KairoEventBus#DOMAIN_TEAM} and projects every
 *       lifecycle step into a {@code PEER_MESSAGE} on the sink (the same UX channel M-Team uses);
 *   <li>active {@link NarratorDispatcher} that batches projected events and triggers a Team-Lead
 *       {@code agent.call} per debounced batch — Team Lead either narrates the batch as a
 *       {@code TEXT_CHUNK} or invokes the {@code no_narration} tool to stay silent.
 * </ul>
 *
 * <p><b>Mode isolation</b> (load-bearing — see M-Experts-Upgrade plan): every preset-only field and
 * branch is gated behind {@code preset != null}. The 5-arg constructor delegates to the 6-arg one
 * with {@code presetOrNull=null}, preserving the public surface used by every default Team call
 * site in {@code AgentService}. {@code AgentSessionPayload} is untouched. The existing
 * {@code TeamSessionPayloadTest} and {@code AgentServiceTeamModeTest} cases must pass without
 * modification — they are the regression gate.
 */
public final class TeamSessionPayload implements SessionPayload {

    private static final Logger log = LoggerFactory.getLogger(TeamSessionPayload.class);

    /** Cadence for draining the peer-message mailbox. 500ms keeps perceived latency low. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    /**
     * High-frequency swarm events that would flood both the user chat and the narrator's prompt
     * context if surfaced. Same filter set TeamEventBridge marks as droppable.
     */
    private static final Set<TeamEventType> HIGH_FREQ_TYPES = EnumSet.of(
            TeamEventType.STEP_THINKING,
            TeamEventType.STEP_TOOL_CALL,
            TeamEventType.STEP_ARTIFACT_CHUNK);

    private final CodeAgentConfig config;
    private final CodeAgentSession session;
    private volatile Agent agent;
    private final AgentRuntimeContext ctx;
    private final TeamManager teamManager;
    private final MessageBus messageBus;

    // ── Experts preset (null in default Team mode) ──
    private final ExpertsPresetConfig preset;
    private volatile String pendingTeamId;
    private volatile Map<String, Object> lastPlanReadyAttributes;
    private final Disposable swarmEventBridge;
    private final NarratorDispatcher narrator;

    private final AtomicReference<Disposable> currentRun = new AtomicReference<>();
    private final AtomicReference<Disposable> peerPoller = new AtomicReference<>();
    private final AtomicBoolean doneEmitted = new AtomicBoolean(false);

    /**
     * Default Team-mode constructor. Preserved bit-for-bit for the M-Team call sites at
     * {@code AgentService} case {@code "team"} — delegates to the 6-arg constructor with a null
     * preset. Existing {@code TeamSessionPayloadTest} cases must continue to compile against this
     * exact signature.
     */
    public TeamSessionPayload(CodeAgentConfig config,
                              CodeAgentSession session,
                              AgentRuntimeContext ctx,
                              TeamManager teamManager,
                              MessageBus messageBus) {
        this(config, session, ctx, teamManager, messageBus, null);
    }

    /**
     * Full constructor used by experts-mode sessions. Pass {@code presetOrNull = null} to get
     * default Team mode behavior — semantically identical to the 5-arg form above.
     */
    public TeamSessionPayload(CodeAgentConfig config,
                              CodeAgentSession session,
                              AgentRuntimeContext ctx,
                              TeamManager teamManager,
                              MessageBus messageBus,
                              ExpertsPresetConfig presetOrNull) {
        this.config = Objects.requireNonNull(config, "config");
        this.session = Objects.requireNonNull(session, "session");
        this.agent = session.agent();
        this.ctx = Objects.requireNonNull(ctx, "ctx");
        this.teamManager = Objects.requireNonNull(teamManager, "teamManager");
        this.messageBus = Objects.requireNonNull(messageBus, "messageBus");
        this.preset = presetOrNull;
        if (preset != null) {
            NarratorSettings narratorSettings = preset.narrator();
            this.narrator =
                    (narratorSettings != null && narratorSettings.enabled())
                            ? new NarratorDispatcher(narratorSettings)
                            : null;
            this.swarmEventBridge = subscribeSwarmEvents(preset.eventBus());
        } else {
            this.narrator = null;
            this.swarmEventBridge = null;
        }
        startPeerPoller();
    }

    // ── SessionPayload contract ──────────────────────────────────────────────────

    @Override
    public Flux<AgentEvent> handleMessage(MessageRequest request) {
        String sessionId = ctx.sessionId();
        AtomicReference<SessionPhase> phaseRef = ctx.phaseRef();
        SessionPhase phase = phaseRef.get();

        // ── Experts preset: plan-gate state machine ─────────────────────────────
        if (preset != null) {
            switch (phase) {
                case IDLE, FAILED_PLANNING -> {
                    if (!preset.triageGate().shouldFanOut(request.text())) {
                        AgentEvent demoted = AgentEvent.modeDemoted(sessionId,
                                "Message too brief for experts mode, single-agent fallback");
                        return Flux.concat(
                                Flux.just(demoted),
                                preset.demotionFallback().handleMessage(request));
                    }
                    if (narrator != null) {
                        narrator.suspend();
                    }
                    return startPlanOnly(request.text());
                }
                case PLAN_PENDING -> {
                    if (narrator != null) {
                        narrator.suspend();
                    }
                    return startPlanOnly(request.text());
                }
                case PLANNING -> {
                    return Flux.just(AgentEvent.error(sessionId,
                            "Planning is already in progress. Please wait for the plan to be generated.",
                            "SESSION_BUSY"));
                }
                case EXECUTING -> {
                    return Flux.just(AgentEvent.error(sessionId,
                            "Expert team is currently executing. Please wait for completion.",
                            "SESSION_BUSY"));
                }
                case COMPLETED -> {
                    phaseRef.set(SessionPhase.IDLE);
                    ctx.persistPhase().accept(SessionPhase.IDLE);
                    return handleMessage(request);
                }
                case FAILED_EXECUTION -> {
                    return Flux.just(AgentEvent.error(sessionId,
                            "Execution failed. Please revert the workspace before retrying.",
                            "REVERT_REQUIRED"));
                }
                default -> {
                    // PLANNING already handled above; this is unreachable but keep the compiler happy.
                    return Flux.just(AgentEvent.error(sessionId,
                            "Unexpected session phase: " + phase, "INTERNAL_ERROR"));
                }
            }
        }

        // ── Default Team mode: chat-loop subset (unchanged from M-Team) ─────────
        // Team mode never enters PLAN_PENDING / FAILED_EXECUTION — those are Experts-only.
        // Mirror AgentSessionPayload's idle/retry behaviour for the rest.
        if (phase == SessionPhase.FAILED_PLANNING) {
            phaseRef.set(SessionPhase.PLANNING);
            ctx.persistPhase().accept(SessionPhase.PLANNING);
        }
        if (phase == SessionPhase.IDLE || phase == SessionPhase.COMPLETED) {
            phaseRef.set(SessionPhase.PLANNING);
            ctx.persistPhase().accept(SessionPhase.PLANNING);
        }

        AtomicBoolean running = ctx.runningState();
        if (!running.compareAndSet(false, true)) {
            return Flux.just(AgentEvent.error(sessionId,
                    "Session is already running", "SESSION_BUSY"));
        }

        Sinks.Many<AgentEvent> sink = ctx.sharedSink();
        Msg userMsg = buildUserMsg(request);
        final Agent localAgent = this.agent;

        ctx.emit(AgentEvent.thinking(sessionId));

        AgentSlot slot;
        try {
            slot = ctx.concurrency().acquire(sessionId);
        } catch (AgentConcurrencyException e) {
            ctx.emit(AgentEvent.error(sessionId, e.getMessage(), e.reason().name()));
            running.set(false);
            return sink.asFlux();
        }

        Consumer<String> thinkingConsumer = delta -> {
            if (delta != null && !delta.isEmpty()) {
                ctx.emit(AgentEvent.thinkingChunk(sessionId, delta));
            }
        };

        long startedAtMs = System.currentTimeMillis();

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
                    log.info("team.terminal session={} signal={} elapsedMs={}",
                            sessionId, signal, elapsedMs);
                    phaseRef.compareAndSet(SessionPhase.PLANNING, SessionPhase.IDLE);
                })
                .subscribe(
                        responseMsg -> {
                            // Terminal emit happens in AgentEventBridgeHook.onSessionEnd.
                        },
                        err -> {
                            log.debug("team.call error for session {}: {}",
                                    sessionId, err.getMessage());
                            phaseRef.compareAndSet(SessionPhase.PLANNING, SessionPhase.FAILED_PLANNING);
                        });

        currentRun.set(disposable);
        return sink.asFlux();
    }

    @Override
    public Flux<AgentEvent> confirmBuild() {
        if (preset == null) {
            return Flux.empty();
        }

        String sessionId = ctx.sessionId();
        AtomicReference<SessionPhase> phaseRef = ctx.phaseRef();

        if (!phaseRef.compareAndSet(SessionPhase.PLAN_PENDING, SessionPhase.EXECUTING)) {
            return Flux.just(AgentEvent.error(sessionId,
                    "No plan is pending confirmation. Current phase: " + phaseRef.get(),
                    "INVALID_STATE"));
        }
        ctx.persistPhase().accept(SessionPhase.EXECUTING);

        if (narrator != null) {
            narrator.resume();
        }

        String teamId = pendingTeamId;
        if (teamId == null) {
            phaseRef.set(SessionPhase.PLAN_PENDING);
            ctx.persistPhase().accept(SessionPhase.PLAN_PENDING);
            return Flux.just(AgentEvent.error(sessionId,
                    "No pending team ID found.", "INTERNAL_ERROR"));
        }

        if (!ctx.runningState().compareAndSet(false, true)) {
            phaseRef.set(SessionPhase.PLAN_PENDING);
            ctx.persistPhase().accept(SessionPhase.PLAN_PENDING);
            return Flux.just(AgentEvent.error(sessionId,
                    "Session is already running", "SESSION_BUSY"));
        }

        // Emit to the shared session sink (see startPlanOnly) — never the throwaway local
        // sink, or execution events never reach the WebSocket. Don't complete it; it's the
        // persistent session channel. emitDoneOnce already signals terminal state to the UI.
        Sinks.Many<AgentEvent> sink = ctx.sharedSink();

        // Bind the session workspace so worker agents resolve tool paths correctly
        if (config.workingDir() != null && !config.workingDir().isBlank()) {
            preset.coordinator().setActiveWorkspace(new SessionWorkspace(config.workingDir()));
        }

        Disposable disposable = preset.coordinator().confirmAndExecute(teamId)
                .doOnSubscribe(sub -> log.debug("confirmAndExecute subscribed (session={})", sessionId))
                .doOnSuccess(result -> {
                    log.debug("confirmAndExecute completed (session={}), result={}",
                            sessionId, result != null ? result.status() : "null");
                    ctx.runningState().set(false);
                    phaseRef.set(SessionPhase.COMPLETED);
                    ctx.persistPhase().accept(SessionPhase.COMPLETED);
                    emitDoneOnce(sessionId);
                })
                .doOnError(err -> {
                    ctx.runningState().set(false);
                    phaseRef.set(SessionPhase.FAILED_EXECUTION);
                    ctx.persistPhase().accept(SessionPhase.FAILED_EXECUTION);
                    log.warn("Expert team execution failed (session={}): {}",
                            sessionId, err.getMessage());
                    ctx.emit(AgentEvent.error(sessionId,
                            "Expert team execution failed: " + err.getMessage(),
                            "TEAM_EXECUTION_ERROR"));
                })
                .doOnCancel(() -> {
                    ctx.runningState().set(false);
                    phaseRef.set(SessionPhase.FAILED_EXECUTION);
                    ctx.persistPhase().accept(SessionPhase.FAILED_EXECUTION);
                })
                .subscribe();

        currentRun.set(disposable);
        return sink.asFlux();
    }

    @Override
    public void stop() {
        if (narrator != null) {
            narrator.dispose();
        }
        if (swarmEventBridge != null && !swarmEventBridge.isDisposed()) {
            swarmEventBridge.dispose();
        }
        Disposable d = currentRun.getAndSet(null);
        if (d != null && !d.isDisposed()) {
            d.dispose();
        }
        Disposable p = peerPoller.getAndSet(null);
        if (p != null && !p.isDisposed()) {
            p.dispose();
        }
        ctx.runningState().set(false);
        session.agent().interrupt();
        log.info("TeamSessionPayload stopped (session={}, preset={})",
                ctx.sessionId(), preset != null);
    }

    @Override
    public boolean isRunning() {
        return ctx.runningState().get();
    }

    @Override
    public SessionPhase getState() {
        return ctx.phaseRef().get();
    }

    // ── Plan-only flow (experts preset) ──────────────────────────────────────────

    private Flux<AgentEvent> startPlanOnly(String goal) {
        String sessionId = ctx.sessionId();
        ctx.phaseRef().set(SessionPhase.PLANNING);
        ctx.persistPhase().accept(SessionPhase.PLANNING);
        ctx.runningState().set(true);

        // Eagerly subscribe to the bus for PLAN_READY BEFORE starting the coordinator.
        // The coordinator's deterministic planner runs synchronously and publishes
        // PLAN_READY inline. With multicast semantics, the event is only delivered to
        // active subscribers — so the bus listener must be subscribed first.
        // .cache() makes this a hot Mono: subscription happens now, not when zip subscribes.
        Mono<Map<String, Object>> planAttrsMono = preset.eventBus() != null
                ? preset.eventBus().subscribe(KairoEvent.DOMAIN_TEAM)
                        .filter(e -> e.payload() instanceof TeamEvent te
                                && te.type() == TeamEventType.PLAN_READY)
                        .next()
                        .map(e -> ((TeamEvent) e.payload()).attributes())
                        .defaultIfEmpty(Map.of())
                        .timeout(java.time.Duration.ofMinutes(5), Mono.just(Map.of()))
                        .cache()
                : Mono.just(Map.of());

        // Force the bus subscription to start NOW (before the coordinator publishes).
        planAttrsMono.subscribe();

        // Emit to the shared session sink — the WebSocket broadcasts ONLY from this
        // channel (AgentService.sessionEvents). A private sink would be silently dropped,
        // which leaves the UI "silent" after the escalation notice. Never complete it:
        // the shared sink is the persistent session channel reused by confirmAndExecute
        // and any follow-up messages.
        Sinks.Many<AgentEvent> sink = ctx.sharedSink();
        ctx.emit(AgentEvent.thinking(sessionId));

        Mono<TeamResult> resultMono = preset.coordinator()
                .startExpertTeam(goal, preset.teamConfig(), List.<String>of(), true);

        Disposable disposable = Mono.zip(resultMono, planAttrsMono)
                .doOnSuccess(tuple -> {
                    ctx.runningState().set(false);
                    pendingTeamId = preset.coordinator().lastTeamId();
                    ctx.phaseRef().set(SessionPhase.PLAN_PENDING);
                    ctx.persistPhase().accept(SessionPhase.PLAN_PENDING);
                    TeamResult result = tuple.getT1();
                    Map<String, Object> planAttrs = tuple.getT2();
                    lastPlanReadyAttributes = planAttrs.isEmpty() ? null : planAttrs;
                    Map<String, Object> meta = buildPlanReadyMeta(pendingTeamId,
                            planAttrs.isEmpty() ? null : planAttrs);
                    ctx.emit(AgentEvent.planReady(sessionId,
                            extractPlanSummary(result), meta));
                })
                .doOnError(err -> {
                    ctx.runningState().set(false);
                    ctx.phaseRef().set(SessionPhase.FAILED_PLANNING);
                    ctx.persistPhase().accept(SessionPhase.FAILED_PLANNING);
                    log.warn("Expert team planning failed (session={}): {}",
                            sessionId, err.getMessage());
                    ctx.emit(AgentEvent.error(sessionId,
                            "Planning failed: " + err.getMessage(),
                            "PLANNING_ERROR"));
                })
                .doOnCancel(() -> {
                    ctx.runningState().set(false);
                    ctx.phaseRef().set(SessionPhase.FAILED_PLANNING);
                    ctx.persistPhase().accept(SessionPhase.FAILED_PLANNING);
                })
                .subscribe();

        currentRun.set(disposable);
        return sink.asFlux();
    }

    private static String extractPlanSummary(TeamResult result) {
        return result.finalOutput().orElse("Plan generated successfully");
    }

    private static Map<String, Object> buildPlanReadyMeta(String teamId,
                                                           Map<String, Object> planAttrs) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("teamId", teamId);
        if (planAttrs != null) {
            meta.put("steps", planAttrs.get("steps"));
            meta.put("mode", planAttrs.get("mode"));
            meta.put("planId", planAttrs.get("planId"));
            meta.put("totalSteps", planAttrs.get("totalSteps"));
        }
        return meta;
    }

    private void emitDoneOnce(String sessionId) {
        if (doneEmitted.compareAndSet(false, true)) {
            ctx.emit(AgentEvent.done(sessionId, 0, 0));
        }
    }

    // ── Swarm-event bridge (experts preset) ──────────────────────────────────────

    /**
     * Subscribe to {@link KairoEventBus#DOMAIN_TEAM} and project lifecycle step events into the
     * shared sink as {@code PEER_MESSAGE} events — same channel M-Team uses for live peer chatter,
     * so the frontend rendering path is bit-identical. When the narrator is enabled the same
     * projected event is also pushed onto the dispatcher's batch queue.
     */
    private Disposable subscribeSwarmEvents(KairoEventBus eventBus) {
        if (eventBus == null) {
            log.warn("Experts preset wired without KairoEventBus — swarm events will not surface "
                    + "as PEER_MESSAGE (session={})", ctx.sessionId());
            return null;
        }
        String sessionId = ctx.sessionId();
        Sinks.Many<AgentEvent> sink = ctx.sharedSink();
        return eventBus.subscribe(KairoEvent.DOMAIN_TEAM)
                .publishOn(Schedulers.boundedElastic())
                .subscribe(envelope -> {
                    Object payload = envelope.payload();
                    if (!(payload instanceof TeamEvent te)) {
                        return;
                    }
                    String activeTeamId = pendingTeamId;
                    if (activeTeamId == null || !activeTeamId.equals(te.teamId())) {
                        return;
                    }
                    if (HIGH_FREQ_TYPES.contains(te.type())) {
                        return;
                    }
                    String role = roleAttribute(te);
                    String content = summarize(te);
                    String fromTag = role != null ? "expert:" + role : "expert";
                    String eventId = envelope.eventId();
                    ctx.emit(AgentEvent.peerMessage(
                            sessionId, fromTag, content, eventId));
                    if (narrator != null) {
                        narrator.enqueue(new PeerEventSummary(
                                fromTag, content, eventId, envelope.timestamp().toEpochMilli()));
                    }
                    // Safety net: if TEAM_COMPLETED arrives but confirmAndExecute() Mono
                    // hasn't signalled onSuccess yet, emit AGENT_DONE so the frontend
                    // transitions running=false and doesn't show a stalled session.
                    if (te.type() == TeamEventType.TEAM_COMPLETED) {
                        ctx.runningState().set(false);
                        ctx.phaseRef().set(SessionPhase.COMPLETED);
                        ctx.persistPhase().accept(SessionPhase.COMPLETED);
                        emitDoneOnce(sessionId);
                    }
                }, err -> log.warn(
                        "swarm-event bridge subscription error (session={}): {}",
                        sessionId, err.getMessage()));
    }

    private static String roleAttribute(TeamEvent te) {
        Object role = te.attributes().get("role");
        if (role == null) {
            role = te.attributes().get("expertRole");
        }
        return role != null ? role.toString() : null;
    }

    private static String summarize(TeamEvent te) {
        Map<String, Object> attrs = te.attributes();
        Object summary = attrs.get("summary");
        if (summary != null) return summary.toString();
        Object output = attrs.get("output");
        if (output != null) return output.toString();

        String stepId = attrs.get("stepId") != null ? attrs.get("stepId").toString() : null;
        switch (te.type()) {
            case STEP_ASSIGNED:
                String roleId = attrs.get("roleId") != null
                        ? attrs.get("roleId").toString() : "expert";
                return "Expert **" + roleId + "** started working"
                        + (stepId != null ? " on step " + stepId : "");
            case EVALUATION_STARTED:
                return "Evaluating output" + (stepId != null ? " for step " + stepId : "") + "…";
            case EVALUATION_RESULT:
                Object verdict = attrs.get("verdict");
                Object score = attrs.get("score");
                Object feedback = attrs.get("feedback");
                StringBuilder sb = new StringBuilder("Evaluation: ");
                if (verdict != null) sb.append("**").append(verdict).append("**");
                if (score != null) sb.append(" (score ").append(score).append(")");
                if (feedback != null && !feedback.toString().isBlank()) {
                    sb.append("\n").append(feedback);
                }
                return sb.toString();
            case STEP_COMPLETED:
                Object attempts = attrs.get("attempts");
                return "Step " + (stepId != null ? stepId : "") + " completed"
                        + (attempts != null ? " in " + attempts + " attempt(s)" : "");
            case TEAM_COMPLETED:
                return "All experts finished — team execution complete.";
            case TEAM_STARTED:
                return "Expert team started.";
            default:
                return te.type().name() + (stepId != null ? " step=" + stepId : "");
        }
    }

    // ── Peer-message poller ──────────────────────────────────────────────────────

    /**
     * Spawn a background poller that drains the session's MessageBus mailbox every
     * {@link #POLL_INTERVAL} and projects each {@code TeamMessage} onto the shared sink as a
     * {@code PEER_MESSAGE} event. Disposed in {@link #stop()}.
     */
    private void startPeerPoller() {
        String sessionId = ctx.sessionId();
        Sinks.Many<AgentEvent> sink = ctx.sharedSink();
        Disposable d = Flux.interval(POLL_INTERVAL, Schedulers.boundedElastic())
                .doOnNext(tick -> {
                    try {
                        List<MessageBus.TeamMessage> msgs = messageBus.poll(sessionId);
                        for (MessageBus.TeamMessage msg : msgs) {
                            ctx.emit(AgentEvent.peerMessage(
                                    sessionId, msg.fromSessionId(),
                                    msg.content(), msg.messageId()));
                        }
                    } catch (Exception e) {
                        log.warn("Peer-message poll failed for session {}: {}",
                                sessionId, e.getMessage());
                    }
                })
                .subscribe();
        peerPoller.set(d);
    }

    // ── Agent rebuild support ────────────────────────────────────────────────────

    /**
     * Replace the live agent with a freshly built one (e.g. after credential update).
     */
    public AgentSessionPayload.AgentSnapshot rebuildAgent(Agent fresh) {
        if (ctx.runningState().get()) {
            throw new IllegalStateException("Cannot rebuild agent while session is running");
        }
        this.agent = fresh;
        return new AgentSessionPayload.AgentSnapshot(fresh.name(), config.modelName());
    }

    // ── Accessors ────────────────────────────────────────────────────────────────

    public CodeAgentConfig config() {
        return config;
    }

    public CodeAgentSession session() {
        return session;
    }

    public TeamManager teamManager() {
        return teamManager;
    }

    public MessageBus messageBus() {
        return messageBus;
    }

    /** Non-null only for experts-mode sessions. */
    public ExpertsPresetConfig preset() {
        return preset;
    }

    /** Non-null after plan-only completes (experts mode). */
    public String pendingTeamId() {
        return pendingTeamId;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    public Msg buildUserMsg(MessageRequest request) {
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

    // ── Preset config records ────────────────────────────────────────────────────

    /**
     * Bundle of preset-only collaborators wired into experts-mode sessions. Defines the seam
     * between the shared substrate (this payload's chat/peer-poller behavior) and the upstream
     * Swarm execution path.
     *
     * @param coordinator the {@link SwarmCoordinator} that owns plan/confirm/execute
     * @param teamConfig the {@link TeamConfig} passed to {@link SwarmCoordinator#startExpertTeam}
     * @param triageGate decides whether a user message is substantial enough for fan-out
     * @param demotionFallback single-agent payload invoked when triage rejects the message
     * @param narrator narrator settings — pass {@link NarratorSettings#disabled()} to skip
     * @param eventBus bus carrying swarm lifecycle events for the bridge; may be {@code null}
     * @param narratorModelProvider model provider for narrator calls; restricted to no_narration
     *     tool only (avoids unintended tool execution from the full session agent)
     */
    public record ExpertsPresetConfig(
            SwarmCoordinator coordinator,
            TeamConfig teamConfig,
            TriageGate triageGate,
            AgentSessionPayload demotionFallback,
            NarratorSettings narrator,
            KairoEventBus eventBus,
            ModelProvider narratorModelProvider) {

        public ExpertsPresetConfig {
            Objects.requireNonNull(coordinator, "coordinator");
            Objects.requireNonNull(teamConfig, "teamConfig");
            Objects.requireNonNull(triageGate, "triageGate");
            Objects.requireNonNull(demotionFallback, "demotionFallback");
            Objects.requireNonNull(narrator, "narrator");
            // eventBus intentionally nullable — the bridge degrades to a warning + no projection.
            // narratorModelProvider intentionally nullable — narrator disabled or not yet wired.
        }

        /** Backward-compatible 6-arg constructor for existing call sites (narrator disabled). */
        public ExpertsPresetConfig(
                SwarmCoordinator coordinator,
                TeamConfig teamConfig,
                TriageGate triageGate,
                AgentSessionPayload demotionFallback,
                NarratorSettings narrator,
                KairoEventBus eventBus) {
            this(coordinator, teamConfig, triageGate, demotionFallback, narrator, eventBus, null);
        }
    }

    /**
     * Tunables for the active narrator dispatcher.
     *
     * @param enabled master kill-switch; when false no dispatcher is constructed
     * @param narratorSystemPrompt prompt injected as the system-role context for each narrator call
     * @param debounceWindow how long the dispatcher waits to accumulate events before firing
     * @param maxBatchSize cap on events fed into a single narrator prompt
     * @param queueHighWaterMark threshold past which the dispatcher fires before the debounce
     *     window elapses
     */
    public record NarratorSettings(
            boolean enabled,
            String narratorSystemPrompt,
            Duration debounceWindow,
            int maxBatchSize,
            int queueHighWaterMark) {

        public static NarratorSettings defaults() {
            return new NarratorSettings(
                    true,
                    DEFAULT_NARRATOR_PROMPT,
                    Duration.ofSeconds(3),
                    10,
                    5);
        }

        public static NarratorSettings disabled() {
            return new NarratorSettings(false, "", Duration.ofSeconds(3), 10, 5);
        }
    }

    /**
     * Default narrator system prompt — kept intentionally terse. The Team Lead either writes a
     * single short paragraph or calls {@code no_narration} when nothing is worth surfacing.
     */
    private static final String DEFAULT_NARRATOR_PROMPT =
            "You are the Team Lead coordinating a multi-expert engineering team. Below are recent "
                    + "updates from your experts. If anything is worth surfacing to the user — a "
                    + "meaningful finding, a risk, a decision point — write a single short paragraph in "
                    + "your own voice. If everything is routine in-progress noise, call the "
                    + "`no_narration` tool with no arguments. Be terse; the user is already watching the "
                    + "raw expert stream.";

    /** Thin projection of a swarm event into the narrator's batch context. */
    record PeerEventSummary(String role, String content, String eventId, long ts) {}

    // ── NarratorDispatcher ──────────────────────────────────────────────────────

    /**
     * Single-flight, debounced dispatcher that asks the Team-Lead {@link Agent} to either narrate
     * the latest batch of expert progress or stay silent via {@code no_narration}.
     *
     * <p>Lives as an inner class because it directly accesses the shared sink, the
     * {@code AgentConcurrencyController}, and the wrapped {@code agent}. Lifecycle is bound to the
     * enclosing {@code TeamSessionPayload}: created when the experts preset is constructed,
     * disposed in {@link #stop()}.
     *
     * <p>Key properties (load-bearing — see plan §Risks):
     * <ul>
     *   <li><b>Single-flight</b>: an in-flight narrator call blocks new dispatches via the
     *       {@code dispatchInFlight} CAS. Backlog events accumulate in {@code pendingBatch} and
     *       drain on the next dispatch — no narrator call ever sees a stale prompt.
     *   <li><b>Debounced</b>: {@code bufferTimeout(queueHighWaterMark, debounceWindow)} fires on
     *       whichever arrives first — {@code debounceWindow} elapses, or the queue accumulates
     *       {@code queueHighWaterMark} events. {@code maxBatchSize} is the *prompt-size cap* enforced
     *       at {@link #drainBatch()}, not the buffer trigger (avoids the dead force-flush of #71).
     *   <li><b>Suspendable</b>: {@link #suspend()} is called from {@code handleMessage} before the
     *       user turn acquires a slot, ensuring no narrator call competes with a user turn.
     *       {@link #resume()} re-enables dispatch; backlog events are still in {@code pendingBatch}.
     * </ul>
     */
    private final class NarratorDispatcher {

        private final NarratorSettings settings;
        private final Sinks.Many<Object> signal =
                Sinks.many().multicast().onBackpressureBuffer();
        private final ConcurrentLinkedQueue<PeerEventSummary> pendingBatch =
                new ConcurrentLinkedQueue<>();
        private final AtomicBoolean suspended = new AtomicBoolean(false);
        private final AtomicBoolean dispatchInFlight = new AtomicBoolean(false);
        private final Disposable subscription;

        NarratorDispatcher(NarratorSettings settings) {
            this.settings = settings;
            // bufferTimeout's count threshold is the high-water-mark — the queue size at which
            // we want to fire ahead of the debounce window. maxBatchSize is the prompt-size cap
            // and is applied at drainBatch(), not here.
            this.subscription = signal.asFlux()
                    .bufferTimeout(settings.queueHighWaterMark(), settings.debounceWindow())
                    .filter(b -> !b.isEmpty())
                    .publishOn(Schedulers.boundedElastic())
                    .subscribe(this::triggerDispatch,
                            err -> log.warn("narrator.signal subscription error (session={}): {}",
                                    ctx.sessionId(), err.getMessage()));
        }

        void enqueue(PeerEventSummary e) {
            pendingBatch.offer(e);
            signal.tryEmitNext(e);
        }

        void suspend() {
            suspended.set(true);
        }

        void resume() {
            if (suspended.compareAndSet(true, false) && !pendingBatch.isEmpty()) {
                // Flushing a sentinel re-arms bufferTimeout so accumulated events get a dispatch.
                signal.tryEmitNext(Boolean.TRUE);
            }
        }

        void dispose() {
            if (!subscription.isDisposed()) {
                subscription.dispose();
            }
            pendingBatch.clear();
        }

        private void triggerDispatch(List<Object> batchSignal) {
            if (suspended.get()) {
                return;
            }
            if (!dispatchInFlight.compareAndSet(false, true)) {
                return;
            }
            List<PeerEventSummary> batch = drainBatch();
            if (batch.isEmpty()) {
                dispatchInFlight.set(false);
                return;
            }
            String sessionId = ctx.sessionId();
            Sinks.Many<AgentEvent> sink = ctx.sharedSink();
            ModelProvider modelProvider = preset != null ? preset.narratorModelProvider() : null;
            if (modelProvider == null) {
                // Fallback: narrator model not wired — emit nothing and release latch.
                dispatchInFlight.set(false);
                return;
            }
            List<Msg> messages = buildNarratorMessages(settings, batch);
            ModelConfig modelCfg = ModelConfig.builder()
                    .model(config.modelName())
                    .maxTokens(512)
                    .temperature(0.3)
                    .addTool(NO_NARRATION_TOOL_DEF)
                    .build();
            ctx.concurrency().enqueueNarrator(sessionId, slot ->
                    modelProvider.call(messages, modelCfg)
                            .subscribeOn(Schedulers.boundedElastic())
                            .doFinally(s -> {
                                slot.close();
                                dispatchInFlight.set(false);
                            })
                            .subscribe(
                                    response -> emitOrSuppressModel(response, sessionId, sink),
                                    err -> log.warn(
                                            "narrator.call failed (session={}): {}",
                                            sessionId, err.getMessage())));
            // If enqueueNarrator dropped the dispatch on contention, the doFinally above never
            // runs — release the latch so the next batch isn't permanently blocked.
            if (!dispatchInFlight.get()) {
                return;
            }
        }

        private List<PeerEventSummary> drainBatch() {
            List<PeerEventSummary> out = new ArrayList<>();
            PeerEventSummary e;
            while (out.size() < settings.maxBatchSize() && (e = pendingBatch.poll()) != null) {
                out.add(e);
            }
            return out;
        }

        private void emitOrSuppressModel(ModelResponse response, String sessionId,
                Sinks.Many<AgentEvent> sink) {
            for (Content c : response.contents()) {
                if (c instanceof Content.ToolUseContent t
                        && NoNarrationTool.NAME.equals(t.toolName())) {
                    log.debug("narrator.dispatch suppressed=true session={}", sessionId);
                    return;
                }
            }
            String text = response.contents().stream()
                    .filter(Content.TextContent.class::isInstance)
                    .map(c -> ((Content.TextContent) c).text())
                    .findFirst()
                    .orElse(null);
            if (text == null || text.isBlank()) {
                return;
            }
            log.debug("narrator.dispatch suppressed=false session={} chars={}",
                    sessionId, text.length());
            ctx.emit(AgentEvent.textChunk(sessionId, text));
        }

        private List<Msg> buildNarratorMessages(NarratorSettings settings,
                List<PeerEventSummary> batch) {
            StringBuilder body = new StringBuilder("Recent expert updates:");
            for (PeerEventSummary e : batch) {
                body.append("\n- [").append(e.role()).append("] ").append(e.content());
            }
            return List.of(
                    Msg.of(MsgRole.SYSTEM, settings.narratorSystemPrompt()),
                    Msg.of(MsgRole.USER, body.toString()));
        }
    }

    private static final ToolDefinition NO_NARRATION_TOOL_DEF = new ToolDefinition(
            NoNarrationTool.NAME,
            "Skip surfacing this batch of expert updates to the user. Call with no arguments "
                    + "when the batched expert progress is routine in-progress noise.",
            ToolCategory.GENERAL,
            new JsonSchema("object", Map.of(), List.of(), "No parameters required"),
            NoNarrationTool.class,
            null,
            ToolSideEffect.READ_ONLY,
            "");
}
