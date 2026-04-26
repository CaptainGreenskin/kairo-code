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

    private StringWriter outputCapture;
    private PrintWriter writer;
    private CodeAgentConfig config;
    private CommandRegistry registry;

    @BeforeEach
    void setUp() {
        outputCapture = new StringWriter();
        writer = new PrintWriter(outputCapture, true);
        config = new CodeAgentConfig("test-key", "https://api.test.com", "gpt-4o", 50, "/tmp");
        registry = new CommandRegistry();
        registry.register(new HistoryCommand());
    }

    @Test
    void historyCommandIsRegistered() {
        assertThat(registry.resolve(":history")).isPresent();
    }

    @Test
    void historyOutputContainsUserMessage() {
        Msg userMsg = Msg.of(MsgRole.USER, "Hello world");
        Agent agent = agentWithHistory(List.of(userMsg));
        ReplContext context = createContext(agent);

        new HistoryCommand().execute("", context);

        assertThat(outputCapture.toString()).contains("User: Hello world");
    }

    @Test
    void historyOutputContainsAssistantMessage() {
        Msg assistantMsg = Msg.of(MsgRole.ASSISTANT, "I can help!");
        Agent agent = agentWithHistory(List.of(assistantMsg));
        ReplContext context = createContext(agent);

        new HistoryCommand().execute("", context);

        assertThat(outputCapture.toString()).contains("Assistant: I can help!");
    }

    @Test
    void historyTruncatesLongMessages() {
        String longText = "x".repeat(200);
        Msg msg = Msg.of(MsgRole.USER, longText);
        Agent agent = agentWithHistory(List.of(msg));
        ReplContext context = createContext(agent);

        new HistoryCommand().execute("", context);

        String output = outputCapture.toString();
        assertThat(output).contains("...");
        assertThat(output).doesNotContain(longText);
    }

    @Test
    void historyShowsEmptyWhenNoHistory() {
        Agent agent = agentWithHistory(List.of());
        ReplContext context = createContext(agent);

        new HistoryCommand().execute("", context);

        assertThat(outputCapture.toString()).contains("No conversation history");
    }

    @Test
    void historyHandlesUnsupportedSnapshot() {
        ReplContext context = createContext(stubAgent());

        new HistoryCommand().execute("", context);

        assertThat(outputCapture.toString()).contains("not available");
    }

    private static Agent agentWithHistory(List<Msg> history) {
        return new Agent() {
            @Override public Mono<Msg> call(Msg input) { return Mono.empty(); }
            @Override public String id() { return "stub-id"; }
            @Override public String name() { return "stub-agent"; }
            @Override public AgentState state() { return AgentState.IDLE; }
            @Override public void interrupt() {}
            @Override public AgentSnapshot snapshot() {
                return new AgentSnapshot("stub-id", "stub-agent", AgentState.IDLE,
                        0, 0L, history, Map.of(), Instant.now());
            }
        };
    }

    private static Agent stubAgent() {
        return new Agent() {
            @Override public Mono<Msg> call(Msg input) { return Mono.empty(); }
            @Override public String id() { return "stub-id"; }
            @Override public String name() { return "stub-agent"; }
            @Override public AgentState state() { return AgentState.IDLE; }
            @Override public void interrupt() {}
        };
    }

    private ReplContext createContext(Agent agent) {
        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        DefaultToolExecutor toolExecutor =
                new DefaultToolExecutor(toolRegistry, new DefaultPermissionGuard());
        CodeAgentSession session = new CodeAgentSession(agent, toolExecutor, toolRegistry, Set.of());
        return new ReplContext(session, config, null, registry, writer, null, null, null, null);
    }
}
