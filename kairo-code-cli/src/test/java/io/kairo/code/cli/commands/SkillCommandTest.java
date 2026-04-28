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

class SkillCommandTest {

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
        config = new CodeAgentConfig("test-key", "https://api.test.com", "gpt-4o", 50, "/tmp", null);
        commandRegistry = new CommandRegistry();
    }

    @Test
    void listShowsAllRegisteredSkills() {
        ReplContext context = createContext();

        new SkillCommand().execute("list", context);

        String output = outputCapture.toString();
        assertThat(output).contains("Available skills:");
        assertThat(output).contains("code-review");
        assertThat(output).contains("test-writer");
    }

    @Test
    void listMarksLoadedSkills() {
        ReplContext context = createContext();
        context.loadedSkills().add("code-review");

        new SkillCommand().execute("list", context);

        String output = outputCapture.toString();
        // ● for loaded, ○ for available
        assertThat(output).contains("● code-review");
        assertThat(output).contains("○ test-writer");
    }

    @Test
    void loadedShowsEmptyMessage() {
        ReplContext context = createContext();

        new SkillCommand().execute("loaded", context);

        assertThat(outputCapture.toString()).contains("No skills currently loaded");
    }

    @Test
    void loadActivatesSkill() {
        ReplContext context = createContext();

        new SkillCommand().execute("load code-review", context);

        assertThat(outputCapture.toString()).contains("✓ Loaded skill: code-review");
        assertThat(context.loadedSkills()).contains("code-review");
    }

    @Test
    void loadUnknownSkillReports() {
        ReplContext context = createContext();

        new SkillCommand().execute("load nonexistent", context);

        assertThat(outputCapture.toString()).contains("Unknown skill: nonexistent");
        assertThat(context.loadedSkills()).doesNotContain("nonexistent");
    }

    @Test
    void loadAlreadyLoadedSkill() {
        ReplContext context = createContext();
        context.loadedSkills().add("code-review");

        new SkillCommand().execute("load code-review", context);

        assertThat(outputCapture.toString()).contains("Skill already loaded: code-review");
    }

    @Test
    void unloadRemovesSkill() {
        ReplContext context = createContext();
        context.loadedSkills().add("code-review");

        new SkillCommand().execute("unload code-review", context);

        assertThat(outputCapture.toString()).contains("✓ Unloaded skill: code-review");
        assertThat(context.loadedSkills()).doesNotContain("code-review");
    }

    @Test
    void unloadSkillNotLoaded() {
        ReplContext context = createContext();

        new SkillCommand().execute("unload code-review", context);

        assertThat(outputCapture.toString()).contains("Skill not loaded: code-review");
    }

    @Test
    void noArgsShowsUsage() {
        ReplContext context = createContext();

        new SkillCommand().execute("", context);

        assertThat(outputCapture.toString()).contains("Usage: :skill");
    }

    @Test
    void unknownSubcommandShowsUsage() {
        ReplContext context = createContext();

        new SkillCommand().execute("garbage", context);

        assertThat(outputCapture.toString()).contains("Usage: :skill");
    }

    @Test
    void noRegistryReportsUnavailable() {
        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        DefaultToolExecutor executor =
                new DefaultToolExecutor(toolRegistry, new DefaultPermissionGuard());
        CodeAgentSession session =
                new CodeAgentSession(stubAgent(), executor, toolRegistry, Set.of());
        ReplContext context = new ReplContext(
                session, config, null, commandRegistry, writer, null, null, null, null, null, null);

        new SkillCommand().execute("list", context);

        assertThat(outputCapture.toString()).contains("Skills unavailable");
    }

    @Test
    void listShowsSourceColumn() {
        ReplContext context = createContext(Map.of(
                "code-review", "classpath",
                "test-writer", "global"));

        new SkillCommand().execute("list", context);

        String output = outputCapture.toString();
        assertThat(output).contains("[classpath]");
        assertThat(output).contains("[global]");
    }

    @Test
    void listShowsProjectSourceColumn() {
        ReplContext context = createContext(Map.of(
                "code-review", "project"));

        new SkillCommand().execute("list", context);

        String output = outputCapture.toString();
        assertThat(output).contains("[project]");
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
