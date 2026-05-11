package io.kairo.code.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentState;
import io.kairo.api.message.Msg;
import io.kairo.api.skill.SkillCategory;
import io.kairo.api.skill.SkillDefinition;
import io.kairo.api.skill.SkillRegistry;
import io.kairo.code.cli.CommandRegistry;
import io.kairo.code.cli.ReplContext;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.core.tool.DefaultPermissionGuard;
import io.kairo.core.tool.DefaultToolExecutor;
import io.kairo.core.tool.DefaultToolRegistry;
import io.kairo.skill.DefaultSkillRegistry;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class EvolveCommandTest {

    private SkillRegistry skillRegistry;
    private StringWriter outputCapture;
    private PrintWriter writer;
    private CodeAgentConfig config;
    private CommandRegistry commandRegistry;

    @BeforeEach
    void setUp() {
        skillRegistry = new DefaultSkillRegistry();
        skillRegistry.register(skillDef("code-review", "Walk the diff and produce findings."));
        skillRegistry.register(skillDef("test-writer", "Write disciplined tests."));

        outputCapture = new StringWriter();
        writer = new PrintWriter(outputCapture, true);
        config = new CodeAgentConfig("test-key", "https://api.test.com", "gpt-4o", 50, "/tmp", null, 0, 0, null);
        commandRegistry = new CommandRegistry();
        commandRegistry.register(new EvolveCommand());
    }

    @Test
    void evolveCommandIsRegistered() {
        assertThat(commandRegistry.resolve(":evolve")).isPresent();
    }

    @Test
    void evolveCommandRunsWithoutException() {
        ReplContext context = createContext();

        new EvolveCommand().execute("", context);

        // Should complete without throwing
        assertThat(outputCapture.toString()).isNotEmpty();
    }

    @Test
    void outputContainsSkillListHeader() {
        ReplContext context = createContext();

        new EvolveCommand().execute("", context);

        assertThat(outputCapture.toString()).contains("Available skills:");
    }

    @Test
    void outputContainsNewSkillAfterRegistration() {
        ReplContext context = createContext();

        new EvolveCommand().execute("", context);

        String output = outputCapture.toString();
        assertThat(output).contains("format-json");
        assertThat(output).contains("Self-evolution complete");
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private ReplContext createContext() {
        return createContext(Map.of());
    }

    private ReplContext createContext(Map<String, String> skillSources) {
        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        DefaultToolExecutor executor =
                new DefaultToolExecutor(toolRegistry, new DefaultPermissionGuard());
        CodeAgentSession session =
                new CodeAgentSession(stubAgent(), executor, toolRegistry, Set.of());
        return new ReplContext(
                session,
                config,
                null,
                commandRegistry,
                writer,
                null,
                null,
                skillRegistry,
                null,
                null,
                skillSources);
    }

    private static SkillDefinition skillDef(String name, String description) {
        return new SkillDefinition(
                name,
                "1.0.0",
                description,
                "# " + name + "\n\nInstructions for " + name,
                List.of(),
                SkillCategory.CODE,
                null,
                null,
                null,
                0,
                null);
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
