package io.kairo.code.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentState;
import io.kairo.api.message.Msg;
import io.kairo.code.cli.CommandRegistry;
import io.kairo.code.cli.ReplContext;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.core.tool.DefaultPermissionGuard;
import io.kairo.core.tool.DefaultToolExecutor;
import io.kairo.core.tool.DefaultToolRegistry;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

class CdCommandTest {

    @TempDir
    Path tempDir;

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
        registry.register(new CdCommand());
    }

    @Test
    void cdCommandIsRegistered() {
        assertThat(registry.resolve(":cd /tmp")).isPresent();
    }

    @Test
    void changesWorkingDirToExistingDirectory() {
        ReplContext context = createContext();
        new CdCommand().execute(tempDir.toString(), context);

        assertThat(outputCapture.toString()).contains(tempDir.toAbsolutePath().toString());
        assertThat(context.config().workingDir()).isEqualTo(tempDir.toAbsolutePath().toString());
    }

    @Test
    void rejectsNonExistentDirectory() {
        ReplContext context = createContext();
        new CdCommand().execute("/no/such/directory/xyz", context);

        assertThat(outputCapture.toString()).contains("Error:");
        assertThat(context.config().workingDir()).isEqualTo("/tmp"); // unchanged
    }

    @Test
    void noArgsShowsUsage() {
        ReplContext context = createContext();
        new CdCommand().execute("", context);

        assertThat(outputCapture.toString()).contains("Usage:");
    }

    @Test
    void nullArgsShowsUsage() {
        ReplContext context = createContext();
        new CdCommand().execute(null, context);

        assertThat(outputCapture.toString()).contains("Usage:");
    }

    private ReplContext createContext() {
        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        DefaultToolExecutor toolExecutor =
                new DefaultToolExecutor(toolRegistry, new DefaultPermissionGuard());
        Agent agent = new Agent() {
            @Override public Mono<Msg> call(Msg input) { return Mono.empty(); }
            @Override public String id() { return "stub"; }
            @Override public String name() { return "stub"; }
            @Override public AgentState state() { return AgentState.IDLE; }
            @Override public void interrupt() {}
        };
        CodeAgentSession session = new CodeAgentSession(agent, toolExecutor, toolRegistry, Set.of());
        return new ReplContext(session, config, null, registry, writer, null, null, null, null);
    }
}
