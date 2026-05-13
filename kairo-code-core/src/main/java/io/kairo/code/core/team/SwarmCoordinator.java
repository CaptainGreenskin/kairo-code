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
import io.kairo.code.core.team.persistence.TeamManifest;
import io.kairo.expertteam.ExpertTeamCoordinator;
import io.kairo.expertteam.role.ExpertRoleRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
    public Mono<TeamResult> startExpertTeam(String goal, TeamConfig config, List<String> roleIds, boolean planOnly) {
        io.kairo.api.team.Team team = buildTeam(roleIds);
        TeamExecutionRequest request = new TeamExecutionRequest(
                UUID.randomUUID().toString(), goal, Map.of(), config);
        lastTeamId = team.name();
        return coordinator.execute(request, team, planOnly);
    }

    /**
     * Convenience overload using default configuration.
     */
    public Mono<TeamResult> startExpertTeam(String goal) {
        return startExpertTeam(goal, TeamConfig.defaults(), List.of());
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
     * Inject user feedback (rejection) into a running step's agent channel.
     * The step agent can pick this up via {@link io.kairo.api.team.MessageBus#receive}.
     *
     * @param teamId   the team ID (for logging)
     * @param stepId   the step whose agent should receive the feedback
     * @param feedback the user's rejection feedback text
     * @return a Mono that completes when the message is enqueued
     */
    public Mono<Void> injectUserFeedback(String teamId, String stepId, String feedback) {
        Msg feedbackMsg = Msg.of(MsgRole.USER, "User rejected output. Feedback: " + feedback);
        return messageBus.send("user-feedback", stepId, feedbackMsg);
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

        // Re-run with the full goal; the coordinator will re-plan from scratch
        // using completed step outputs as available dependency inputs
        return startExpertTeam(manifest.goal(), TeamConfig.defaults(), List.of());
    }

    private io.kairo.api.team.Team buildTeam(List<String> roleIds) {
        String teamName = "expert-team-" + UUID.randomUUID().toString().substring(0, 8);
        // When roleIds is empty, the planner uses all registered profiles.
        // The Team is always built with the full agent pool; the planner handles
        // role assignment via round-robin binding.
        return new io.kairo.api.team.Team(teamName, agents, messageBus);
    }
}
