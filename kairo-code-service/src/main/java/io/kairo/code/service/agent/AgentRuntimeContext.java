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
) {}
