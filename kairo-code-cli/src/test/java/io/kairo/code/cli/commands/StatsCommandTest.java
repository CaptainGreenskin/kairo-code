package io.kairo.code.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentState;
import io.kairo.api.message.Msg;
import io.kairo.code.cli.CommandRegistry;
import io.kairo.code.cli.ReplContext;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.code.core.stats.ToolUsageTracker;
import io.kairo.core.tool.DefaultPermissionGuard;
import io.kairo.core.tool.DefaultToolExecutor;
import io.kairo.core.tool.DefaultToolRegistry;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class StatsCommandTest {

    private CommandRegistry registry;
    private StringWriter outputCapture;
    private PrintWriter writer;
    private CodeAgentConfig config;

    @BeforeEach
    void setUp() {
        registry = new CommandRegistry();
        registry.register(new StatsCommand());
        outputCapture = new StringWriter();
        writer = new PrintWriter(outputCapture, true);
        config = new CodeAgentConfig("test-key", "https://api.test.com", "gpt-4o", 50, "/tmp", null);
    }

    @Test
    void statsCommandIsRegistered() {
        assertThat(registry.resolve(":stats")).isPresent();
    }

    @Test
    void noData_showsFriendlyMessage() {
        ReplContext context = createContext(new ToolUsageTracker());

        new StatsCommand().execute("", context);

        assertThat(outputCapture.toString()).contains("No tool usage recorded yet.");
    }

    @Test
    void withData_showsTableWithColumns() {
        ToolUsageTracker tracker = new ToolUsageTracker();
        // Simulate some tool usage
        tracker.onToolResult(event("bash", true, 200));
        tracker.onToolResult(event("bash", true, 300));
        tracker.onToolResult(event("bash", false, 100));
        tracker.onToolResult(event("read", true, 15));
        tracker.onToolResult(event("edit", true, 40));

        ReplContext context = createContext(tracker);

        new StatsCommand().execute("", context);

        String output = outputCapture.toString();
        assertThat(output).contains("Tool Usage Statistics");
        assertThat(output).contains("Tool");
        assertThat(output).contains("Calls");
        assertThat(output).contains("Success%");
        assertThat(output).contains("Avg(ms)");
        assertThat(output).contains("bash");
        assertThat(output).contains("read");
        assertThat(output).contains("edit");
    }

    @Test
    void withData_showsCorrectStats() {
        ToolUsageTracker tracker = new ToolUsageTracker();
        tracker.onToolResult(event("bash", true, 200));
        tracker.onToolResult(event("bash", false, 100));
        tracker.onToolResult(event("read", true, 10));

        ReplContext context = createContext(tracker);

        new StatsCommand().execute("", context);

        String output = outputCapture.toString();
        // bash: 2 calls, 50.0% success, 150 avg ms
        assertThat(output).contains("bash");
        assertThat(output).contains("50.0%");
        // read: 1 call, 100.0% success
        assertThat(output).contains("100.0%");
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private ReplContext createContext(ToolUsageTracker tracker) {
        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        DefaultToolExecutor toolExecutor =
                new DefaultToolExecutor(toolRegistry, new DefaultPermissionGuard());
        CodeAgentSession session =
                new CodeAgentSession(stubAgent(), toolExecutor, toolRegistry, Set.of(), null, tracker, null);
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

    private static io.kairo.api.hook.ToolResultEvent event(
            String tool, boolean success, long millis) {
        io.kairo.api.tool.ToolResult result =
                new io.kairo.api.tool.ToolResult("id1", "output", !success, java.util.Map.of());
        return new io.kairo.api.hook.ToolResultEvent(
                tool, result, java.time.Duration.ofMillis(millis), success);
    }
}
