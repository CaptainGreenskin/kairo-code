/*
 * Copyright 2025-2026 the Kairo authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.kairo.code.core.team;

import io.kairo.api.agent.Agent;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.team.TeamConfig;
import io.kairo.api.team.TeamExecutionRequest;
import io.kairo.api.team.TeamResult;
import io.kairo.api.team.TeamStatus;
import io.kairo.api.workspace.Workspace;
import io.kairo.code.core.team.persistence.TeamManifest;
import io.kairo.multiagent.orchestration.ExpertTeamCoordinator;
import io.kairo.multiagent.subagent.ExpertRoleRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Thin delegation shell over {@link ExpertTeamCoordinator}.
 * Translates kairo-code team requests into upstream expert-team execution.
 */
public class SwarmCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SwarmCoordinator.class);

    private final ExpertTeamCoordinator coordinator;
    private final ExpertRoleRegistry roleRegistry;
    private final io.kairo.api.team.MessageBus messageBus;
    private final List<Agent> agents;
    private volatile String lastTeamId;
    private final AtomicReference<Workspace> activeWorkspace = new AtomicReference<>();

    public SwarmCoordinator(ExpertTeamCoordinator coordinator,
                            ExpertRoleRegistry roleRegistry,
                            io.kairo.api.team.MessageBus messageBus,
                            List<Agent> agents) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.roleRegistry = Objects.requireNonNull(roleRegistry, "roleRegistry");
        this.messageBus = Objects.requireNonNull(messageBus, "messageBus");
        this.agents = List.copyOf(Objects.requireNonNull(agents, "agents"));
    }

    /**
     * Set the active workspace for the current execution. Workers read this at call time
     * to resolve tool working directory. Must be called before {@link #confirmAndExecute}.
     */
    public void setActiveWorkspace(Workspace workspace) {
        activeWorkspace.set(workspace);
    }

    /** Returns the active workspace (may be null before any session binds one). */
    public Workspace getActiveWorkspace() {
        return activeWorkspace.get();
    }

    /**
     * Start an expert team execution.
     *
     * @param goal    user's task description
     * @param config  team configuration
     * @param roleIds specific roles to use (empty = let planner decide)
     * @return team execution result
     */
    public Mono<TeamResult> startExpertTeam(String goal, TeamConfig config, List<String> roleIds) {
        return startExpertTeam(goal, config, roleIds, false);
    }

    /**
     * Start an expert team execution with planOnly option.
     *
     * @param goal     user's task description
     * @param config   team configuration
     * @param roleIds  specific roles to use (empty = let planner decide)
     * @param planOnly if true, only generate the plan without executing it
     * @return team execution result (plan-ready if planOnly)
     */
    public record TeamStartResult(String teamId, Mono<TeamResult> result) {}

    public Mono<TeamResult> startExpertTeam(String goal, TeamConfig config, List<String> roleIds, boolean planOnly) {
        TeamStartResult r = startExpertTeamWithId(goal, config, roleIds, planOnly);
        return r.result();
    }

    public TeamStartResult startExpertTeamWithId(String goal, TeamConfig config, List<String> roleIds, boolean planOnly) {
        io.kairo.api.team.Team team = buildTeam(roleIds);
        // Publish the session's workspace root into the request context so the (singleton)
        // WorkspaceContextGatherer can collect the RIGHT workspace per execution instead of being
        // bound to one fixed root. Absent → gatherer degrades to an empty SharedContext.
        java.util.Map<String, Object> ctx = new java.util.HashMap<>();
        Workspace ws = activeWorkspace.get();
        if (ws != null && ws.root() != null) {
            ctx.put(SessionWorkspaceContextGatherer.WORKSPACE_ROOT_KEY, ws.root().toString());
        }
        TeamExecutionRequest request = new TeamExecutionRequest(
                UUID.randomUUID().toString(), goal, java.util.Map.copyOf(ctx), config);
        String teamId = team.name();
        lastTeamId = teamId;
        return new TeamStartResult(teamId, coordinator.execute(request, team, planOnly));
    }

    /**
     * Convenience overload using default configuration.
     */
    public Mono<TeamResult> startExpertTeam(String goal) {
        TeamConfig config = new TeamConfig(
                TeamConfig.defaults().riskProfile(),
                TeamConfig.defaults().maxFeedbackRounds(),
                java.time.Duration.ofMinutes(30),
                null,
                TeamConfig.defaults().evaluatorPreference(),
                TeamConfig.defaults().plannerFailureMode(),
                TeamConfig.defaults().resourceConstraint());
        return startExpertTeam(goal, config, List.of());
    }

    /**
     * Resume execution of a previously planned team (planOnly mode).
     * Call this after the user has reviewed and confirmed the plan.
     *
     * @param teamId the team ID returned from the planOnly execution
     * @return team execution result
     */
    public Mono<TeamResult> confirmAndExecute(String teamId) {
        return coordinator.confirmAndExecute(teamId);
    }

    /** Returns the last created team ID (used for plan-preview → confirm flow). */
    public String lastTeamId() {
        return lastTeamId;
    }

    /** Returns the role registry for introspection. */
    public ExpertRoleRegistry roleRegistry() {
        return roleRegistry;
    }

    /**
     * Steer running expert worker(s) in real time by injecting a user directive into the live
     * worker agent's conversation (picked up at its next reasoning iteration, no interrupt).
     *
     * @param stepId target a specific running step; {@code null}/blank → all currently-active steps
     * @param directive the user's instruction
     * @return {@code true} if at least one active worker received the directive; {@code false} means
     *     no step is currently executing (caller should queue the directive for the next plan)
     */
    public boolean steer(String stepId, String directive) {
        return coordinator.steer(stepId, directive);
    }

    /**
     * Inject user feedback (e.g. a step rejection) into the running step's worker in real time.
     * Reuses the {@link #steer} path (which uses {@code Agent.injectMessages}) — the previous
     * {@code MessageBus.send("user-feedback", …)} channel had no consumer and was a no-op.
     *
     * @param teamId   the team ID (for logging)
     * @param stepId   the step whose worker should receive the feedback
     * @param feedback the user's feedback text
     * @return a Mono that completes once the directive is injected (or skipped if step not active)
     */
    public Mono<Void> injectUserFeedback(String teamId, String stepId, String feedback) {
        return Mono.fromRunnable(
                () -> {
                    boolean hit = coordinator.steer(stepId, "User feedback: " + feedback);
                    if (!hit) {
                        log.debug(
                                "injectUserFeedback: step {} not active, feedback dropped (team {})",
                                stepId,
                                teamId);
                    }
                });
    }

    /**
     * Resume a team from a saved manifest after crash recovery.
     * Completed steps' outputs are loaded from persistence.
     * Incomplete steps are re-run from scratch.
     */
    public Mono<TeamResult> resumeFromManifest(TeamManifest manifest) {
        // Filter DAG: only steps NOT in completedStepIds need to be re-run
        List<TeamManifest.StepEntry> incompleteSteps = manifest.dag().stream()
                .filter(step -> !manifest.completedStepIds().contains(step.stepId()))
                .toList();

        if (incompleteSteps.isEmpty()) {
            log.info("Team '{}' has no incomplete steps \u2014 marking as completed", manifest.teamId());
            return Mono.just(TeamResult.withoutOutput(
                    manifest.teamId(), TeamStatus.COMPLETED, List.of(), Duration.ZERO, List.of()));
        }

        // Build a focused goal that references the original and lists only the incomplete
        // steps, so the coordinator does not redo completed work.
        StringBuilder resumeGoal = new StringBuilder();
        resumeGoal.append("Resume the following task (some steps already completed):\n\n");
        resumeGoal.append("Original goal: ").append(manifest.goal()).append("\n\n");
        resumeGoal.append("Completed steps (do NOT redo these): ")
                .append(String.join(", ", manifest.completedStepIds())).append("\n\n");
        resumeGoal.append("Steps still to do:\n");
        for (TeamManifest.StepEntry step : incompleteSteps) {
            resumeGoal.append("- ").append(step.stepId())
                    .append(": ").append(step.description()).append("\n");
        }
        return startExpertTeam(resumeGoal.toString(), TeamConfig.defaults(), List.of());
    }

    private io.kairo.api.team.Team buildTeam(List<String> roleIds) {
        String teamName = "expert-team-" + UUID.randomUUID().toString().substring(0, 8);
        // When roleIds is empty, the planner uses all registered profiles.
        // The Team is always built with the full agent pool; the planner handles
        // role assignment via round-robin binding.
        return new io.kairo.api.team.Team(teamName, agents, messageBus);
    }
}
