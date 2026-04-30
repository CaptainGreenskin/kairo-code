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
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

class InitDoctorCommandTest {

    @TempDir
    Path tempDir;

    private StringWriter outputCapture;
    private PrintWriter writer;
    private CommandRegistry registry;

    @BeforeEach
    void setUp() {
        outputCapture = new StringWriter();
        writer = new PrintWriter(outputCapture, true);
        registry = new CommandRegistry();
    }

    // ─── InitCommand Tests ─────────────────────────────────────

    @Test
    void initCreatesAllExpectedDirectories() {
        ReplContext context = contextWithWorkDir(tempDir);

        new InitCommand().execute("", context);

        assertThat(tempDir.resolve(".kairo-code/skills")).isDirectory();
        assertThat(tempDir.resolve(".kairo-code/memory")).isDirectory();
        assertThat(tempDir.resolve(".kairo-code/sessions")).isDirectory();
    }

    @Test
    void initCreatesConfigAndHooksFiles() {
        ReplContext context = contextWithWorkDir(tempDir);

        new InitCommand().execute("", context);

        assertThat(tempDir.resolve(".kairo-code/config.properties")).isRegularFile();
        assertThat(tempDir.resolve(".kairo-code/hooks.json")).isRegularFile();
    }

    @Test
    void initIsIdempotentSecondCallPrintsAlreadyInitialized() throws IOException {
        // Pre-create the directory
        Files.createDirectories(tempDir.resolve(".kairo-code"));
        ReplContext context = contextWithWorkDir(tempDir);

        new InitCommand().execute("", context);

        assertThat(outputCapture.toString()).contains("Already initialized");
    }

    @Test
    void initWarnsWhenNoGitDirectory() {
        // tempDir has no .git directory
        ReplContext context = contextWithWorkDir(tempDir);

        new InitCommand().execute("", context);

        assertThat(outputCapture.toString()).contains("Warning: Not a git repository");
    }

    @Test
    void initSucceedsEvenWhenNoGit() {
        ReplContext context = contextWithWorkDir(tempDir);

        new InitCommand().execute("", context);

        // Should succeed (not throw)
        assertThat(outputCapture.toString()).contains("Initialized kairo-code at");
        assertThat(tempDir.resolve(".kairo-code/skills")).isDirectory();
    }

    @Test
    void initDoesNotWarnWhenGitExists() throws IOException {
        Files.createDirectories(tempDir.resolve(".git"));
        ReplContext context = contextWithWorkDir(tempDir);

        new InitCommand().execute("", context);

        assertThat(outputCapture.toString()).doesNotContain("Warning");
        assertThat(outputCapture.toString()).contains("Initialized kairo-code at");
    }

    // ─── DoctorCommand Tests ───────────────────────────────────

    @Test
    void doctorShowsApiKeyCheckMark() {
        ReplContext context = contextWithWorkDir(tempDir);

        new DoctorCommand().execute("", context);

        String output = outputCapture.toString();
        // Config has "test-key" so API key is configured
        assertThat(output).contains("\u2713 API Key configured");
    }

    @Test
    void doctorShowsJdkVersion() {
        ReplContext context = contextWithWorkDir(tempDir);

        new DoctorCommand().execute("", context);

        String output = outputCapture.toString();
        int jdkVersion = Runtime.version().feature();
        assertThat(output).contains("JDK " + jdkVersion);
    }

    @Test
    void doctorShowsInitializedCheckWhenKairoDirExists() throws IOException {
        Files.createDirectories(tempDir.resolve(".kairo-code"));
        ReplContext context = contextWithWorkDir(tempDir);

        new DoctorCommand().execute("", context);

        assertThat(outputCapture.toString()).contains("\u2713 .kairo-code/ initialized");
    }

    @Test
    void doctorShowsNotInitializedWhenKairoDirAbsent() {
        ReplContext context = contextWithWorkDir(tempDir);

        new DoctorCommand().execute("", context);

        assertThat(outputCapture.toString()).contains("\u2717 .kairo-code/ initialized");
    }

    @Test
    void doctorCompletesWithoutHanging() {
        ReplContext context = contextWithWorkDir(tempDir);

        long start = System.currentTimeMillis();
        new DoctorCommand().execute("", context);
        long elapsed = System.currentTimeMillis() - start;

        // Should complete well within 15 seconds (git/mvn checks have 5s timeout each)
        assertThat(elapsed).isLessThan(15_000);
        assertThat(outputCapture.toString()).contains("kairo-code diagnostics");
    }

    @Test
    void doctorShowsMemoryStoreWritableWhenExists() throws IOException {
        Files.createDirectories(tempDir.resolve(".kairo-code/memory"));
        ReplContext context = contextWithWorkDir(tempDir);

        new DoctorCommand().execute("", context);

        assertThat(outputCapture.toString()).contains("\u2713 Memory store writable");
    }

    @Test
    void doctorShowsMemoryStoreNotWritableWhenAbsent() {
        ReplContext context = contextWithWorkDir(tempDir);

        new DoctorCommand().execute("", context);

        assertThat(outputCapture.toString()).contains("\u2717 Memory store writable");
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private ReplContext contextWithWorkDir(Path workDir) {
        CodeAgentConfig config = new CodeAgentConfig(
                "test-key", "https://api.test.com", "gpt-4o", 50,
                workDir.toString(), null, 0, 0);
        Agent agent = stubAgent();
        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        DefaultToolExecutor toolExecutor = new DefaultToolExecutor(toolRegistry, new DefaultPermissionGuard());
        CodeAgentSession session = new CodeAgentSession(agent, toolExecutor, toolRegistry, Set.of());
        return new ReplContext(session, config, null, registry, writer, null, null, null, null);
    }

    private static Agent stubAgent() {
        return new Agent() {
            @Override
            public Mono<Msg> call(Msg input) {
                return Mono.just(Msg.of(MsgRole.ASSISTANT, "ok"));
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
                        0, 0L, List.of(), Map.of(), Instant.now());
            }
        };
    }
}
