package io.kairo.code.service.agent;

import io.kairo.code.service.AgentEvent;
import io.kairo.code.service.SessionPhase;
import io.kairo.code.service.concurrency.AgentConcurrencyController;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Runtime dependencies shared by all AgentSessionPayload instances within a SessionEntry.
 *
 * <p>Encapsulates the per-session infrastructure that was previously scattered across
 * {@link io.kairo.code.service.AgentService}'s ConcurrentHashMaps. Passing a single context
 * object to {@link AgentSessionPayload} enables the payload to fully own message lifecycle
 * (subscribe, emit events, release concurrency slots) without back-references to the service.
 *
 * @param sessionId     unique session identifier
 * @param sharedSink    the multicast event sink (autoCancel=false) shared across reconnects
 * @param runningState  CAS guard — true while an agent.call() subscription is active
 * @param phaseRef      session lifecycle state machine position
 * @param persistPhase  callback to persist phase transitions to disk (crash recovery)
 * @param concurrency   three-layer concurrency controller (global / session / depth)
 */
public record AgentRuntimeContext(
    String sessionId,
    Sinks.Many<AgentEvent> sharedSink,
    AtomicBoolean runningState,
    AtomicReference<SessionPhase> phaseRef,
    Consumer<SessionPhase> persistPhase,
    AgentConcurrencyController concurrency
) {
    /**
     * Emit an event to the shared multicast sink, the single safe path for all writers.
     *
     * <p>Reactor's {@code multicast().onBackpressureBuffer} sink rejects concurrent
     * {@code tryEmitNext} calls with {@link Sinks.EmitResult#FAIL_NON_SERIALIZED} and silently
     * drops the event. The shared sink is written from several threads at once (agent reactor,
     * swarm-event bridge, narrator dispatcher, worker pool), so a raw {@code tryEmitNext} loses
     * events under contention — this is what left the chat stuck on "Stop" when the terminal
     * {@code AGENT_DONE} raced the bridge. Spin-retry on contention; non-contention failures
     * (terminated / overflow / no-subscriber) are returned to the caller, preserving the prior
     * fire-and-forget leniency.
     */
    public Sinks.EmitResult emit(AgentEvent event) {
        return emitSerialized(sharedSink, event);
    }

    /**
     * Serialized emit usable by any holder of the per-session sink (e.g. AgentService) that does
     * not have an {@link AgentRuntimeContext} handy. See {@link #emit(AgentEvent)} for rationale.
     */
    public static Sinks.EmitResult emitSerialized(Sinks.Many<AgentEvent> sink, AgentEvent event) {
        Sinks.EmitResult result;
        while ((result = sink.tryEmitNext(event)) == Sinks.EmitResult.FAIL_NON_SERIALIZED) {
            Thread.onSpinWait();
        }
        return result;
    }
}
