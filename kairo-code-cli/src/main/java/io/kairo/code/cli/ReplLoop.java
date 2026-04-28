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
import io.kairo.code.cli.commands.HookCommand;
import io.kairo.code.cli.commands.LearnedCommand;
import io.kairo.code.cli.commands.McpCommand;
import io.kairo.code.cli.commands.ModelCommand;
import io.kairo.code.cli.commands.PlanCommand;
import io.kairo.code.cli.commands.ResumeCommand;
import io.kairo.code.cli.commands.SkillCommand;
import io.kairo.code.cli.commands.SnapshotCommand;
import io.kairo.code.cli.commands.UsageCommand;
import io.kairo.code.core.evolution.FailurePatternTracker;
import io.kairo.code.cli.hooks.HookExecutor;
import io.kairo.code.cli.hooks.HooksConfig;
import io.kairo.code.cli.hooks.ShellHookListener;
import io.kairo.code.cli.task.ConsoleWorktreeMergePrompter;
import io.kairo.code.cli.task.ReplChildSessionSpawner;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentFactory;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.code.core.ConsoleApprovalHandler;
import io.kairo.code.core.task.TaskToolDependencies;
import io.kairo.code.core.workspace.WorktreeLifecycle;
import io.kairo.code.core.workspace.WorktreeWorkspaceProvider;
import io.kairo.code.core.skill.FsSkillLoader;
import io.kairo.code.core.skill.FsSkillLoader.SkillWithSource;
import io.kairo.core.agent.snapshot.JsonFileSnapshotStore;
import io.kairo.core.session.SessionSerializer;
import io.kairo.skill.DefaultSkillRegistry;
import io.kairo.skill.SkillHotReloadWatcher;
import io.kairo.skill.SkillLoader;
import io.kairo.skill.SkillReloadEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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
    private final HooksConfig hooksConfig;
    private final boolean notificationsEnabled;
    private SkillHotReloadWatcher hotReloadWatcher;
    private final Map<String, String> skillSources = new ConcurrentHashMap<>();

    public ReplLoop(CodeAgentConfig config, List<Object> hooks) {
        this(config, hooks, null, true);
    }

    /**
     * Constructor that allows injecting a pre-loaded hooks config (useful for testing).
     */
    public ReplLoop(CodeAgentConfig config, List<Object> hooks, HooksConfig hooksConfig) {
        this(config, hooks, hooksConfig, true);
    }

    public ReplLoop(
            CodeAgentConfig config,
            List<Object> hooks,
            HooksConfig hooksConfig,
            boolean notificationsEnabled) {
        this.config = config;
        this.hooks = hooks != null ? hooks : List.of();
        this.hooksConfig = hooksConfig != null ? hooksConfig : HooksConfig.loadDefault();
        this.notificationsEnabled = notificationsEnabled;
    }

    /** Run the interactive REPL. Blocks until the user exits. */
    public void run() {
        Path kairoDir = ensureKairoDir();

        try (Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .build()) {

            SkillRegistry skillRegistry = bootstrapSkillRegistry();

            // Load FS skills (global + project) and register them, overriding classpath by name.
            loadFsSkills(skillRegistry);

            // Start hot reload watcher for FS skill directories.
            hotReloadWatcher = createHotReloadWatcher(skillRegistry);
            if (hotReloadWatcher != null) {
                hotReloadWatcher.addListener(event -> {
                    log.info("Skill reload event: {} {}", event.skillId(), event.type());
                    // The watcher already updates the registry; we just refresh source map.
                    refreshSkillSources(skillRegistry);
                });
                try {
                    hotReloadWatcher.start();
                } catch (IOException e) {
                    log.warn("Failed to start skill hot-reload watcher: {}", e.getMessage());
                }
            }

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

            // Wire shell hooks if configured. ShellHookListener bridges kairo @HookHandler events
            // to user-configured shell commands (~/.kairo-code/hooks.json).
            List<Object> allHooks = new ArrayList<>(hooks);
            ShellHookListener shellHookListener = null;
            HookExecutor hookExecutor = null;
            if (!hooksConfig.isEmpty()) {
                hookExecutor = new HookExecutor(hooksConfig);
                shellHookListener = new ShellHookListener(hookExecutor);
                allHooks.add(shellHookListener);
                log.info("Loaded {} shell hooks from config",
                        hooksConfig.getAll().values().stream().mapToInt(List::size).sum());
            }

            // Wire failure pattern tracker: warns user after 3 consecutive tool failures.
            final PrintWriter failureWriter = writer;
            FailurePatternTracker failureTracker = new FailurePatternTracker(
                    toolName -> {
                        failureWriter.println();
                        failureWriter.println(
                                "⚠ Tool '"
                                        + toolName
                                        + "' has failed 3 consecutive times."
                                        + " Use ':learned add "
                                        + toolName
                                        + " <lesson>' to record what to avoid.");
                        failureWriter.flush();
                    });
            allHooks.add(failureTracker);

            // Create initial session: wire approval handler + hooks + task tool at the factory
            // level so every rebuilt session (e.g., after :clear, :model, :skill) keeps the same
            // setup without callers having to re-thread them.
            final List<Object> effectiveHooks = List.copyOf(allHooks);
            // Per-token text delta consumer: prints each text chunk as it arrives from the model.
            // AgentEventPrinter.onPostReasoning is set to streamingText=true to suppress
            // double-printing.
            final java.util.function.Consumer<String> textDelta = delta -> {
                writer.print(delta);
                writer.flush();
            };
            CodeAgentFactory.SessionOptions baseOpts =
                    CodeAgentFactory.SessionOptions.empty()
                            .withApprovalHandler(approvalHandler)
                            .withHooks(effectiveHooks)
                            .withTaskTool(taskDeps)
                            .withTextDeltaConsumer(textDelta);
            CodeAgentSession session = CodeAgentFactory.createSession(config, baseOpts);

            StreamingAgentRunner runner = new StreamingAgentRunner(writer, shellHookListener, hookExecutor);
            ReplContext context = new ReplContext(
                    session, config, lineReader, registry, writer,
                    opts -> opts.withApprovalHandler(approvalHandler)
                            .withHooks(effectiveHooks)
                            .withTaskTool(taskDeps)
                            .withTextDeltaConsumer(textDelta),
                    approvalHandler,
                    skillRegistry,
                    snapshotStore,
                    hooksConfig,
                    skillSources);
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
        registry.register(new HookCommand());
        registry.register(new McpCommand());
        registry.register(new LearnedCommand());

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
                new ReplChildSessionSpawner(config, approvalHandler, writer, notificationsEnabled);
        return new TaskToolDependencies(provider, spawner, prompter);
    }

    /**
     * Build a {@link SkillRegistry} pre-loaded with the built-in skills shipped under
     * {@code skills/} on the classpath. Skill load failures are logged at WARN — startup must
     * never abort because of a malformed bundled markdown file.
     */
    private SkillRegistry bootstrapSkillRegistry() {
        DefaultSkillRegistry registry = new DefaultSkillRegistry();
        for (String name : BUILTIN_SKILLS) {
            String resourcePath = "skills/" + name + ".md";
            try {
                registry.loadFromClasspath(resourcePath).block();
            } catch (Exception e) {
                log.warn("Failed to load built-in skill '{}': {}", name, e.getMessage());
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

    /**
     * Load skills from global (~/.kairo-code/skills/) and project (.kairo-code/skills/)
     * directories. FS skills override classpath skills by name.
     */
    private void loadFsSkills(SkillRegistry registry) {
        Path globalDir = Path.of(System.getProperty("user.home"), KAIRO_CODE_DIR, "skills");
        Path projectDir = config.workingDir() != null && !config.workingDir().isBlank()
                ? Path.of(config.workingDir(), ".kairo-code", "skills")
                : Path.of(System.getProperty("user.dir"), ".kairo-code", "skills");

        FsSkillLoader loader = new FsSkillLoader(globalDir, projectDir);
        for (SkillWithSource ws : loader.loadAll()) {
            registry.register(ws.skill());
            skillSources.put(ws.skill().name(), ws.source());
        }

        // Mark classpath skills that weren't overridden
        for (String name : BUILTIN_SKILLS) {
            skillSources.putIfAbsent(name, "classpath");
        }
    }

    /**
     * Create a hot-reload watcher for the global and project skill directories.
     * Returns null if neither directory exists.
     */
    private SkillHotReloadWatcher createHotReloadWatcher(SkillRegistry registry) {
        Path globalDir = Path.of(System.getProperty("user.home"), KAIRO_CODE_DIR, "skills");
        Path projectDir = config.workingDir() != null && !config.workingDir().isBlank()
                ? Path.of(config.workingDir(), ".kairo-code", "skills")
                : Path.of(System.getProperty("user.dir"), ".kairo-code", "skills");

        Path dirToWatch = Files.isDirectory(projectDir) ? projectDir
                : Files.isDirectory(globalDir) ? globalDir
                : null;

        if (dirToWatch == null) {
            return null;
        }

        SkillLoader skillLoader = new SkillLoader(registry);
        return new SkillHotReloadWatcher(dirToWatch, skillLoader, registry);
    }

    /**
     * Refresh the skill source map after a hot-reload event.
     */
    private void refreshSkillSources(SkillRegistry registry) {
        Path globalDir = Path.of(System.getProperty("user.home"), KAIRO_CODE_DIR, "skills");
        Path projectDir = config.workingDir() != null && !config.workingDir().isBlank()
                ? Path.of(config.workingDir(), ".kairo-code", "skills")
                : Path.of(System.getProperty("user.dir"), ".kairo-code", "skills");

        FsSkillLoader loader = new FsSkillLoader(globalDir, projectDir);
        for (SkillWithSource ws : loader.loadAll()) {
            skillSources.put(ws.skill().name(), ws.source());
        }
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
