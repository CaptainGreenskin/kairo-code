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

class UsageCommandTest {

    private StringWriter outputCapture;
    private PrintWriter writer;
    private CodeAgentConfig config;
    private CommandRegistry registry;

    @BeforeEach
    void setUp() {
        outputCapture = new StringWriter();
        writer = new PrintWriter(outputCapture, true);
        config = new CodeAgentConfig("test-key", "https://api.test.com", "gpt-4o", 50, "/tmp", null);
        registry = new CommandRegistry();
        registry.register(new UsageCommand());
    }

    @Test
    void usageCommandIsRegistered() {
        assertThat(registry.resolve(":usage")).isPresent();
    }

    @Test
    void usageOutputContainsTotalTokens() {
        Agent agent = new StubAgentWithSnapshot(3000, 5);
        ReplContext context = createContext(agent);

        new UsageCommand().execute("", context);

        assertThat(outputCapture.toString()).contains("total_tokens=3000");
    }

    @Test
    void usageOutputContainsIterations() {
        Agent agent = new StubAgentWithSnapshot(3000, 5);
        ReplContext context = createContext(agent);

        new UsageCommand().execute("", context);

        assertThat(outputCapture.toString()).contains("iterations=5");
    }

    @Test
    void usageHandlesUnsupportedSnapshot() {
        ReplContext context = createContext(stubAgent());

        new UsageCommand().execute("", context);

        assertThat(outputCapture.toString()).contains("not available");
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

    private static class StubAgentWithSnapshot implements Agent {
        private final long tokens;
        private final int iterations;

        StubAgentWithSnapshot(long tokens, int iterations) {
            this.tokens = tokens;
            this.iterations = iterations;
        }

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
                    iterations, tokens, List.of(), Map.of(), Instant.now());
        }
    }
}
