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
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class SwarmCommandTest {

    private StringWriter outputCapture;
    private PrintWriter writer;
    private CodeAgentConfig config;
    private CommandRegistry registry;

    @BeforeEach
    void setUp() {
        outputCapture = new StringWriter();
        writer = new PrintWriter(outputCapture, true);
        config = new CodeAgentConfig("test-key", "https://api.test.com", "gpt-4o", 50, "/tmp", null, 0, 0, null);
        registry = new CommandRegistry();
        registry.register(new SwarmCommand());
    }

    @Test
    void swarmCommandRunsWithoutException() {
        ReplContext context = createContext(stubAgent());
        new SwarmCommand().execute("", context);
        assertThat(outputCapture.toString()).isNotBlank();
    }

    @Test
    void outputContainsSwarmHeader() {
        ReplContext context = createContext(stubAgent());
        new SwarmCommand().execute("", context);
        assertThat(outputCapture.toString()).contains("Swarm Status");
    }

    @Test
    void noActiveSwarmShowsHelpMessage() {
        ReplContext context = createContext(stubAgent());
        new SwarmCommand().execute("", context);
        String out = outputCapture.toString();
        assertThat(out).contains("No active swarm.");
        assertThat(out).contains("Web UI");
        assertThat(out).contains("Launch Swarm");
    }

    private ReplContext createContext(Agent agent) {
        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        DefaultToolExecutor toolExecutor =
                new DefaultToolExecutor(toolRegistry, new DefaultPermissionGuard());
        CodeAgentSession session =
                new CodeAgentSession(agent, toolExecutor, toolRegistry, Set.of());
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
}
