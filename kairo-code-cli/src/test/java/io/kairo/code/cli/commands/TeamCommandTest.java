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

import io.kairo.code.cli.CommandRegistry;
import io.kairo.code.cli.ReplContext;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.team.SwarmCoordinator;
import io.kairo.multiagent.orchestration.ExpertTeamCoordinator;
import io.kairo.multiagent.orchestration.SimpleEvaluationStrategy;
import io.kairo.multiagent.orchestration.internal.DefaultPlanner;
import io.kairo.multiagent.subagent.ExpertRoleRegistry;
import io.kairo.code.cli.testutil.NoopMessageBus;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TeamCommandTest {

    private CommandRegistry registry;
    private StringWriter outputCapture;
    private PrintWriter writer;
    private CodeAgentConfig config;

    @BeforeEach
    void setUp() {
        registry = new CommandRegistry();
        registry.register(new TeamCommand());
        outputCapture = new StringWriter();
        writer = new PrintWriter(outputCapture, true);
        config = new CodeAgentConfig(
                "test-key", "https://api.test.com", "gpt-4o", 50, "/tmp", null, 0, 0, null);
    }

    @Test
    void teamCommandRunsWithoutException() {
        ReplContext context = minimalContext();
        new TeamCommand().execute("", context);
        assertThat(outputCapture.toString()).isNotBlank();
    }

    @Test
    void outputContainsTeamHeader() {
        ReplContext context = minimalContext();
        new TeamCommand().execute("", context);
        assertThat(outputCapture.toString()).contains("Team Status");
        assertThat(outputCapture.toString()).contains("─".repeat(42));
    }

    @Test
    void noCoordinator_showsHelpMessage() {
        ReplContext context = minimalContext();
        new TeamCommand().execute("", context);
        String output = outputCapture.toString();
        assertThat(output).contains("No team API available");
    }

    @Test
    void withCoordinator_showsReadyStatus() {
        SwarmCoordinator coordinator = createCoordinator();
        ReplContext context = contextWithCoordinator(coordinator);
        new TeamCommand().execute("", context);
        String output = outputCapture.toString();
        assertThat(output).contains("Coordinator:   ready");
        assertThat(output).contains(":expert");
    }

    @Test
    void rolesSubcommand_listsRoles() {
        SwarmCoordinator coordinator = createCoordinator();
        ReplContext context = contextWithCoordinator(coordinator);
        new TeamCommand().execute("roles", context);
        String output = outputCapture.toString();
        assertThat(output).contains("Registered roles");
        assertThat(output).contains("expert:coder");
    }

    private ReplContext minimalContext() {
        return new ReplContext(null, config, null, registry, writer, null, null, null, null);
    }

    private ReplContext contextWithCoordinator(SwarmCoordinator coordinator) {
        return new ReplContext(
                null, config, null, registry, writer, null, null, null, null, null, null, null,
                null, coordinator);
    }

    private static SwarmCoordinator createCoordinator() {
        ExpertRoleRegistry roleRegistry = new ExpertRoleRegistry();
        return new SwarmCoordinator(
                new ExpertTeamCoordinator(
                        null,
                        new SimpleEvaluationStrategy(),
                        null,
                        new DefaultPlanner(roleRegistry, null, null),
                        roleRegistry),
                roleRegistry,
                new NoopMessageBus(),
                List.of());
    }
}
