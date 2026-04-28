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

/**
 * Tests for all slash command implementations.
 */
class SlashCommandsTest {

    private CommandRegistry registry;
    private StringWriter outputCapture;
    private PrintWriter writer;
    private CodeAgentConfig config;

    @BeforeEach
    void setUp() {
        registry = new CommandRegistry();
        registry.register(new HelpCommand());
        registry.register(new ClearCommand());
        registry.register(new ModelCommand());
        registry.register(new CostCommand());
        registry.register(new PlanCommand());
        registry.register(new SkillCommand());
        registry.register(new ExitCommand());
        registry.register(new McpCommand());

        outputCapture = new StringWriter();
        writer = new PrintWriter(outputCapture, true);
        config = new CodeAgentConfig("test-key", "https://api.test.com", "gpt-4o", 50, "/tmp", null);
    }

    // ─── :help ───────────────────────────────────────────────────

    @Test
    void helpListsAllRegisteredCommands() {
        ReplContext context = createContext(stubAgent());

        registry.resolve(":help").get().execute("", context);

        String output = outputCapture.toString();
        assertThat(output).contains(":help");
        assertThat(output).contains(":clear");
        assertThat(output).contains(":model");
        assertThat(output).contains(":cost");
        assertThat(output).contains(":plan");
        assertThat(output).contains(":exit");
    }

    // ─── :clear ──────────────────────────────────────────────────

    @Test
    void clearResetsAgent() {
        Agent originalAgent = stubAgent();
        ReplContext context = createContext(originalAgent);

        new ClearCommand().execute("", context);

        String output = outputCapture.toString();
        assertThat(output).contains("✓ Conversation cleared.");
        // Resetting rebuilds the session — the stubbed agent must be replaced.
        assertThat(context.agent()).isNotNull();
        assertThat(context.agent()).isNotSameAs(originalAgent);
    }

    // ─── :model ──────────────────────────────────────────────────

    @Test
    void modelShowsCurrentModel() {
        ReplContext context = createContext(stubAgent());

        new ModelCommand().execute("", context);

        String output = outputCapture.toString();
        assertThat(output).contains("Current model: gpt-4o");
    }

    @Test
    void modelSwitchesModel() {
        ReplContext context = createContext(stubAgent());

        new ModelCommand().execute("gpt-4o-mini", context);

        String output = outputCapture.toString();
        assertThat(output).contains("✓ Switched to model: gpt-4o-mini");
        assertThat(context.modelName()).isEqualTo("gpt-4o-mini");
    }

    // ─── :cost ───────────────────────────────────────────────────

    @Test
    void costShowsTokenCount() {
        Agent agentWithSnapshot = new StubAgentWithSnapshot(12450);
        ReplContext context = createContext(agentWithSnapshot);

        new CostCommand().execute("", context);

        String output = outputCapture.toString();
        assertThat(output).contains("Session Token Usage");
        assertThat(output).contains("Total tokens : 12,450");
    }

    @Test
    void costHandlesUnsupportedSnapshot() {
        ReplContext context = createContext(stubAgent());

        new CostCommand().execute("", context);

        String output = outputCapture.toString();
        assertThat(output).contains("Session Token Usage");
        assertThat(output).contains("Token tracking not available");
    }

    // ─── :plan ───────────────────────────────────────────────────

    @Test
    void planNoArgsShowsCurrentState() {
        ReplContext context = createContext(stubAgent());

        new PlanCommand().execute("", context);

        assertThat(outputCapture.toString()).contains("Plan mode: off");
    }

    @Test
    void planOnEntersPlanMode() {
        ReplContext context = createContext(stubAgent());

        new PlanCommand().execute("on", context);

        assertThat(outputCapture.toString()).contains("Plan mode ON");
        assertThat(context.session().toolExecutor().isPlanMode()).isTrue();
    }

    @Test
    void planOffExitsPlanMode() {
        ReplContext context = createContext(stubAgent());
        context.session().toolExecutor().setPlanMode(true);

        new PlanCommand().execute("off", context);

        assertThat(outputCapture.toString()).contains("Plan mode OFF");
        assertThat(context.session().toolExecutor().isPlanMode()).isFalse();
    }

    @Test
    void planToggleFlipsState() {
        ReplContext context = createContext(stubAgent());
        assertThat(context.session().toolExecutor().isPlanMode()).isFalse();

        new PlanCommand().execute("toggle", context);
        assertThat(context.session().toolExecutor().isPlanMode()).isTrue();

        new PlanCommand().execute("toggle", context);
        assertThat(context.session().toolExecutor().isPlanMode()).isFalse();
    }

    @Test
    void planUnknownArgPrintsUsage() {
        ReplContext context = createContext(stubAgent());

        new PlanCommand().execute("garbage", context);

        assertThat(outputCapture.toString()).contains("Usage: :plan");
    }

    // ─── :mcp ────────────────────────────────────────────────────

    @Test
    void mcpListShowsNoServersWhenNotConfigured() {
        ReplContext context = createContext(stubAgent());

        new McpCommand().execute("list", context);

        String output = outputCapture.toString();
        assertThat(output).contains("No MCP servers configured");
    }

    @Test
    void mcpDefaultSubcommandIsList() {
        ReplContext context = createContext(stubAgent());

        new McpCommand().execute("", context);

        String output = outputCapture.toString();
        assertThat(output).contains("No MCP servers configured");
    }

    // ─── :exit ───────────────────────────────────────────────────

    @Test
    void exitSetsRunningToFalse() {
        ReplContext context = createContext(stubAgent());

        assertThat(context.isRunning()).isTrue();

        new ExitCommand().execute("", context);

        String output = outputCapture.toString();
        assertThat(output).contains("Goodbye!");
        assertThat(context.isRunning()).isFalse();
    }

    // ─── CommandRegistry integration ─────────────────────────────

    @Test
    void allCommandsAreRegistered() {
        assertThat(registry.allCommands()).hasSize(8);
        assertThat(registry.allCommandNames())
                .containsExactly("help", "clear", "model", "cost", "plan", "skill", "exit", "mcp");
    }

    @Test
    void commandsResolvableWithBothPrefixes() {
        assertThat(registry.resolve(":clear")).isPresent();
        assertThat(registry.resolve("/clear")).isPresent();
        assertThat(registry.resolve(":model gpt-4o")).isPresent();
        assertThat(registry.resolve("/exit")).isPresent();
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

    /** Minimal Agent stub — snapshot not supported. */
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
            public void interrupt() {
                // no-op
            }
        };
    }

    /** Agent stub that supports snapshot with a specific token count. */
    private static class StubAgentWithSnapshot implements Agent {
        private final long tokens;

        StubAgentWithSnapshot(long tokens) {
            this.tokens = tokens;
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
        public void interrupt() {
            // no-op
        }

        @Override
        public AgentSnapshot snapshot() {
            return new AgentSnapshot(
                    "stub-id", "stub-agent", AgentState.IDLE,
                    0, tokens, List.of(), Map.of(), Instant.now());
        }
    }
}
