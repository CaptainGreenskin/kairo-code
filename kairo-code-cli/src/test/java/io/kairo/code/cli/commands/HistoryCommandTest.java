package io.kairo.code.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentSnapshot;
import io.kairo.api.agent.AgentState;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.code.cli.CommandRegistry;
import io.kairo.code.cli.ReplContext;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentSession;
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

class HistoryCommandTest {

    private CommandRegistry registry;
    private StringWriter outputCapture;
    private PrintWriter writer;
    private CodeAgentConfig config;

    @BeforeEach
    void setUp() {
        registry = new CommandRegistry();
        outputCapture = new StringWriter();
        writer = new PrintWriter(outputCapture, true);
        config = new CodeAgentConfig("test-key", "https://api.test.com", "gpt-4o", 50, "/tmp", null);
    }

    @Test
    void showsLastFiveTurnsByDefault() {
        List<Msg> history = buildHistory(8);
        Agent agent = agentWithHistory(history);
        ReplContext context = createContext(agent);

        new HistoryCommand().execute("", context);

        String output = outputCapture.toString();
        // 8 messages total, default limit 5 → shows [4] through [8]
        // Index 3=msg4(odd→ASSISTANT), 4=msg5(even→USER), ..., 7=msg8(odd→ASSISTANT)
        assertThat(output).contains("[4] ASSISTANT");
        assertThat(output).contains("[5] USER");
        assertThat(output).contains("[8] ASSISTANT");
        assertThat(output).doesNotContain("[3]");
    }

    @Test
    void historyWithLimit() {
        List<Msg> history = buildHistory(10);
        Agent agent = agentWithHistory(history);
        ReplContext context = createContext(agent);

        new HistoryCommand().execute("2", context);

        String output = outputCapture.toString();
        // 10 messages, limit 2 → shows [9] and [10]
        // Index 8=msg9(even→USER), 9=msg10(odd→ASSISTANT)
        assertThat(output).contains("[9] USER");
        assertThat(output).contains("[10] ASSISTANT");
        assertThat(output).doesNotContain("[8]");
    }

    @Test
    void capsAtMaxLimit() {
        List<Msg> history = buildHistory(30);
        Agent agent = agentWithHistory(history);
        ReplContext context = createContext(agent);

        new HistoryCommand().execute("25", context);

        String output = outputCapture.toString();
        // Max is 20, so from 30 messages we see [11] through [30]
        assertThat(output).contains("[11] USER");
        assertThat(output).contains("[30] ASSISTANT");
        assertThat(output).doesNotContain("[10]");
    }

    @Test
    void noHistoryWhenSnapshotUnsupported() {
        Agent agent = stubAgentNoSnapshot();
        ReplContext context = createContext(agent);

        new HistoryCommand().execute("", context);

        assertThat(outputCapture.toString()).contains("No conversation history available.");
    }

    @Test
    void noHistoryWhenEmpty() {
        Agent agent = agentWithHistory(List.of());
        ReplContext context = createContext(agent);

        new HistoryCommand().execute("", context);

        assertThat(outputCapture.toString()).contains("No conversation history available.");
    }

    @Test
    void truncatesLongContent() {
        String longContent = "A".repeat(300);
        List<Msg> history = List.of(Msg.of(MsgRole.USER, longContent));
        Agent agent = agentWithHistory(history);
        ReplContext context = createContext(agent);

        new HistoryCommand().execute("", context);

        String output = outputCapture.toString();
        assertThat(output).contains("...");
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private ReplContext createContext(Agent agent) {
        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        DefaultToolExecutor toolExecutor =
                new DefaultToolExecutor(toolRegistry, new DefaultPermissionGuard());
        CodeAgentSession session =
                new CodeAgentSession(agent, toolExecutor, toolRegistry, Set.of());
        return new ReplContext(
                session, config, null, registry, writer, null, null, null, null);
    }

    private static Agent stubAgentNoSnapshot() {
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
            public void interrupt() {
                // no-op
            }
        };
    }

    private static Agent agentWithHistory(List<Msg> history) {
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
            public void interrupt() {
                // no-op
            }

            @Override
            public AgentSnapshot snapshot() {
                return new AgentSnapshot(
                        "stub-id", "stub-agent", AgentState.IDLE,
                        0, 0, history, Map.of(), Instant.now());
            }
        };
    }

    private static List<Msg> buildHistory(int count) {
        List<Msg> history = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            MsgRole role = (i % 2 == 0) ? MsgRole.USER : MsgRole.ASSISTANT;
            history.add(Msg.of(role, "Message " + (i + 1)));
        }
        return List.copyOf(history);
    }
}
