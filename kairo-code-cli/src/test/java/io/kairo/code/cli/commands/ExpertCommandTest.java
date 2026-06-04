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
package io.kairo.code.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.team.EvaluationVerdict;
import io.kairo.api.team.EvaluationVerdict.VerdictOutcome;
import io.kairo.api.team.TeamConfig;
import io.kairo.api.team.TeamResult;
import io.kairo.api.team.TeamResult.StepOutcome;
import io.kairo.api.team.TeamStatus;
import io.kairo.code.cli.CommandRegistry;
import io.kairo.code.cli.ReplContext;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.team.SwarmCoordinator;
import io.kairo.multiagent.orchestration.ExpertTeamCoordinator;
import io.kairo.multiagent.orchestration.SimpleEvaluationStrategy;
import io.kairo.multiagent.orchestration.internal.DefaultPlanner;
import io.kairo.multiagent.subagent.ExpertRoleRegistry;
import io.kairo.multiagent.orchestration.tck.NoopMessageBus;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class ExpertCommandTest {

    private StringWriter outputCapture;
    private PrintWriter writer;
    private CodeAgentConfig config;
    private CommandRegistry registry;

    @BeforeEach
    void setUp() {
        outputCapture = new StringWriter();
        writer = new PrintWriter(outputCapture, true);
        config = new CodeAgentConfig(
                "test-key", "https://api.test.com", "gpt-4o", 50, "/tmp", null, 0, 0, null);
        registry = new CommandRegistry();
        registry.register(new ExpertCommand());
    }

    @Test
    void noCoordinator_printsUnavailable() {
        ReplContext context = contextWithoutCoordinator();
        new ExpertCommand().execute("build a REST API", context);
        assertThat(outputCapture.toString()).contains("Expert team unavailable");
    }

    @Test
    void blankArgs_printsUsage() {
        ReplContext context = contextWithCoordinator(stubCoordinator(null));
        new ExpertCommand().execute("", context);
        String out = outputCapture.toString();
        assertThat(out).contains(":expert <goal>");
        assertThat(out).contains(":expert plan");
        assertThat(out).contains(":expert confirm");
        assertThat(out).contains(":expert roles");
        assertThat(out).contains(":expert status");
    }

    @Test
    void execute_successPath() {
        StubSwarmCoordinator coordinator = stubCoordinator(TeamResult.of(
                "req-1", TeamStatus.COMPLETED,
                List.of(new StepOutcome("step-1", "code output", passVerdict(), 1)),
                "Final output", Duration.ofSeconds(10), List.of()));

        ReplContext context = contextWithCoordinator(coordinator);
        new ExpertCommand().execute("build a REST API", context);

        String out = outputCapture.toString();
        assertThat(out).contains("Starting expert team");
        assertThat(out).contains("COMPLETED");
        assertThat(out).contains("Final output");
    }

    @Test
    void execute_failure() {
        StubSwarmCoordinator coordinator = stubCoordinator(null);
        coordinator.willError(new RuntimeException("LLM unavailable"));

        ReplContext context = contextWithCoordinator(coordinator);
        new ExpertCommand().execute("do something", context);

        assertThat(outputCapture.toString()).contains("Expert team execution failed");
        assertThat(outputCapture.toString()).contains("LLM unavailable");
    }

    @Test
    void planSubcommand_blankGoal() {
        ReplContext context = contextWithCoordinator(stubCoordinator(null));
        new ExpertCommand().execute("plan", context);
        assertThat(outputCapture.toString()).contains("Usage: :expert plan <goal>");
    }

    @Test
    void planSubcommand_success() {
        StubSwarmCoordinator coordinator = stubCoordinator(TeamResult.withoutOutput(
                "req-2", TeamStatus.COMPLETED, List.of(),
                Duration.ofSeconds(5),
                List.of("Plan generated; awaiting confirmation")));

        ReplContext context = contextWithCoordinator(coordinator);
        new ExpertCommand().execute("plan refactor auth module", context);

        String out = outputCapture.toString();
        assertThat(out).contains("Generating expert team plan");
        assertThat(out).contains("Plan generated successfully");
        assertThat(out).contains(":expert confirm");
    }

    @Test
    void confirmSubcommand_noPendingPlan() {
        StubSwarmCoordinator coordinator = stubCoordinator(null);
        ReplContext context = contextWithCoordinator(coordinator);
        new ExpertCommand().execute("confirm", context);
        assertThat(outputCapture.toString()).contains("No pending plan");
    }

    @Test
    void rolesSubcommand_listsRoles() {
        StubSwarmCoordinator coordinator = stubCoordinator(null);
        ReplContext context = contextWithCoordinator(coordinator);
        new ExpertCommand().execute("roles", context);

        String out = outputCapture.toString();
        assertThat(out).contains("Available Expert Roles");
        assertThat(out).contains("expert:coder");
        assertThat(out).contains("expert:architect");
    }

    @Test
    void statusSubcommand_noExecution() {
        ReplContext context = contextWithCoordinator(stubCoordinator(null));
        new ExpertCommand().execute("status", context);
        assertThat(outputCapture.toString()).contains("No expert team execution recorded");
    }

    @Test
    void statusSubcommand_afterExecution() {
        StubSwarmCoordinator coordinator = stubCoordinator(TeamResult.of(
                "req-3", TeamStatus.COMPLETED, List.of(),
                "done", Duration.ofSeconds(1), List.of()));

        ReplContext context = contextWithCoordinator(coordinator);
        ExpertCommand cmd = new ExpertCommand();
        cmd.execute("quick task", context);
        outputCapture.getBuffer().setLength(0);

        cmd.execute("status", context);
        assertThat(outputCapture.toString()).contains("Last Expert Team Result");
        assertThat(outputCapture.toString()).contains("COMPLETED");
    }

    // ── Helpers ──

    private ReplContext contextWithoutCoordinator() {
        return new ReplContext(null, config, null, registry, writer, null, null, null, null);
    }

    private ReplContext contextWithCoordinator(SwarmCoordinator coordinator) {
        return new ReplContext(
                null, config, null, registry, writer, null, null, null, null,
                null, null, null, null, coordinator);
    }

    private static StubSwarmCoordinator stubCoordinator(TeamResult cannedResult) {
        StubSwarmCoordinator stub = new StubSwarmCoordinator();
        if (cannedResult != null) {
            stub.willReturn(cannedResult);
        }
        return stub;
    }

    private static EvaluationVerdict passVerdict() {
        return new EvaluationVerdict(VerdictOutcome.PASS, 1.0, "", List.of(), Instant.now());
    }

    private static class StubSwarmCoordinator extends SwarmCoordinator {

        private Mono<TeamResult> cannedResult = Mono.empty();

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
        public Mono<TeamResult> startExpertTeam(
                String goal, TeamConfig config, List<String> roleIds) {
            return cannedResult;
        }

        @Override
        public Mono<TeamResult> startExpertTeam(
                String goal, TeamConfig config, List<String> roleIds, boolean planOnly) {
            return cannedResult;
        }
    }
}
