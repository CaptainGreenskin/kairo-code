package io.kairo.code.service.agent;

import io.kairo.code.service.AgentEvent;
import io.kairo.code.service.SessionPhase;
import reactor.core.publisher.Flux;

/**
 * Polymorphic session handler: single-agent chat or live multi-agent Team
 * (Team mode and the Experts preset both use {@link TeamSessionPayload}).
 *
 * <p>Implementations encapsulate all the state needed to handle a user message and translate
 * it into a stream of {@link AgentEvent}s.
 * <ul>
 *   <li>{@link AgentSessionPayload} wraps a single {@code CodeAgentSession}.</li>
 *   <li>{@link TeamSessionPayload} wraps a long-lived multi-agent session with peer-to-peer
 *       SendMessage and shared TaskList (Claude TeamCreate-style; introduced by M-Team / #60).
 *       With an {@code ExpertsPresetConfig} attached (M-Experts-Upgrade / #61) it also drives
 *       the Experts plan-preview → confirm → SwarmCoordinator execution flow with an active
 *       narrator Team Lead.</li>
 * </ul>
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
     * <p>For {@link AgentSessionPayload} and default-mode {@link TeamSessionPayload} this is a
     * no-op. For {@link TeamSessionPayload} with an Experts preset this triggers swarm execution.
     *
     * @return Flux of events during execution
     */
    default Flux<AgentEvent> confirmBuild() {
        return Flux.empty();
    }
}
