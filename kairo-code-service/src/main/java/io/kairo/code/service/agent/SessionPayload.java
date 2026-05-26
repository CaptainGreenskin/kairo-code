package io.kairo.code.service.agent;

import io.kairo.code.service.AgentEvent;
import io.kairo.code.service.SessionPhase;
import reactor.core.publisher.Flux;

/**
 * Polymorphic session handler: single-agent chat, Experts DAG batch, or live multi-agent Team.
 *
 * <p>Implementations encapsulate all the state needed to handle a user message and translate
 * it into a stream of {@link AgentEvent}s.
 * <ul>
 *   <li>{@link AgentSessionPayload} wraps a single {@code CodeAgentSession}.</li>
 *   <li>{@link ExpertsSessionPayload} wraps a {@code SwarmCoordinator} for Experts mode
 *       (plan-preview → confirm → DAG execution).</li>
 *   <li>{@link TeamSessionPayload} wraps a long-lived multi-agent session with peer-to-peer
 *       SendMessage and shared TaskList (Claude TeamCreate-style; introduced by M-Team / #60).</li>
 * </ul>
 */
public sealed interface SessionPayload
        permits AgentSessionPayload, ExpertsSessionPayload, TeamSessionPayload {

    /**
     * Handle an incoming user message, returning a reactive stream of events.
     *
     * @param request the user message (text, optional image)
     * @return Flux of {@link AgentEvent}s that the transport layer should relay to the client
     */
    Flux<AgentEvent> handleMessage(MessageRequest request);

    /**
     * Interrupt / stop the current execution, if any.
     */
    void stop();

    /**
     * @return {@code true} if an execution is currently in flight.
     */
    boolean isRunning();

    /**
     * @return the current session phase (state machine position).
     */
    SessionPhase getState();

    /**
     * Confirm and execute the pending plan.
     * <p>For {@link AgentSessionPayload} and {@link TeamSessionPayload} this is a no-op.
     * For {@link ExpertsSessionPayload} this triggers DAG execution.
     *
     * @return Flux of events during execution
     */
    default Flux<AgentEvent> confirmBuild() {
        return Flux.empty();
    }
}
