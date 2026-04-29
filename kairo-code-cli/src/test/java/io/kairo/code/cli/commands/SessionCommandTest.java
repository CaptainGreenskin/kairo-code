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
import io.kairo.api.agent.AgentSnapshot;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class SessionCommandTest {

    private CommandRegistry registry;
    private StringWriter outputCapture;
    private PrintWriter writer;
    private CodeAgentConfig config;

    @BeforeEach
    void setUp() {
        registry = new CommandRegistry();
        registry.register(new SessionCommand());
        outputCapture = new StringWriter();
        writer = new PrintWriter(outputCapture, true);
        config = new CodeAgentConfig("test-key", "https://api.test.com", "gpt-4o", 50, "/tmp", null, 0, 0);
    }

    @Test
    void sessionCommandIsRegistered() {
        assertThat(registry.resolve(":session")).isPresent();
    }

    @Test
    void noTurns_showsSessionSummaryAndNoTurnsMessage() {
        ReplContext context = createContext(new TurnMetricsCollector());

        new SessionCommand().execute("", context);

        String output = outputCapture.toString();
        assertThat(output).contains("Session Summary");
        assertThat(output).contains("No turns recorded");
        assertThat(output).contains("Turns:      0");
    }

    @Test
    void withTurns_showsDurationAndTurns() {
        TurnMetricsCollector collector = new TurnMetricsCollector();
        collector.onToolResult(toolEvent("bash", true, 200));
        collector.onToolResult(toolEvent("read", true, 50));
        collector.onPostReasoning(postReasoningEvent());

        ReplContext context = createContext(collector);

        new SessionCommand().execute("", context);

        String output = outputCapture.toString();
        assertThat(output).contains("Session Summary");
        assertThat(output).contains("Duration:");
        assertThat(output).contains("Turns:      1");
        assertThat(output).contains("Tool calls: 2");
    }

    @Test
    void withMultipleTurns_showsCorrectAverages() {
        TurnMetricsCollector collector = new TurnMetricsCollector();

        // Turn 1: 3 tool calls
        collector.onToolResult(toolEvent("bash", true, 200));
        collector.onToolResult(toolEvent("read", true, 50));
        collector.onToolResult(toolEvent("edit", true, 100));
        collector.onPostReasoning(postReasoningEvent());

        // Turn 2: 1 tool call
        collector.onToolResult(toolEvent("bash", false, 150));
        collector.onPostReasoning(postReasoningEvent());

        ReplContext context = createContext(collector);

        new SessionCommand().execute("", context);

        String output = outputCapture.toString();
        assertThat(output).contains("Turns:      2");
        assertThat(output).contains("Tool calls: 4");
        assertThat(output).contains("avg 2.0/turn");
    }

    @Test
    void outputContainsStartedTimestamp() {
        ReplContext context = createContext(new TurnMetricsCollector());

        new SessionCommand().execute("", context);

        String output = outputCapture.toString();
        assertThat(output).contains("Started:");
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

            @Override
            public AgentSnapshot snapshot() {
                return new AgentSnapshot(
                        "stub-id", "stub-agent", AgentState.IDLE,
                        0, 0L,
                        List.of(),
                        Map.of(),
                        Instant.now());
            }
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
