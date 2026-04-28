package io.kairo.code.cli;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentSnapshot;
import io.kairo.api.agent.SnapshotStore;
import io.kairo.api.skill.SkillRegistry;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentFactory;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.code.core.ConsoleApprovalHandler;
import java.io.PrintWriter;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.jline.reader.LineReader;

/**
 * Context object passed to slash commands, providing access to the REPL environment.
 *
 * <p>Holds a {@link CodeAgentSession} so commands like {@code :plan}, {@code :skill}, and {@code
 * :snapshot} can mutate runtime state (toggle plan mode, swap skill set, restore snapshot) without
 * digging into agent internals.
 */
public class ReplContext {

    private CodeAgentSession session;
    private CodeAgentConfig config;
    private final LineReader lineReader;
    private final CommandRegistry commandRegistry;
    private final PrintWriter writer;
    private final UnaryOperator<CodeAgentFactory.SessionOptions> sessionOptionsCustomizer;
    private final ConsoleApprovalHandler approvalHandler;
    private final SkillRegistry skillRegistry;
    private final SnapshotStore snapshotStore;
    private final Set<String> loadedSkills = new LinkedHashSet<>();
    private volatile StreamingAgentRunner runner;
    private boolean running = true;

    public ReplContext(
            CodeAgentSession session,
            CodeAgentConfig config,
            LineReader lineReader,
            CommandRegistry commandRegistry,
            PrintWriter writer,
            UnaryOperator<CodeAgentFactory.SessionOptions> sessionOptionsCustomizer,
            ConsoleApprovalHandler approvalHandler,
            SkillRegistry skillRegistry,
            SnapshotStore snapshotStore) {
        this.session = session;
        this.config = config;
        this.lineReader = lineReader;
        this.commandRegistry = commandRegistry;
        this.writer = writer;
        this.sessionOptionsCustomizer =
                sessionOptionsCustomizer != null ? sessionOptionsCustomizer : opts -> opts;
        this.approvalHandler = approvalHandler;
        this.skillRegistry = skillRegistry;
        this.snapshotStore = snapshotStore;
        if (session != null && session.loadedSkills() != null) {
            loadedSkills.addAll(session.loadedSkills());
        }
    }

    /** The current agent (delegates to session). */
    public Agent agent() {
        return session != null ? session.agent() : null;
    }

    /** The current session bundle (agent + tool runtime). */
    public CodeAgentSession session() {
        return session;
    }

    public LineReader lineReader() {
        return lineReader;
    }

    public CommandRegistry commandRegistry() {
        return commandRegistry;
    }

    public PrintWriter writer() {
        return writer;
    }

    public ConsoleApprovalHandler approvalHandler() {
        return approvalHandler;
    }

    /** The skill registry used by {@code :skill} commands. May be {@code null} if not configured. */
    public SkillRegistry skillRegistry() {
        return skillRegistry;
    }

    /** The snapshot store used by {@code :snapshot}/{@code :resume}. */
    public SnapshotStore snapshotStore() {
        return snapshotStore;
    }

    /** The set of skills currently injected into the system prompt (mutable). */
    public Set<String> loadedSkills() {
        return loadedSkills;
    }

    public CodeAgentConfig config() {
        return config;
    }

    public String modelName() {
        return config != null ? config.modelName() : "unknown";
    }

    public StreamingAgentRunner runner() {
        return runner;
    }

    public void setRunner(StreamingAgentRunner runner) {
        this.runner = runner;
    }

    public boolean isRunning() {
        return running;
    }

    public void requestExit() {
        this.running = false;
    }

    /**
     * Recreate the session with the current config + active skills, clearing conversation history.
     */
    public void resetAgent() {
        rebuildSession(null);
    }

    /**
     * Switch to a new model by updating the config and recreating the session. History is dropped.
     */
    public void setModelName(String newModelName) {
        if (config == null) {
            return;
        }
        this.config =
                new CodeAgentConfig(
                        config.apiKey(),
                        config.baseUrl(),
                        newModelName,
                        config.maxIterations(),
                        config.workingDir(),
                        config.mcpConfig());
        rebuildSession(null);
    }

    /**
     * Recreate the session with the current loaded-skill set, preserving conversation history via
     * a snapshot/restore round-trip.
     */
    public void reloadSkills() {
        if (session == null || session.agent() == null) {
            return;
        }
        AgentSnapshot snapshot;
        try {
            snapshot = session.agent().snapshot();
        } catch (UnsupportedOperationException e) {
            // Agent doesn't support snapshotting — just rebuild without history.
            rebuildSession(null);
            return;
        }
        rebuildSession(snapshot);
    }

    /** Restore the session from a previously saved snapshot. Loaded skills are preserved. */
    public void restoreFromSnapshot(AgentSnapshot snapshot) {
        rebuildSession(snapshot);
    }

    private void rebuildSession(AgentSnapshot restoreFrom) {
        if (config == null) {
            return;
        }
        CodeAgentFactory.SessionOptions opts = CodeAgentFactory.SessionOptions.empty();
        if (skillRegistry != null) {
            opts = opts.withSkills(skillRegistry, Set.copyOf(loadedSkills));
        }
        if (restoreFrom != null) {
            opts = opts.withRestoreFrom(restoreFrom);
        }
        opts = sessionOptionsCustomizer.apply(opts);
        this.session = CodeAgentFactory.createSession(config, opts);
    }
}
