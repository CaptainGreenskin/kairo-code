package io.kairo.code.service.agent;

import io.kairo.api.agent.Agent;
import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.code.core.team.MessageBus;
import io.kairo.code.core.team.TeamManager;
import io.kairo.code.service.AgentEvent;
import io.kairo.code.service.SessionPhase;
import io.kairo.code.service.concurrency.AgentConcurrencyException;
import io.kairo.code.service.concurrency.AgentSlot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Team-mode session payload — Claude-style {@code TeamCreate} live multi-agent collaboration.
 *
 * <p>The orchestrator session is a normal {@link CodeAgentSession} whose tool registry has been
 * augmented (via {@link io.kairo.code.core.CodeAgentFactory.SessionOptions#withTeamPrimitives}) with
 * {@code team_create} / {@code send_message} / {@code team_delete}. The model uses those tools to
 * spawn workers (via the existing {@code task} tool) and exchange messages over the in-process
 * {@link MessageBus}. Inbound peer messages addressed to this session are polled by a background
 * scheduler and projected onto the shared sink as {@link AgentEvent#peerMessage} events so they
 * surface in the live chat transcript.
 *
 * <p>The phase machine reuses only the {@link AgentSessionPayload} chat-loop subset
 * (IDLE ⇄ PLANNING ⇄ COMPLETED ⇄ FAILED_PLANNING). PLAN_PENDING / EXECUTING are Experts-only and
 * never reached here — Team mode never enters the plan-preview flow.
 *
 * <p>Introduced by M-Team (task #60). See ADR-001 §"Implementation status & order" §3 and
 * {@link ExpertsSessionPayload} for the DAG-batch counterpart.
 */
public final class TeamSessionPayload implements SessionPayload {

    private static final Logger log = LoggerFactory.getLogger(TeamSessionPayload.class);

    /** Cadence for draining the peer-message mailbox. 500ms keeps perceived latency low. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    private final CodeAgentConfig config;
    private final CodeAgentSession session;
    private volatile Agent agent;
    private final AgentRuntimeContext ctx;
    private final TeamManager teamManager;
    private final MessageBus messageBus;

    private final AtomicReference<Disposable> currentRun = new AtomicReference<>();
    private final AtomicReference<Disposable> peerPoller = new AtomicReference<>();

    public TeamSessionPayload(CodeAgentConfig config,
                              CodeAgentSession session,
                              AgentRuntimeContext ctx,
                              TeamManager teamManager,
                              MessageBus messageBus) {
        this.config = Objects.requireNonNull(config, "config");
        this.session = Objects.requireNonNull(session, "session");
        this.agent = session.agent();
        this.ctx = Objects.requireNonNull(ctx, "ctx");
        this.teamManager = Objects.requireNonNull(teamManager, "teamManager");
        this.messageBus = Objects.requireNonNull(messageBus, "messageBus");
        startPeerPoller();
    }

    // ── SessionPayload contract ──────────────────────────────────────────────────

    @Override
    public Flux<AgentEvent> handleMessage(MessageRequest request) {
        String sessionId = ctx.sessionId();
        AtomicReference<SessionPhase> phaseRef = ctx.phaseRef();
        SessionPhase phase = phaseRef.get();

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

        sink.tryEmitNext(AgentEvent.thinking(sessionId));

        AgentSlot slot;
        try {
            slot = ctx.concurrency().acquire(sessionId);
        } catch (AgentConcurrencyException e) {
            sink.tryEmitNext(AgentEvent.error(sessionId, e.getMessage(), e.reason().name()));
            running.set(false);
            return sink.asFlux();
        }

        Consumer<String> thinkingConsumer = delta -> {
            if (delta != null && !delta.isEmpty()) {
                sink.tryEmitNext(AgentEvent.thinkingChunk(sessionId, delta));
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
    public void stop() {
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
        log.info("TeamSessionPayload stopped (session={})", ctx.sessionId());
    }

    @Override
    public boolean isRunning() {
        return ctx.runningState().get();
    }

    @Override
    public SessionPhase getState() {
        return ctx.phaseRef().get();
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
                            sink.tryEmitNext(AgentEvent.peerMessage(
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
}
