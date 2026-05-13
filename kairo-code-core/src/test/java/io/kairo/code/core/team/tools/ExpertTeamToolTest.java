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
package io.kairo.code.core.team.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.team.EvaluationVerdict;
import io.kairo.api.team.EvaluationVerdict.VerdictOutcome;
import io.kairo.api.team.TeamConfig;
import io.kairo.api.team.TeamResult;
import io.kairo.api.team.TeamResult.StepOutcome;
import io.kairo.api.team.TeamStatus;
import io.kairo.api.tool.ToolResult;
import io.kairo.code.core.team.SwarmCoordinator;
import io.kairo.expertteam.ExpertTeamCoordinator;
import io.kairo.expertteam.SimpleEvaluationStrategy;
import io.kairo.expertteam.internal.DefaultPlanner;
import io.kairo.expertteam.role.ExpertRoleRegistry;
import io.kairo.expertteam.tck.NoopMessageBus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class ExpertTeamToolTest {

    // ── Test stub for SwarmCoordinator ──

    /**
     * Minimal stub that overrides startExpertTeam to return a canned result
     * without needing a real ExpertTeamCoordinator.
     */
    private static class StubSwarmCoordinator extends SwarmCoordinator {

        private Mono<TeamResult> cannedResult;
        private String capturedGoal;
        private TeamConfig capturedConfig;
        private List<String> capturedRoleIds;

        StubSwarmCoordinator() {
            super(
                new ExpertTeamCoordinator(
                    null,
                    new SimpleEvaluationStrategy(),
                    null,
                    new DefaultPlanner(new ExpertRoleRegistry(), null, null),
                    new ExpertRoleRegistry()),
                new ExpertRoleRegistry(),
                new NoopMessageBus(),
                List.of());
        }

        void willReturn(TeamResult result) {
            this.cannedResult = Mono.just(result);
        }

        void willError(Throwable ex) {
            this.cannedResult = Mono.error(ex);
        }

        @Override
        public Mono<TeamResult> startExpertTeam(String goal, TeamConfig config, List<String> roleIds) {
            this.capturedGoal = goal;
            this.capturedConfig = config;
            this.capturedRoleIds = roleIds;
            return cannedResult;
        }
    }

    // ── Helpers ──

    private static EvaluationVerdict passVerdict() {
        return new EvaluationVerdict(VerdictOutcome.PASS, 1.0, "", List.of(), Instant.now());
    }

    // ── Tests ──

    @Test
    void successPath_goalProvided_returnsSynthesizedOutput() {
        StubSwarmCoordinator coordinator = new StubSwarmCoordinator();
        coordinator.willReturn(TeamResult.of(
            "req-1",
            TeamStatus.COMPLETED,
            List.of(new StepOutcome("step-1", "code output", passVerdict(), 1)),
            "Final synthesized output",
            Duration.ofSeconds(42),
            List.of()));

        ExpertTeamTool tool = new ExpertTeamTool(coordinator);
        ToolResult result = tool.execute(Map.of("goal", "build a REST API"), null).block();

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("\"status\": \"COMPLETED\"");
        assertThat(result.content()).contains("\"output\": \"Final synthesized output\"");
        assertThat(result.content()).contains("\"stepCount\": 1");
        assertThat(result.content()).contains("\"duration\":");
        assertThat(coordinator.capturedGoal).isEqualTo("build a REST API");
    }

    @Test
    void missingGoal_returnsError() {
        StubSwarmCoordinator coordinator = new StubSwarmCoordinator();
        ExpertTeamTool tool = new ExpertTeamTool(coordinator);

        ToolResult result = tool.execute(Map.of(), null).block();

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("'goal' is required");
    }

    @Test
    void blankGoal_returnsError() {
        StubSwarmCoordinator coordinator = new StubSwarmCoordinator();
        ExpertTeamTool tool = new ExpertTeamTool(coordinator);

        ToolResult result = tool.execute(Map.of("goal", "   "), null).block();

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("'goal' is required");
    }

    @Test
    void customRoles_arePassedThrough() {
        StubSwarmCoordinator coordinator = new StubSwarmCoordinator();
        coordinator.willReturn(TeamResult.of(
            "req-2", TeamStatus.COMPLETED, List.of(),
            "done", Duration.ofSeconds(5), List.of()));

        ExpertTeamTool tool = new ExpertTeamTool(coordinator);
        List<String> roles = List.of("expert:coder", "expert:reviewer");
        tool.execute(Map.of("goal", "refactor module", "roles", roles), null).block();

        assertThat(coordinator.capturedRoleIds).containsExactly("expert:coder", "expert:reviewer");
    }

    @Test
    void customTimeout_buildsCorrectConfig() {
        StubSwarmCoordinator coordinator = new StubSwarmCoordinator();
        coordinator.willReturn(TeamResult.of(
            "req-3", TeamStatus.COMPLETED, List.of(),
            "done", Duration.ofSeconds(10), List.of()));

        ExpertTeamTool tool = new ExpertTeamTool(coordinator);
        tool.execute(Map.of(
            "goal", "optimize queries",
            "timeout_minutes", 20,
            "max_rounds", 5
        ), null).block();

        TeamConfig config = coordinator.capturedConfig;
        assertThat(config.maxFeedbackRounds()).isEqualTo(5);
        assertThat(config.teamTimeout()).isEqualTo(Duration.ofMinutes(20));
    }

    @Test
    void emptyFinalOutput_returnsWarningsFallback() {
        StubSwarmCoordinator coordinator = new StubSwarmCoordinator();
        coordinator.willReturn(TeamResult.withoutOutput(
            "req-4",
            TeamStatus.DEGRADED,
            List.of(),
            Duration.ofSeconds(30),
            List.of("Step 2 timed out", "Review loop overrun")));

        ExpertTeamTool tool = new ExpertTeamTool(coordinator);
        ToolResult result = tool.execute(Map.of("goal", "complex task"), null).block();

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("\"status\": \"DEGRADED\"");
        assertThat(result.content()).contains("Team completed with warnings:");
        assertThat(result.content()).contains("Step 2 timed out");
        assertThat(result.content()).contains("Review loop overrun");
    }

    @Test
    void exceptionInCoordinator_returnsToolResultError() {
        StubSwarmCoordinator coordinator = new StubSwarmCoordinator();
        coordinator.willError(new RuntimeException("LLM provider unavailable"));

        ExpertTeamTool tool = new ExpertTeamTool(coordinator);
        ToolResult result = tool.execute(Map.of("goal", "do something"), null).block();

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("Expert team execution failed");
        assertThat(result.content()).contains("LLM provider unavailable");
    }

    @Test
    void defaultConfig_usedWhenNoCustomParams() {
        StubSwarmCoordinator coordinator = new StubSwarmCoordinator();
        coordinator.willReturn(TeamResult.of(
            "req-5", TeamStatus.COMPLETED, List.of(),
            "done", Duration.ofSeconds(1), List.of()));

        ExpertTeamTool tool = new ExpertTeamTool(coordinator);
        tool.execute(Map.of("goal", "simple task"), null).block();

        TeamConfig config = coordinator.capturedConfig;
        TeamConfig defaults = TeamConfig.defaults();
        assertThat(config.maxFeedbackRounds()).isEqualTo(defaults.maxFeedbackRounds());
        assertThat(config.teamTimeout()).isEqualTo(defaults.teamTimeout());
    }
}
