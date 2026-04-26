package io.kairo.code.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentState;
import io.kairo.api.message.Msg;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolDefinition;
import io.kairo.code.cli.CommandRegistry;
import io.kairo.code.cli.ReplContext;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.core.tool.DefaultPermissionGuard;
import io.kairo.core.tool.DefaultToolExecutor;
import io.kairo.core.tool.DefaultToolRegistry;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class ToolsCommandTest {

    private StringWriter outputCapture;
    private PrintWriter writer;
    private CommandRegistry commandRegistry;
    private CodeAgentConfig config;

    @BeforeEach
    void setUp() {
        outputCapture = new StringWriter();
        writer = new PrintWriter(outputCapture, true);
        commandRegistry = new CommandRegistry();
        commandRegistry.register(new ToolsCommand());
        config = new CodeAgentConfig("key", "https://api.test.com", "gpt-4o", 10, "/tmp");
    }

    private ReplContext contextWithTools(ToolDefinition... tools) {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        for (ToolDefinition tool : tools) {
            registry.register(tool);
        }
        DefaultToolExecutor executor = new DefaultToolExecutor(registry, new DefaultPermissionGuard());
        CodeAgentSession session = new CodeAgentSession(stubAgent(), executor, registry, Set.of());
        return new ReplContext(session, config, null, commandRegistry, writer, null, null, null, null);
    }

    private static ToolDefinition tool(String name, String description) {
        return new ToolDefinition(name, description, ToolCategory.FILE_AND_CODE, null, null);
    }

    @Test
    void toolsCommandIsRegistered() {
        assertThat(commandRegistry.resolve(":tools")).isPresent();
    }

    @Test
    void outputContainsToolName() {
        ReplContext context = contextWithTools(tool("read_file", "Reads a file"));

        new ToolsCommand().execute("", context);

        assertThat(outputCapture.toString()).contains("read_file");
    }

    @Test
    void outputContainsToolDescription() {
        ReplContext context = contextWithTools(tool("bash", "Execute shell commands"));

        new ToolsCommand().execute("", context);

        assertThat(outputCapture.toString()).contains("Execute shell commands");
    }

    @Test
    void outputContainsToolCount() {
        ReplContext context = contextWithTools(
                tool("read_file", "Reads a file"),
                tool("write_file", "Writes a file"));

        new ToolsCommand().execute("", context);

        assertThat(outputCapture.toString()).contains("(2)");
    }

    @Test
    void noSessionPrintsNoActivSession() {
        ReplContext context = new ReplContext(
                null, config, null, commandRegistry, writer, null, null, null, null);

        new ToolsCommand().execute("", context);

        assertThat(outputCapture.toString()).contains("No active session");
    }

    private static Agent stubAgent() {
        return new Agent() {
            @Override public Mono<Msg> call(Msg input) { return Mono.empty(); }
            @Override public String id() { return "stub"; }
            @Override public String name() { return "stub-agent"; }
            @Override public AgentState state() { return AgentState.IDLE; }
            @Override public void interrupt() {}
        };
    }
}
