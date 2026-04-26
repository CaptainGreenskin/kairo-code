package io.kairo.code.cli;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.SnapshotStore;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.skill.SkillRegistry;
import io.kairo.code.cli.commands.ClearCommand;
import io.kairo.code.cli.commands.CostCommand;
import io.kairo.code.cli.commands.ExitCommand;
import io.kairo.code.cli.commands.HelpCommand;
import io.kairo.code.cli.commands.ModelCommand;
import io.kairo.code.cli.commands.PlanCommand;
import io.kairo.code.cli.commands.ResumeCommand;
import io.kairo.code.cli.commands.SkillCommand;
import io.kairo.code.cli.commands.SnapshotCommand;
import io.kairo.code.cli.commands.UsageCommand;
import io.kairo.code.cli.task.ConsoleWorktreeMergePrompter;
import io.kairo.code.cli.task.ReplChildSessionSpawner;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentFactory;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.code.core.ConsoleApprovalHandler;
import io.kairo.code.core.task.TaskToolDependencies;
import io.kairo.code.core.workspace.WorktreeLifecycle;
import io.kairo.code.core.workspace.WorktreeWorkspaceProvider;
import io.kairo.core.agent.snapshot.JsonFileSnapshotStore;
import io.kairo.core.session.SessionSerializer;
import io.kairo.skill.DefaultSkillRegistry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.completer.AggregateCompleter;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Interactive REPL loop for Kairo Code.
 *
 * <p>Uses JLine 3 for multi-line editing, command history, and tab completion
 * of slash commands. The loop reads user input, dispatches slash commands to the
 * {@link CommandRegistry}, and sends regular input to the agent.
 *
 * <p>Owns the lifecycle of {@link ConsoleApprovalHandler}: creates it with
 * JLine's terminal I/O channels so approval prompts go through JLine (not raw
 * System.in/out), and the handler's interruptible reader works with terminal signals.
 */
public class ReplLoop {

    private static final Logger log = LoggerFactory.getLogger(ReplLoop.class);
    private static final String KAIRO_CODE_DIR = ".kairo-code";
    private static final String HISTORY_FILE = "history";
    private static final String PROMPT = "kairo-code> ";
    private static final String VERSION = "0.1.0";

    /** Built-in skills shipped on the classpath under {@code skills/}. */
    private static final List<String> BUILTIN_SKILLS =
            List.of("code-review", "test-writer", "refactor", "commit-message");

    private final CodeAgentConfig config;
    private final List<Object> hooks;

    public ReplLoop(CodeAgentConfig config, List<Object> hooks) {
        this.config = config;
        this.hooks = hooks != null ? hooks : List.of();
    }

    /** Run the interactive REPL. Blocks until the user exits. */
    public void run() {
        Path kairoDir = ensureKairoDir();

        try (Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .build()) {

            SkillRegistry skillRegistry = bootstrapSkillRegistry(kairoDir);
            SnapshotStore snapshotStore = bootstrapSnapshotStore(kairoDir);
            CommandRegistry registry = createCommandRegistry();

            LineReader lineReader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(new AggregateCompleter(
                            new StringsCompleter(
                                    registry.allCommandNames().stream()
                                            .map(n -> ":" + n)
                                            .toList())))
                    .variable(LineReader.HISTORY_FILE, kairoDir.resolve(HISTORY_FILE))
                    .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                    .build();

            PrintWriter writer = terminal.writer();

            // Create approval handler wired to JLine's terminal I/O.
            // This ensures prompts go through JLine (not raw System.in/out)
            // and the handler's interruptible reader works with terminal signals.
            BufferedReader terminalReader = new BufferedReader(
                    new InputStreamReader(terminal.input()));
            ConsoleApprovalHandler approvalHandler =
                    new ConsoleApprovalHandler(terminalReader, writer);

            // Wire TaskTool dependencies. The provider is rooted at the user's working dir; if
            // that dir is not a git repo, the provider transparently falls back to NONE
            // isolation (parent workspace) for any sub-tasks. The merge prompter and child
            // spawner reuse the same approval handler + terminal I/O as the parent.
            TaskToolDependencies taskDeps = buildTaskDependencies(
                    config, kairoDir, terminalReader, writer, approvalHandler);

            // Create initial session: wire approval handler + hooks + task tool at the factory
            // level so every rebuilt session (e.g., after :clear, :model, :skill) keeps the same
            // setup without callers having to re-thread them.
            CodeAgentFactory.SessionOptions baseOpts =
                    CodeAgentFactory.SessionOptions.empty()
                            .withApprovalHandler(approvalHandler)
                            .withHooks(hooks)
                            .withTaskTool(taskDeps);
            CodeAgentSession session = CodeAgentFactory.createSession(config, baseOpts);

            StreamingAgentRunner runner = new StreamingAgentRunner(writer);
            ReplContext context = new ReplContext(
                    session, config, lineReader, registry, writer,
                    opts -> opts.withApprovalHandler(approvalHandler)
                            .withHooks(hooks)
                            .withTaskTool(taskDeps),
                    approvalHandler,
                    skillRegistry,
                    snapshotStore);
            context.setRunner(runner);

            // Wire Ctrl+C signal handler: cancel agent if running, else no-op
            terminal.handle(Terminal.Signal.INT, signal -> {
                if (runner.isRunning()) {
                    runner.cancel();
                }
            });

            printBanner(writer);

            while (context.isRunning()) {
                String input;
                try {
                    input = lineReader.readLine(PROMPT);
                } catch (UserInterruptException e) {
                    // Ctrl+C at prompt — just print a new line and continue
                    writer.println();
                    continue;
                } catch (EndOfFileException e) {
                    // Ctrl+D — exit
                    writer.println("Goodbye!");
                    break;
                }

                if (input == null || input.isBlank()) {
                    continue;
                }

                String trimmed = input.trim();

                // Check for slash commands
                Optional<SlashCommand> command = registry.resolve(trimmed);
                if (command.isPresent()) {
                    String args = CommandRegistry.extractArgs(trimmed);
                    try {
                        command.get().execute(args, context);
                    } catch (Exception e) {
                        writer.println("Error executing command: " + e.getMessage());
                        log.debug("Command execution error", e);
                    }
                    continue;
                }

                // Regular input — send to agent via StreamingAgentRunner
                executeAgentCall(trimmed, context, runner, writer);
            }
        } catch (IOException e) {
            log.error("Failed to create terminal", e);
            System.err.println("Error: Failed to create terminal — " + e.getMessage());
        }
    }

    /**
     * Execute an agent call using the StreamingAgentRunner, with proper
     * state tracking and error handling.
     */
    private void executeAgentCall(String input, ReplContext context,
                                   StreamingAgentRunner runner, PrintWriter writer) {
        try {
            Msg userMsg = Msg.of(MsgRole.USER, input);
            Agent currentAgent = context.agent();
            Msg response = runner.run(userMsg, currentAgent);
            // The POST_REASONING hook already prints the model's text response
            // during execution, so we don't re-print it here to avoid duplication.
            // If the response is null (cancelled), just return silently.
            if (response != null) {
                writer.println();
            }
        } catch (StreamingAgentRunner.AgentExecutionException e) {
            writer.println(ErrorRenderer.render(e));
            log.debug("Agent call error", e);
        } catch (Exception e) {
            writer.println(ErrorRenderer.render(e));
            log.debug("Agent call error", e);
        } finally {
            writer.flush();
        }
    }

    private CommandRegistry createCommandRegistry() {
        CommandRegistry registry = new CommandRegistry();

        // Built-in commands
        registry.register(new HelpCommand());
        registry.register(new ClearCommand());
        registry.register(new ModelCommand());
        registry.register(new CostCommand());
        registry.register(new UsageCommand());
        registry.register(new PlanCommand());
        registry.register(new SkillCommand());
        registry.register(new SnapshotCommand());
        registry.register(new ResumeCommand());
        registry.register(new ExitCommand());

        return registry;
    }

    /**
     * Build a file-backed snapshot store rooted at {@code <kairoDir>/snapshots/}. Snapshots saved
     * via {@code :snapshot save <key>} land here as {@code <key>.json}.
     */
    private SnapshotStore bootstrapSnapshotStore(Path kairoDir) {
        return new JsonFileSnapshotStore(kairoDir.resolve("snapshots"), new SessionSerializer());
    }

    /**
     * Wire the {@code task} tool's dependency bundle: a worktree-backed workspace provider rooted
     * at the user's working dir, a JLine-backed merge prompter, and a child-session spawner that
     * builds children through {@link CodeAgentFactory} with {@code asChildSession()}.
     */
    private TaskToolDependencies buildTaskDependencies(
            CodeAgentConfig config,
            Path kairoDir,
            BufferedReader terminalReader,
            PrintWriter writer,
            ConsoleApprovalHandler approvalHandler) {
        Path parentRoot =
                Path.of(
                        config.workingDir() != null && !config.workingDir().isBlank()
                                ? config.workingDir()
                                : System.getProperty("user.dir"));
        WorktreeLifecycle lifecycle = new WorktreeLifecycle(kairoDir.resolve("worktrees"), "git");
        WorktreeWorkspaceProvider provider = new WorktreeWorkspaceProvider(parentRoot, lifecycle);
        ConsoleWorktreeMergePrompter prompter =
                new ConsoleWorktreeMergePrompter(terminalReader, writer);
        ReplChildSessionSpawner spawner =
                new ReplChildSessionSpawner(config, approvalHandler, writer);
        return new TaskToolDependencies(provider, spawner, prompter);
    }

    /**
     * Build a {@link SkillRegistry} pre-loaded with built-in skills (classpath) and any
     * user-defined skills found in {@code <kairoDir>/skills/*.md}. Load failures are logged at
     * WARN — startup must never abort because of a malformed skill file.
     */
    private SkillRegistry bootstrapSkillRegistry(Path kairoDir) {
        DefaultSkillRegistry registry = new DefaultSkillRegistry();
        for (String name : BUILTIN_SKILLS) {
            String resourcePath = "skills/" + name + ".md";
            try {
                registry.loadFromClasspath(resourcePath).block();
            } catch (Exception e) {
                log.warn("Failed to load built-in skill '{}': {}", name, e.getMessage());
            }
        }
        Path userSkillsDir = kairoDir.resolve("skills");
        if (Files.isDirectory(userSkillsDir)) {
            try (var stream = Files.list(userSkillsDir)) {
                stream.filter(p -> p.toString().endsWith(".md"))
                        .sorted()
                        .forEach(p -> {
                            try {
                                registry.loadFromFile(p).block();
                                log.debug("Loaded user skill: {}", p.getFileName());
                            } catch (Exception e) {
                                log.warn("Failed to load user skill '{}': {}", p.getFileName(),
                                        e.getMessage());
                            }
                        });
            } catch (IOException e) {
                log.warn("Could not list user skills directory {}: {}", userSkillsDir,
                        e.getMessage());
            }
        }
        return registry;
    }

    private void printBanner(PrintWriter writer) {
        writer.println();
        writer.println("Kairo Code v" + VERSION + " — Same Models. Governable.");
        writer.println("Type your request, or :help for commands. :exit to quit.");
        writer.println();
        writer.flush();
    }

    private static Path ensureKairoDir() {
        Path dir = Path.of(System.getProperty("user.home"), KAIRO_CODE_DIR);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("Could not create {} directory: {}", dir, e.getMessage());
        }
        return dir;
    }
}
