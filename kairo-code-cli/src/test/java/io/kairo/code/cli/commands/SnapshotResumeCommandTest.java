package io.kairo.code.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentSnapshot;
import io.kairo.api.agent.AgentState;
import io.kairo.api.agent.SnapshotStore;
import io.kairo.api.message.Msg;
import io.kairo.code.cli.CommandRegistry;
import io.kairo.code.cli.ReplContext;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.core.agent.snapshot.JsonFileSnapshotStore;
import io.kairo.core.session.SessionSerializer;
import io.kairo.core.tool.DefaultPermissionGuard;
import io.kairo.core.tool.DefaultToolExecutor;
import io.kairo.core.tool.DefaultToolRegistry;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

class SnapshotResumeCommandTest {

    @TempDir Path tempDir;

    private SnapshotStore store;
    private StringWriter outputCapture;
    private PrintWriter writer;
    private CodeAgentConfig config;
    private CommandRegistry commandRegistry;

    @BeforeEach
    void setUp() {
        store = new JsonFileSnapshotStore(tempDir, new SessionSerializer());
        outputCapture = new StringWriter();
        writer = new PrintWriter(outputCapture, true);
        config = new CodeAgentConfig("test-key", "https://api.test.com", "gpt-4o", 50, "/tmp");
        commandRegistry = new CommandRegistry();
    }

    // ─── :snapshot ──────────────────────────────────────────────

    @Test
    void snapshotSaveWritesFile() {
        ReplContext context = createContext(snapshotAgent(7, 1234));

        new SnapshotCommand().execute("save my-session", context);

        String output = outputCapture.toString();
        assertThat(output).contains("✓ Saved snapshot 'my-session'");
        assertThat(output).contains("0 messages");
        assertThat(output).contains("1234 tokens");
        assertThat(tempDir.resolve("my-session.json").toFile()).exists();
    }

    @Test
    void snapshotSaveWithoutKeyShowsUsage() {
        ReplContext context = createContext(snapshotAgent(0, 0));

        new SnapshotCommand().execute("save", context);

        assertThat(outputCapture.toString()).contains("Usage: :snapshot save");
    }

    @Test
    void snapshotSaveOnUnsupportedAgentReports() {
        ReplContext context = createContext(stubAgent());

        new SnapshotCommand().execute("save anything", context);

        assertThat(outputCapture.toString())
                .contains("Cannot snapshot: agent does not support snapshotting");
    }

    @Test
    void snapshotListShowsSavedKeys() {
        ReplContext context = createContext(snapshotAgent(0, 0));
        new SnapshotCommand().execute("save first", context);
        new SnapshotCommand().execute("save second", context);
        outputCapture.getBuffer().setLength(0);

        new SnapshotCommand().execute("list", context);

        String output = outputCapture.toString();
        assertThat(output).contains("Saved snapshots:");
        assertThat(output).contains("first");
        assertThat(output).contains("second");
    }

    @Test
    void snapshotListWhenEmpty() {
        ReplContext context = createContext(stubAgent());

        new SnapshotCommand().execute("list", context);

        assertThat(outputCapture.toString()).contains("No snapshots saved");
    }

    @Test
    void snapshotDeleteRemovesFile() {
        ReplContext context = createContext(snapshotAgent(0, 0));
        new SnapshotCommand().execute("save tmp", context);
        outputCapture.getBuffer().setLength(0);

        new SnapshotCommand().execute("delete tmp", context);

        assertThat(outputCapture.toString()).contains("✓ Deleted snapshot: tmp");
        assertThat(tempDir.resolve("tmp.json").toFile()).doesNotExist();
    }

    @Test
    void snapshotNoArgsShowsUsage() {
        ReplContext context = createContext(stubAgent());

        new SnapshotCommand().execute("", context);

        assertThat(outputCapture.toString()).contains("Usage: :snapshot");
    }

    @Test
    void snapshotNoStoreReportsUnavailable() {
        ReplContext context = createContextWithoutStore();

        new SnapshotCommand().execute("list", context);

        assertThat(outputCapture.toString()).contains("Snapshots unavailable");
    }

    // ─── :resume ────────────────────────────────────────────────

    @Test
    void resumeRestoresFromSavedSnapshot() {
        // First, save a snapshot.
        ReplContext saveCtx = createContext(snapshotAgent(3, 9876));
        new SnapshotCommand().execute("save check", saveCtx);

        // Use a fresh context (different agent) to resume.
        ReplContext resumeCtx = createContext(stubAgent());
        outputCapture.getBuffer().setLength(0);

        new ResumeCommand().execute("check", resumeCtx);

        String output = outputCapture.toString();
        assertThat(output).contains("✓ Resumed from 'check'");
        assertThat(output).contains("9876 tokens");
    }

    @Test
    void resumeMissingKeyShowsNotFound() {
        ReplContext context = createContext(stubAgent());

        new ResumeCommand().execute("nonexistent", context);

        assertThat(outputCapture.toString()).contains("Snapshot not found: nonexistent");
    }

    @Test
    void resumeWithoutKeyShowsUsage() {
        ReplContext context = createContext(stubAgent());

        new ResumeCommand().execute("", context);

        assertThat(outputCapture.toString()).contains("Usage: :resume");
    }

    @Test
    void resumeNoStoreReportsUnavailable() {
        ReplContext context = createContextWithoutStore();

        new ResumeCommand().execute("anything", context);

        assertThat(outputCapture.toString()).contains("Resume unavailable");
    }

    // ─── Helpers ────────────────────────────────────────────────

    private ReplContext createContext(Agent agent) {
        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        DefaultToolExecutor executor =
                new DefaultToolExecutor(toolRegistry, new DefaultPermissionGuard());
        CodeAgentSession session =
                new CodeAgentSession(agent, executor, toolRegistry, Set.of());
        return new ReplContext(
                session, config, null, commandRegistry, writer, null, null, null, store);
    }

    private ReplContext createContextWithoutStore() {
        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        DefaultToolExecutor executor =
                new DefaultToolExecutor(toolRegistry, new DefaultPermissionGuard());
        CodeAgentSession session =
                new CodeAgentSession(stubAgent(), executor, toolRegistry, Set.of());
        return new ReplContext(
                session, config, null, commandRegistry, writer, null, null, null, null);
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

    private static Agent snapshotAgent(int iteration, long tokens) {
        return new Agent() {
            @Override
            public Mono<Msg> call(Msg input) {
                return Mono.empty();
            }

            @Override
            public String id() {
                return "snap-id";
            }

            @Override
            public String name() {
                return "snap-agent";
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
                        id(),
                        name(),
                        AgentState.IDLE,
                        iteration,
                        tokens,
                        List.of(),
                        Map.of(),
                        Instant.now());
            }
        };
    }
}
