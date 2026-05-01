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

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentState;
import io.kairo.api.message.Msg;
import io.kairo.code.cli.CommandRegistry;
import io.kairo.code.cli.ReplContext;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.code.core.stats.TurnMetricsCollector;
import io.kairo.core.tool.DefaultPermissionGuard;
import io.kairo.core.tool.DefaultToolExecutor;
import io.kairo.core.tool.DefaultToolRegistry;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class CtxCommandTest {

    private CommandRegistry registry;
    private StringWriter outputCapture;
    private PrintWriter writer;
    private CodeAgentConfig config;

    @BeforeEach
    void setUp() {
        registry = new CommandRegistry();
        registry.register(new CtxCommand());
        outputCapture = new StringWriter();
        writer = new PrintWriter(outputCapture, true);
        config = new CodeAgentConfig("test-key", "https://api.test.com", "gpt-4o", 50, "/tmp", null, 0, 0);
    }

    @Test
    void ctxCommandIsRegistered() {
        assertThat(registry.resolve(":ctx")).isPresent();
    }

    @Test
    void ctxCommandExecutesWithoutException() {
        ReplContext context = createContext(new TurnMetricsCollector());

        new CtxCommand().execute("", context);

        assertThat(outputCapture.toString()).contains("Context Window");
        assertThat(outputCapture.toString()).contains("Budget:");
        assertThat(outputCapture.toString()).contains("Phase:");
    }

    @Test
    void withNoTurns_showsZeroUsage() {
        ReplContext context = createContext(new TurnMetricsCollector());

        new CtxCommand().execute("", context);

        String output = outputCapture.toString();
        assertThat(output).contains("0%");
        assertThat(output).contains("no turns yet");
    }

    @Test
    void withTurns_showsActivityMetrics() {
        TurnMetricsCollector collector = new TurnMetricsCollector();
        collector.onToolResult(toolEvent("bash", true, 200));
        collector.onToolResult(toolEvent("bash", true, 300));
        collector.onPostReasoning(postReasoningEvent());

        ReplContext context = createContext(collector);

        new CtxCommand().execute("", context);

        String output = outputCapture.toString();
        assertThat(output).contains("Turns:");
        assertThat(output).contains("Tools:");
        assertThat(output).contains("Phase:");
    }

    @Test
    void progressBarFormatsCorrectly() {
        assertThat(CtxCommand.progressBar(0)).hasSize(20);
        assertThat(CtxCommand.progressBar(50)).hasSize(20);
        assertThat(CtxCommand.progressBar(100)).hasSize(20);
        assertThat(CtxCommand.progressBar(100)).doesNotContain("\u2591");
        assertThat(CtxCommand.progressBar(0)).doesNotContain("\u2588");
    }

    @Test
    void estimateUsagePercentReturnsCorrectRange() {
        assertThat(CtxCommand.estimateUsagePercent(0, 100_000)).isEqualTo(0);
        // 200 turns * 500 = 100,000 = 100%
        assertThat(CtxCommand.estimateUsagePercent(200, 100_000)).isEqualTo(100);
        // 1000 turns exceeds 100% — capping is done in execute(), not here
        assertThat(CtxCommand.estimateUsagePercent(1000, 100_000)).isEqualTo(500);
        assertThat(CtxCommand.estimateUsagePercent(0, 0)).isEqualTo(0);
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private ReplContext createContext(TurnMetricsCollector collector) {
        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        DefaultToolExecutor toolExecutor =
                new DefaultToolExecutor(toolRegistry, new DefaultPermissionGuard());
        CodeAgentSession session =
                new CodeAgentSession(stubAgent(), toolExecutor, toolRegistry, Set.of(),
                        null, null, collector);
        return new ReplContext(
                session, config, null, registry, writer, null, null, null, null);
    }

    private static Agent stubAgent() {
        return new Agent() {
            @Override
            public Mono<Msg> call(Msg input) {
                return Mono.empty();
            }

            @Override
            public String id() {
                return "stub-id";
            }

            @Override
            public String name() {
                return "stub-agent";
            }

            @Override
            public AgentState state() {
                return AgentState.IDLE;
            }

            @Override
            public void interrupt() {}
        };
    }

    private static io.kairo.api.hook.ToolResultEvent toolEvent(
            String tool, boolean success, long millis) {
        io.kairo.api.tool.ToolResult result =
                new io.kairo.api.tool.ToolResult("id1", "output", !success, java.util.Map.of());
        return new io.kairo.api.hook.ToolResultEvent(
                tool, result, java.time.Duration.ofMillis(millis), success);
    }

    private static io.kairo.api.hook.PostReasoningEvent postReasoningEvent() {
        return new io.kairo.api.hook.PostReasoningEvent(null, false);
    }
}
