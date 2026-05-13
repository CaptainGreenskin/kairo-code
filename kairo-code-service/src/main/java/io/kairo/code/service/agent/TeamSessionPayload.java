package io.kairo.code.service.agent;

import io.kairo.api.team.TeamConfig;
import io.kairo.api.team.TeamResult;
import io.kairo.code.core.team.SwarmCoordinator;
import io.kairo.code.service.AgentEvent;
import io.kairo.code.service.SessionPhase;
import io.kairo.code.service.team.TriageGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Expert-team session payload wrapping a {@link SwarmCoordinator} and {@link TeamConfig}.
 *
 * <p>This payload handles "experts" mode sessions where the user's message is routed
 * through a plan-preview flow:
 * <ol>
 *   <li>First message: call coordinator with planOnly=true → PLAN_READY event</li>
 *   <li>Subsequent messages while in plan-pending: route for DAG adjustment (re-plan)</li>
 *   <li>On confirmBuild signal: call confirmAndExecute → full execution</li>
 * </ol>
 *
 * @see AgentSessionPayload for the single-agent counterpart
 */
public final class TeamSessionPayload implements SessionPayload {

    private static final Logger log = LoggerFactory.getLogger(TeamSessionPayload.class);

    private final SwarmCoordinator coordinator;
    private final TeamConfig teamConfig;
    private final String sessionId;
    private final TriageGate triageGate;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<SessionPhase> phase = new AtomicReference<>(SessionPhase.IDLE);
    private volatile String pendingTeamId;

    /**
     * Internal fallback for messages that fail triage (not complex enough for expert-team).
     */
    private volatile AgentSessionPayload fallback;

    public TeamSessionPayload(SwarmCoordinator coordinator,
                               TeamConfig teamConfig,
                               String sessionId,
                               TriageGate triageGate) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.teamConfig = Objects.requireNonNull(teamConfig, "teamConfig");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.triageGate = Objects.requireNonNull(triageGate, "triageGate");
    }

    /**
     * Set the fallback single-agent payload for messages that fail triage.
     * Called during session creation when the triage gate is configured.
     */
    public void setFallback(AgentSessionPayload fallback) {
        this.fallback = fallback;
    }

    /**
     * Handle a user message in expert-team mode.
     *
     * <p>Flow:
     * <ol>
     *   <li>First message (IDLE/FAILED_PLANNING): triage → if demoted route to fallback, else planOnly</li>
     *   <li>Messages while PLAN_PENDING: route to re-plan (re-run planOnly with updated goal)</li>
     *   <li>confirmBuild (see {@link #confirmBuild()}): execute the pending plan</li>
     * </ol>
     */
    @Override
    public Flux<AgentEvent> handleMessage(MessageRequest request) {
        SessionPhase current = phase.get();

        switch (current) {
            case IDLE, FAILED_PLANNING -> {
                if (!triageGate.shouldFanOut(request.text())) {
                    // Demote to ReAct — message too short/simple for expert team
                    Sinks.Many<AgentEvent> sink = Sinks.many().multicast().onBackpressureBuffer();
                    sink.tryEmitNext(AgentEvent.modeDemoted(sessionId,
                            "Message too simple for experts mode; routing to single-agent"));
                    sink.tryEmitComplete();

                    AgentSessionPayload fb = this.fallback;
                    if (fb != null) {
                        return Flux.concat(sink.asFlux(), fb.handleMessage(request));
                    }
                    // No fallback wired — just emit the demotion event
                    return sink.asFlux();
                }
                // Triage passed — start expert team planning
                return startPlanOnly(request.text());
            }

            case PLAN_PENDING -> {
                // User is refining the plan — re-plan with the updated goal
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
                // Reset and allow new planning
                phase.set(SessionPhase.IDLE);
                return handleMessage(request);
            }

            case FAILED_EXECUTION -> {
                return Flux.just(AgentEvent.error(sessionId,
                        "Execution failed. Please revert the workspace before retrying.",
                        "REVERT_REQUIRED"));
            }

            default -> {
                return Flux.just(AgentEvent.error(sessionId,
                        "Unexpected session phase: " + current, "INTERNAL_ERROR"));
            }
        }
    }

    /**
     * Confirm and execute the pending plan.
     * Called when the user clicks "Start Team" after reviewing the plan preview.
     *
     * @return Flux of AgentEvents during execution
     */
    @Override
    public Flux<AgentEvent> confirmBuild() {
        if (phase.get() != SessionPhase.PLAN_PENDING) {
            return Flux.just(AgentEvent.error(sessionId,
                    "No plan is pending confirmation. Current phase: " + phase.get(),
                    "INVALID_STATE"));
        }

        String teamId = pendingTeamId;
        if (teamId == null) {
            return Flux.just(AgentEvent.error(sessionId,
                    "No pending team ID found.", "INTERNAL_ERROR"));
        }

        phase.set(SessionPhase.EXECUTING);
        running.set(true);

        Sinks.Many<AgentEvent> sink = Sinks.many().multicast().onBackpressureBuffer();

        coordinator.confirmAndExecute(teamId)
                .doOnSuccess(result -> {
                    running.set(false);
                    phase.set(SessionPhase.COMPLETED);
                    sink.tryEmitNext(AgentEvent.done(sessionId, 0, 0));
                    sink.tryEmitComplete();
                })
                .doOnError(err -> {
                    running.set(false);
                    phase.set(SessionPhase.FAILED_EXECUTION);
                    log.warn("Expert team execution failed (session={}): {}",
                            sessionId, err.getMessage());
                    sink.tryEmitNext(AgentEvent.error(sessionId,
                            "Expert team execution failed: " + err.getMessage(),
                            "TEAM_EXECUTION_ERROR"));
                    sink.tryEmitComplete();
                })
                .subscribe();

        return sink.asFlux();
    }

    @Override
    public void stop() {
        running.set(false);
        // Stop fallback if active
        AgentSessionPayload fb = this.fallback;
        if (fb != null) {
            fb.stop();
        }
        log.info("TeamSessionPayload stopped (session={})", sessionId);
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public SessionPhase getState() {
        return phase.get();
    }

    // ── Private helpers ──

    private Flux<AgentEvent> startPlanOnly(String goal) {
        phase.set(SessionPhase.PLANNING);
        running.set(true);

        Sinks.Many<AgentEvent> sink = Sinks.many().multicast().onBackpressureBuffer();

        // Emit thinking indicator
        sink.tryEmitNext(AgentEvent.thinking(sessionId));

        coordinator.startExpertTeam(goal, teamConfig, List.<String>of(), true)
                .doOnSuccess(result -> {
                    running.set(false);
                    pendingTeamId = coordinator.lastTeamId();
                    phase.set(SessionPhase.PLAN_PENDING);
                    // Emit plan-ready with the plan text from finalOutput (the canonical location)
                    sink.tryEmitNext(AgentEvent.planReady(sessionId,
                            extractPlanSummary(result)));
                    sink.tryEmitComplete();
                })
                .doOnError(err -> {
                    running.set(false);
                    phase.set(SessionPhase.FAILED_PLANNING);
                    log.warn("Expert team planning failed (session={}): {}",
                            sessionId, err.getMessage());
                    sink.tryEmitNext(AgentEvent.error(sessionId,
                            "Planning failed: " + err.getMessage(),
                            "PLANNING_ERROR"));
                    sink.tryEmitComplete();
                })
                .subscribe();

        return sink.asFlux();
    }

    /**
     * Extract the plan summary from the TeamResult.
     * In planOnly mode the coordinator places the plan text in {@code finalOutput()}.
     */
    private String extractPlanSummary(TeamResult result) {
        return result.finalOutput().orElse("Plan generated successfully");
    }

    // ── Accessors ──

    /** The swarm coordinator for expert-team execution. */
    public SwarmCoordinator coordinator() {
        return coordinator;
    }

    /** The team configuration for this session. */
    public TeamConfig teamConfig() {
        return teamConfig;
    }

    /** The fallback single-agent payload, if configured. */
    public AgentSessionPayload fallback() {
        return fallback;
    }

    /** The pending team ID (available after plan-only completes). */
    public String pendingTeamId() {
        return pendingTeamId;
    }

    /** Current phase of the team session (string form for backward compat). */
    public String currentPhase() {
        return phase.get().name().toLowerCase();
    }
}
