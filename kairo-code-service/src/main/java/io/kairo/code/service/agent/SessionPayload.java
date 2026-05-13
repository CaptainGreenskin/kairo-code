package io.kairo.code.service.agent;

import io.kairo.code.service.AgentEvent;
import io.kairo.code.service.SessionPhase;
import reactor.core.publisher.Flux;

/**
 * Polymorphic session handler: either a single-agent chat or an expert-team execution.
 *
 * <p>Implementations encapsulate all the state needed to handle a user message and translate
 * it into a stream of {@link AgentEvent}s. {@link AgentSessionPayload} wraps the existing
 * {@code CodeAgentSession}; {@link TeamSessionPayload} wraps a {@code SwarmCoordinator} for
 * multi-agent expert-team mode (wired in Task 74).
 */
public sealed interface SessionPayload
        permits AgentSessionPayload, TeamSessionPayload {

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
     * <p>For {@link AgentSessionPayload} this is a no-op.
     * For {@link TeamSessionPayload} this triggers DAG execution.
     *
     * @return Flux of events during execution
     */
    default Flux<AgentEvent> confirmBuild() {
        return Flux.empty();
    }
}
