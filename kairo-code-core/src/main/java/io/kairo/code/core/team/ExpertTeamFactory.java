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
import io.kairo.api.model.ModelProvider;
import io.kairo.api.team.TeamConfig;
import io.kairo.api.team.TeamResult;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentFactory;
import io.kairo.expertteam.ExpertTeamCoordinator;
import io.kairo.expertteam.role.ExpertRoleRegistry;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Factory for creating Expert Team sessions without Spring DI.
 *
 * <p>Builds a {@link SwarmCoordinator} from raw config + model provider, suitable for CLI one-shot
 * mode (SWE-bench evaluation, batch tasks).
 */
public final class ExpertTeamFactory {

    private ExpertTeamFactory() {}

    private static String truncate(String s, int max) {
        if (s == null) return "(null)";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * Create a fully-wired SwarmCoordinator ready for expert team execution.
     *
     * @param config         agent config (API key, model, working dir, etc.)
     * @param modelProvider  the model provider to use for all team agents
     * @param agentCount     number of worker agents in the pool (default: 3)
     * @return a configured SwarmCoordinator
     */
    public static SwarmCoordinator create(
            CodeAgentConfig config, ModelProvider modelProvider, int agentCount) {
        // M-F6a: delegate the boilerplate composition (coordinator + role registry + no-op
        // message bus + agent loop) to the upstream ExpertTeamComposer, only supplying the
        // kairo-code-specific worker factory. This is what made kairo-assistant unable to
        // reuse the original method — it hardcoded CodeAgentFactory.
        var composition =
                io.kairo.expertteam.ExpertTeamComposer.create(
                        agentCount,
                        () ->
                                CodeAgentFactory.createSession(
                                                config,
                                                CodeAgentFactory.SessionOptions.empty()
                                                        .withModelProvider(modelProvider)
                                                        .asChildSession())
                                        .agent());
        return new SwarmCoordinator(
                composition.coordinator(),
                composition.roleRegistry(),
                composition.messageBus(),
                composition.agents());
    }

    /**
     * Run an expert team task in one-shot mode with timeout.
     *
     * @param coordinator  the swarm coordinator
     * @param task         the task description
     * @param timeoutSeconds  timeout in seconds (0 = no limit)
     * @param stderr       where to print progress
     * @return the team result
     */
    public static TeamResult runOneShot(
            SwarmCoordinator coordinator, String task, int timeoutSeconds, PrintWriter stderr) {
        stderr.println("[expert-team] Starting expert team execution...");
        stderr.flush();

        // Use CLI timeout for TeamConfig (not the default 10min) so sub-agents get enough time
        TeamConfig config;
        if (timeoutSeconds > 0) {
            config = new TeamConfig(
                    io.kairo.api.team.RiskProfile.MEDIUM,
                    3,
                    java.time.Duration.ofSeconds(timeoutSeconds),
                    io.kairo.api.team.EvaluatorPreference.AUTO,
                    io.kairo.api.team.PlannerFailureMode.FAIL_FAST,
                    io.kairo.api.team.TeamResourceConstraint.unbounded());
            stderr.printf("[expert-team] Config: teamTimeout=%ds%n", timeoutSeconds);
        } else {
            config = TeamConfig.defaults();
        }

        Mono<TeamResult> mono = coordinator.startExpertTeam(task, config, List.of());

        if (timeoutSeconds > 0) {
            mono = mono.timeout(java.time.Duration.ofSeconds(timeoutSeconds));
        }

        try {
            TeamResult result = mono.block();
            if (result != null) {
                stderr.printf("[expert-team] Completed: status=%s, steps=%d, duration=%s%n",
                        result.status(), result.stepOutcomes().size(), result.totalDuration());

                // Log per-step outcomes for observability
                if (!result.stepOutcomes().isEmpty()) {
                    stderr.println("[expert-team] Step outcomes:");
                    for (var step : result.stepOutcomes()) {
                        stderr.printf("  step=%s verdict=%s attempts=%d output=%s%n",
                                step.stepId(),
                                step.finalVerdict(),
                                step.attempts(),
                                truncate(step.output(), 200));
                    }
                }
                if (!result.warnings().isEmpty()) {
                    stderr.println("[expert-team] Warnings:");
                    for (String w : result.warnings()) {
                        stderr.printf("  - %s%n", truncate(w, 300));
                    }
                }
                result.finalOutput().ifPresent(out ->
                        stderr.printf("[expert-team] Final output: %s%n", truncate(out, 500)));
                stderr.flush();
            }
            return result;
        } catch (Exception e) {
            stderr.println("[expert-team] Execution failed: " + e.getMessage());
            stderr.flush();
            throw e;
        }
    }
}
