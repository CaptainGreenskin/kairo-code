package io.kairo.code.service.agent;

import io.kairo.api.agent.Agent;
import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentSession;
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

import java.util.Base64;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
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

    private final CodeAgentConfig config;
    private final CodeAgentSession session;
    private volatile Agent agent;
    private final AgentRuntimeContext ctx;

    // ── Payload-owned state ──────────────────────────────────────────────────────
    private final ReentrantLock refineLock = new ReentrantLock();
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
        String sessionId = ctx.sessionId();
        AtomicReference<SessionPhase> phaseRef = ctx.phaseRef();
        SessionPhase phase = phaseRef.get();

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
        sink.tryEmitNext(AgentEvent.thinking(sessionId));

        // Acquire concurrency slot
        AgentSlot slot;
        try {
            slot = ctx.concurrency().acquire(sessionId);
        } catch (AgentConcurrencyException e) {
            sink.tryEmitNext(AgentEvent.error(sessionId, e.getMessage(), e.reason().name()));
            running.set(false);
            return sink.asFlux();
        }

        // Thinking delta consumer (for reasoning models)
        Consumer<String> thinkingConsumer = delta -> {
            if (delta != null && !delta.isEmpty()) {
                sink.tryEmitNext(AgentEvent.thinkingChunk(sessionId, delta));
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
        Disposable d = currentRun.getAndSet(null);
        if (d != null && !d.isDisposed()) {
            d.dispose();
        }
        ctx.runningState().set(false);
        refineQueue.clear();
        // Also interrupt the underlying agent for cooperative cancellation
        session.agent().interrupt();
    }

    @Override
    public boolean isRunning() {
        return ctx.runningState().get();
    }

    @Override
    public SessionPhase getState() {
        return ctx.phaseRef().get();
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
        if (!refineLock.tryLock()) {
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

                    sink.tryEmitNext(AgentEvent.thinking(sessionId));

                    // Acquire concurrency slot before calling agent
                    AgentSlot slot = null;
                    try {
                        slot = ctx.concurrency().acquire(sessionId);
                    } catch (AgentConcurrencyException e) {
                        sink.tryEmitNext(AgentEvent.error(sessionId, e.getMessage(), e.reason().name()));
                        break;
                    }

                    // Call the agent for refinement (still in plan mode context)
                    try {
                        this.agent.call(msg).block(Duration.ofMinutes(5));
                    } catch (Exception e) {
                        log.warn("Plan refinement call failed for session {}: {}",
                                sessionId, e.getMessage());
                        sink.tryEmitNext(AgentEvent.error(sessionId,
                                "Plan refinement failed: " + e.getMessage(), "REFINEMENT_ERROR"));
                    } finally {
                        slot.close();
                    }
                }
            } finally {
                refineLock.unlock();
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
        if (!refineLock.tryLock()) {
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

    /**
     * Build the user {@link Msg} from a {@link MessageRequest}.
     * Extracted here so AgentService.sendMessage can use it during the transition period.
     */
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
