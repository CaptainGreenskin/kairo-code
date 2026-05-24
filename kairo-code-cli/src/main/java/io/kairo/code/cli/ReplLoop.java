package io.kairo.code.cli;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.SnapshotStore;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.skill.SkillRegistry;
import io.kairo.code.core.config.ConfigLoader;
import io.kairo.code.cli.commands.ClearCommand;
import io.kairo.code.cli.commands.CompactCommand;
import io.kairo.code.cli.commands.CostCommand;
import io.kairo.code.cli.commands.CtxCommand;
import io.kairo.code.cli.commands.TeamCommand;
import io.kairo.code.cli.commands.DoctorCommand;
import io.kairo.code.cli.commands.EvolveCommand;
import io.kairo.code.cli.commands.ExpertCommand;
import io.kairo.code.cli.commands.ExitCommand;
import io.kairo.code.cli.commands.HelpCommand;
import io.kairo.code.cli.commands.HistoryCommand;
import io.kairo.code.cli.commands.HookCommand;
import io.kairo.code.cli.commands.InitCommand;
import io.kairo.code.cli.commands.LearnedCommand;
import io.kairo.code.cli.commands.McpCommand;
import io.kairo.code.cli.commands.McpServerCommand;
import io.kairo.code.cli.commands.MemoryCommand;
import io.kairo.code.cli.commands.ModelCommand;
import io.kairo.code.cli.commands.PlanCommand;
import io.kairo.code.cli.commands.ResumeCommand;
import io.kairo.code.cli.commands.SkillCommand;
import io.kairo.code.cli.commands.SnapshotCommand;
import io.kairo.code.cli.commands.PluginCommand;
import io.kairo.code.cli.commands.StatsCommand;
import io.kairo.code.cli.commands.MetricsCommand;
import io.kairo.code.cli.commands.SessionCommand;
import io.kairo.code.cli.commands.SwarmCommand;
import io.kairo.code.cli.commands.UsageCommand;
import io.kairo.api.memory.MemoryStore;
import io.kairo.code.core.evolution.FailurePatternTracker;
import io.kairo.code.core.evolution.LearnedLessonStore;
import io.kairo.code.core.evolution.ReflectionPipeline;
import io.kairo.code.core.evolution.ToolStrikeEvent;
import io.kairo.code.core.hook.SessionAppendHook;
import io.kairo.code.core.memory.AutoMemoryHook;
import io.kairo.code.core.stats.ToolUsageTracker;
import io.kairo.code.core.stats.TurnMetricsCollector;
import io.kairo.code.core.session.SessionWriter;
import io.kairo.core.memory.FileMemoryStore;
import io.kairo.code.cli.hooks.HookExecutor;
import io.kairo.code.cli.hooks.HooksConfig;
import io.kairo.code.cli.hooks.ShellHookListener;
import io.kairo.code.cli.task.ConsoleWorktreeMergePrompter;
import io.kairo.code.cli.task.ReplChildSessionSpawner;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.api.cron.CronScheduler;
import io.kairo.code.core.CodeAgentFactory;
import io.kairo.code.core.team.ExpertTeamFactory;
import io.kairo.code.core.team.SwarmCoordinator;
import io.kairo.api.lsp.LspService;
import io.kairo.core.health.AgentCallObserver;
import io.kairo.evolution.curator.FileSkillTelemetryStore;
import io.kairo.evolution.curator.LifecycleCuratorDaemon;
import io.kairo.lsp.DefaultLspService;
import io.kairo.lsp.registry.DefaultLanguageServerRegistry;
import io.kairo.observability.AgentMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
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
    private static final String CONTINUATION_PROMPT = "> ";
    private static final String VERSION = "0.1.0";

    /** Built-in skills shipped on the classpath under {@code skills/}. */
    private static final List<String> BUILTIN_SKILLS =
            List.of("code-review", "test-writer", "refactor", "commit-message");

    private final CodeAgentConfig config;
    private final List<Object> hooks;
    private final HooksConfig hooksConfig;
    private final boolean notificationsEnabled;
    private final AgentEventPrinter eventPrinter;
    private SkillHotReloadWatcher hotReloadWatcher;
    private final Map<String, String> skillSources = new ConcurrentHashMap<>();
    private io.kairo.api.plugin.PluginManager pluginManager;

    public ReplLoop(CodeAgentConfig config, List<Object> hooks) {
        this(config, hooks, null, true);
    }

    public ReplLoop(CodeAgentConfig config, List<Object> hooks, AgentEventPrinter eventPrinter) {
        this(config, hooks, null, true, eventPrinter);
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
        this(config, hooks, hooksConfig, notificationsEnabled, null);
    }

    public ReplLoop(
            CodeAgentConfig config,
            List<Object> hooks,
            HooksConfig hooksConfig,
            boolean notificationsEnabled,
            AgentEventPrinter eventPrinter) {
        this.config = config;
        this.hooks = hooks != null ? hooks : List.of();
        this.hooksConfig = hooksConfig != null ? hooksConfig : HooksConfig.loadDefault();
        this.notificationsEnabled = notificationsEnabled;
        this.eventPrinter = eventPrinter;
    }

    /** Run the interactive REPL. Blocks until the user exits. */
    public void run() {
        Path kairoDir = ensureKairoDir();

        try (Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .build()) {

            SkillRegistry skillRegistry = bootstrapSkillRegistry();

            // Discover plugins from global + project directories.
            discoverPlugins();

            // Load FS skills (global + project + plugin) and register them, overriding classpath by name.
            loadFsSkills(skillRegistry);

            // Start hot reload watcher for FS skill directories.
            hotReloadWatcher = createHotReloadWatcher(skillRegistry);
            if (hotReloadWatcher != null) {
                try {
                    hotReloadWatcher.start();
                } catch (IOException e) {
                    log.warn("Failed to start skill hot-reload watcher: {}", e.getMessage());
                }
            }

            SnapshotStore snapshotStore = bootstrapSnapshotStore(kairoDir);
            CommandRegistry registry = createCommandRegistry();

            final SkillRegistry completionRegistry = skillRegistry;
            LineReader lineReader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(new ReplCompleter(registry, () -> completionRegistry))
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
                    config, kairoDir, terminalReader, writer, approvalHandler, skillRegistry);

            // Output-budget tracker — per-session, used by the "+500k" / "spend 2M tokens" DSL.
            // Stays empty (no nudges) until startTurn() is called below on a user prompt that
            // carries the syntax. Living here (not inside CodeAgentFactory) so ReplLoop can both
            // hand it to the hook AND drive startTurn / endTurn from the input loop.
            final io.kairo.core.context.budget.OutputBudgetTracker outputBudgetTracker =
                    new io.kairo.core.context.budget.OutputBudgetTracker();

            // Wire shell hooks if configured. ShellHookListener bridges kairo @HookHandler events
            // to user-configured shell commands (~/.kairo-code/hooks.json).
            List<Object> allHooks = new ArrayList<>(hooks);
            allHooks.add(new io.kairo.core.context.budget.OutputBudgetHook(outputBudgetTracker));
            ShellHookListener shellHookListener = null;
            HookExecutor hookExecutor = null;
            if (!hooksConfig.isEmpty()) {
                hookExecutor = new HookExecutor(hooksConfig);
                shellHookListener = new ShellHookListener(hookExecutor);
                allHooks.add(shellHookListener);
                log.info("Loaded {} shell hooks from config",
                        hooksConfig.getAll().values().stream().mapToInt(List::size).sum());
            }

            // Wire tool usage tracker: collects per-tool call count, success rate, avg duration.
            ToolUsageTracker usageTracker = new ToolUsageTracker();
            allHooks.add(usageTracker);

            // Wire persistent memory store for cross-session memory enrichment.
            // Storage path: {workingDir}/.kairo-code/memory/ (project-local memories).
            Path memoryDir = config.workingDir() != null && !config.workingDir().isBlank()
                    ? Path.of(config.workingDir(), KAIRO_CODE_DIR, "memory")
                    : kairoDir.resolve("memory");
            MemoryStore memoryStore = new FileMemoryStore(memoryDir);
            log.debug("FileMemoryStore initialized at {}", memoryDir);

            // Wire auto-memory hook: extracts facts/preferences from model responses and saves
            // them to the memory store for cross-session enrichment.
            allHooks.add(new AutoMemoryHook(memoryStore));

            // Wire session persistence: each turn (user + assistant) is appended to a JSONL file
            // under {workingDir}/.kairo-code/sessions/<sessionId>.jsonl.
            String sessionId = UUID.randomUUID().toString().substring(0, 8);
            Path sessionsDir = config.workingDir() != null && !config.workingDir().isBlank()
                    ? Path.of(config.workingDir(), KAIRO_CODE_DIR, "sessions")
                    : kairoDir.resolve("sessions");
            Path sessionFile = sessionsDir.resolve(sessionId + ".jsonl");
            SessionWriter sessionWriter = new SessionWriter(sessionFile);
            allHooks.add(new SessionAppendHook(sessionWriter));
            log.info("Session persistence: {}", sessionFile);

            // Wire turn metrics collector: collects per-turn tool calls, success, duration.
            TurnMetricsCollector turnMetrics = new TurnMetricsCollector();
            allHooks.add(turnMetrics);

            // Wire failure pattern tracker: warns user after 3 consecutive tool failures
            // and triggers reflection pipeline to auto-generate a lesson.
            final PrintWriter failureWriter = writer;
            final LearnedLessonStore lessonStore = LearnedLessonStore.fromKairoDir(kairoDir);
            final CodeAgentConfig effectiveConfig = config;
            FailurePatternTracker failureTracker = new FailurePatternTracker(
                    event -> {
                        failureWriter.println();
                        failureWriter.println(
                                "⚠ Tool '"
                                        + event.toolName()
                                        + "' has failed 3 consecutive times."
                                        + " Use ':learned add "
                                        + event.toolName()
                                        + " <lesson>' to record what to avoid.");
                        failureWriter.println("  ✦ 正在后台生成教训，完成后用 :learned list 查看");
                        failureWriter.flush();

                        ReflectionPipeline.generateAndSave(event, effectiveConfig, lessonStore);
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
            // M-Langfuse: opt-in OTLP wiring. Done BEFORE createSession so the
            // resulting tracer can stamp every span with this REPL invocation's
            // session id — without it, Langfuse displays each chat turn as an
            // unrelated trace and has no way to roll them up.
            io.kairo.api.tracing.Tracer cliTracer = null;
            String cliSessionId = "repl-" + java.util.UUID.randomUUID();
            if (System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT") != null) {
                try {
                    if (System.getenv("OTEL_SERVICE_NAME") == null) {
                        System.setProperty("otel.service.name", "kairo-code");
                    }
                    io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
                            .initialize();
                    cliTracer = new io.kairo.code.core.observability.SessionAwareTracer(
                            io.kairo.observability.OTelTracerFactory.create(),
                            cliSessionId,
                            System.getProperty("user.name"));
                    log.info(
                            "OTLP exporter initialized → {} (session={})",
                            System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT"),
                            cliSessionId);
                } catch (Throwable t) {
                    log.warn("Failed to initialize OTLP SDK: {}", t.getMessage());
                }
            }

            CodeAgentFactory.SessionOptions baseOpts =
                    CodeAgentFactory.SessionOptions.empty()
                            .withApprovalHandler(approvalHandler)
                            .withHooks(effectiveHooks)
                            .withTaskTool(taskDeps)
                            .withTextDeltaConsumer(textDelta)
                            .withToolUsageTracker(usageTracker)
                            .withTurnMetricsCollector(turnMetrics)
                            .withMemoryStore(memoryStore)
                            .asReplSession();
            if (cliTracer != null) {
                baseOpts = baseOpts.withTracer(cliTracer);
            }
            CodeAgentSession session = CodeAgentFactory.createSession(config, baseOpts);

            // Build SwarmCoordinator on demand for :expert / :team / :swarm. Wired here at
            // REPL bootstrap so the commands aren't dead — without this every team command
            // reports "kairo-expert-team not on classpath" even though the jar IS resolved.
            // Build failures are non-fatal: the commands fall back to the same "unavailable"
            // message they showed before.
            SwarmCoordinator swarmCoordinator = buildSwarmCoordinator(config);

            // Bootstrap kairo-cron. Scheduler is created lazily-started: tasks survive across
            // restarts via the on-disk store, but the tick loop only runs after :cron start so a
            // bare REPL session doesn't fire reminders unexpectedly. Fire callback is a stub
            // logger for now — wiring fires into the agent loop is M-A4 follow-on.
            CronScheduler cronScheduler = buildCronScheduler(kairoDir, writer);

            // Bootstrap kairo-observability. SimpleMeterRegistry is in-process only (no exporter)
            // — sufficient for CLI :metrics introspection. AgentMetrics registers Micrometer
            // counters/timers and installs itself as the global AgentCallObserver so every
            // agent.call() in the session is instrumented automatically.
            MeterRegistry meterRegistry = new SimpleMeterRegistry();
            try {
                AgentMetrics agentMetrics = new AgentMetrics(meterRegistry);
                AgentCallObserver.setGlobal(agentMetrics);
            } catch (Throwable t) {
                log.warn("Failed to wire AgentMetrics: {}", t.getMessage());
            }

            // (OTLP exporter init moved above createSession so the resulting
            //  Tracer can be wrapped in SessionAwareTracer + passed via
            //  withTracer. Tools / iterations / agent.run now all carry
            //  langfuse.session.id pointing at the REPL invocation.)

            // Bootstrap upstream kairo-evolution LifecycleCuratorDaemon. Non-destructive
            // skill curation: ACTIVE → STALE → ARCHIVED based on usage telemetry. Lazy-start —
            // user opts in with :evolve curator start, mirroring the cron pattern. Self-built
            // ReflectionPipeline / LearnedLessonStore continue to handle the strike-3 lesson
            // workflow (different concern: post-failure lesson generation, not skill quality).
            LifecycleCuratorDaemon curatorDaemon = buildCuratorDaemon(kairoDir);

            // Bootstrap kairo-lsp. Lazy: subprocesses spawn only when a tool actually queries
            // diagnostics for a file with a registered language server (Pyright, JDT_LS, gopls,
            // etc). Wired here so :lsp commands AND WriteTool/EditTool's post-edit diagnostic
            // callout (M-D1') have access. The JDT_LS server entry is the upstream
            // contribution we pushed in M-D1.
            LspService lspService = buildLspService();
            // M-D1': install into LspServiceHolder so CodeAgentFactory's tool registry picks
            // up the instance-form WriteTool/EditTool with LSP wiring.
            io.kairo.code.core.LspServiceHolder.setGlobal(lspService);

            StreamingAgentRunner runner = new StreamingAgentRunner(writer, shellHookListener, hookExecutor);
            ReplContext context = new ReplContext(
                    session, config, lineReader, registry, writer,
                    opts -> opts.withApprovalHandler(approvalHandler)
                            .withHooks(effectiveHooks)
                            .withTaskTool(taskDeps)
                            .withTextDeltaConsumer(textDelta)
                            .withToolUsageTracker(usageTracker)
                            .withTurnMetricsCollector(turnMetrics)
                            .withMemoryStore(memoryStore),
                    approvalHandler,
                    skillRegistry,
                    snapshotStore,
                    hooksConfig,
                    skillSources,
                    memoryStore,
                    sessionWriter,
                    swarmCoordinator,
                    cronScheduler,
                    meterRegistry,
                    curatorDaemon,
                    lspService);
            context.setRunner(runner);

            // Wire Ctrl+C signal handler: cancel agent if running, else no-op
            terminal.handle(Terminal.Signal.INT, signal -> {
                if (runner.isRunning()) {
                    runner.cancel();
                }
            });

            printBanner(writer);

            // Resolve context window limit for the token status line.
            int contextLimit = eventPrinter != null
                    ? eventPrinter.maxContextTokens()
                    : TokenStatusLine.contextLimitForModel(config.modelName());

            // Status-line custom shell: if the user dropped a statusline.json with `command`
            // set, route through the shell renderer instead of the built-in token bar.
            // Loaded once at REPL start — changes to settings require a restart, matching
            // the rest of kairo-code's config conventions.
            Path slProjectRoot = config.workingDir() != null && !config.workingDir().isBlank()
                    ? Path.of(config.workingDir())
                    : Path.of(System.getProperty("user.dir"));
            com.fasterxml.jackson.databind.ObjectMapper slMapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            io.kairo.code.cli.statusline.StatusLineConfig statusLineCfg =
                    new io.kairo.code.cli.statusline.StatusLineConfigLoader(
                            slMapper, slProjectRoot)
                            .load();
            io.kairo.code.cli.statusline.ShellStatusLineRenderer shellRenderer =
                    new io.kairo.code.cli.statusline.ShellStatusLineRenderer(slMapper);

            InputAccumulator accumulator = new InputAccumulator();
            String currentPrompt = PROMPT;

            while (context.isRunning()) {
                // Print persistent status line above the prompt (after first response).
                if (eventPrinter != null) {
                    String statusLine;
                    if (statusLineCfg.isShellEnabled()) {
                        io.kairo.code.cli.statusline.StatusLineState state =
                                buildStatusLineState(
                                        cliSessionId,
                                        config,
                                        eventPrinter.totalInputTokens(),
                                        contextLimit,
                                        slProjectRoot);
                        statusLine = shellRenderer.render(statusLineCfg, state);
                    } else {
                        statusLine = TokenStatusLine.format(
                                eventPrinter.totalInputTokens(), contextLimit, null);
                    }
                    if (!statusLine.isEmpty()) {
                        writer.println(statusLine);
                        writer.flush();
                    }
                }

                String input;
                try {
                    input = lineReader.readLine(currentPrompt);
                } catch (UserInterruptException e) {
                    // Ctrl+C at prompt — reset accumulator if active, print new line
                    if (accumulator.isAccumulating()) {
                        accumulator.reset();
                        currentPrompt = PROMPT;
                    }
                    writer.println();
                    continue;
                } catch (EndOfFileException e) {
                    // Ctrl+D — exit
                    writer.println("Goodbye!");
                    long elapsedMs = java.time.Duration.between(
                            context.sessionStartTime(), java.time.Instant.now()).toMillis();
                    if (eventPrinter != null) {
                        eventPrinter.printSessionSummary(elapsedMs);
                    }
                    break;
                }

                if (input == null) {
                    continue;
                }

                // Multi-line accumulation: feed through InputAccumulator
                Optional<String> completed = accumulator.feed(input);
                if (completed.isEmpty()) {
                    // Still accumulating — switch to continuation prompt
                    currentPrompt = CONTINUATION_PROMPT;
                    continue;
                }
                currentPrompt = PROMPT;
                String line = completed.get();

                if (line.isBlank()) {
                    continue;
                }

                String trimmed = line.trim();

                // Shell passthrough: lines starting with ! execute a shell command
                if (trimmed.startsWith("!")) {
                    String shellCmd = trimmed.substring(1).trim();
                    if (!shellCmd.isEmpty()) {
                        executeShellCommand(shellCmd, config, writer);
                    }
                    continue;
                }

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

                // Output-budget DSL: if the prompt has "+500k" / "spend 2M tokens" syntax,
                // snapshot the tracker and feed the model the prompt with the syntax stripped.
                // Without strip, the literal "+500k" would read like a typo to the model.
                io.kairo.core.context.budget.OutputBudgetParser.ParseResult budgetParse =
                        io.kairo.core.context.budget.OutputBudgetParser.parseAndStrip(trimmed);
                String agentInput = trimmed;
                if (budgetParse.budget().isPresent()) {
                    var budget = budgetParse.budget().get();
                    outputBudgetTracker.startTurn(budget);
                    agentInput = budgetParse.strippedPrompt();
                    writer.println(
                            "\033[36m[output budget: "
                                    + formatBudget(budget.totalTokens())
                                    + " tokens — agent will keep working until ≥ 90% used]\033[0m");
                    writer.flush();
                }

                // Regular input — send to agent via StreamingAgentRunner
                // Persist user turn to session JSONL before sending to agent
                if (context.sessionWriter() != null) {
                    context.sessionWriter().appendTurn(
                            "user", agentInput, 0, java.time.Instant.now());
                }
                try {
                    executeAgentCall(agentInput, context, runner, writer);
                } finally {
                    // Clear budget so the NEXT user prompt without budget syntax doesn't
                    // inherit the previous turn's nudge loop.
                    outputBudgetTracker.endTurn();
                }
            }

            // Print session summary on normal exit (e.g., :exit command)
            long elapsedMs = java.time.Duration.between(
                    context.sessionStartTime(), java.time.Instant.now()).toMillis();
            if (eventPrinter != null) {
                eventPrinter.printSessionSummary(elapsedMs);
            }
        } catch (IOException e) {
            log.error("Failed to create terminal", e);
            System.err.println("Error: Failed to create terminal — " + e.getMessage());
        }
    }

    /**
     * Execute a shell command via {@code sh -c}, using the configured working directory.
     * Output goes directly to the terminal (inheritIO). Non-zero exit codes are printed.
     */
    private static void executeShellCommand(String command, CodeAgentConfig config,
                                             PrintWriter writer) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            String workDir = config.workingDir();
            if (workDir != null && !workDir.isBlank()) {
                pb.directory(Path.of(workDir).toFile());
            }
            pb.inheritIO();
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                writer.println("Exit code: " + exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writer.println("Shell command interrupted");
        } catch (Exception e) {
            writer.println("Shell error: " + e.getMessage());
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
        registry.register(new HistoryCommand());
        registry.register(new ClearCommand());
        registry.register(new CompactCommand());
        registry.register(new CtxCommand());
        registry.register(new TeamCommand());
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
        registry.register(new MemoryCommand());
        registry.register(new LearnedCommand());
        registry.register(new StatsCommand());
        registry.register(new MetricsCommand());
        registry.register(new SessionCommand());
        registry.register(new SwarmCommand());
        registry.register(new ExpertCommand());
        registry.register(new InitCommand());
        registry.register(new DoctorCommand());
        registry.register(new EvolveCommand());
        registry.register(new PluginCommand(pluginManager));
        registry.register(new McpServerCommand());
        registry.register(new io.kairo.code.cli.commands.CronCommand());
        registry.register(new io.kairo.code.cli.commands.LspCommand());

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
            ConsoleApprovalHandler approvalHandler,
            SkillRegistry skillRegistry) {
        Path parentRoot =
                Path.of(
                        config.workingDir() != null && !config.workingDir().isBlank()
                                ? config.workingDir()
                                : System.getProperty("user.dir"));
        WorktreeLifecycle lifecycle = new WorktreeLifecycle(kairoDir.resolve("worktrees"), "git");
        WorktreeWorkspaceProvider provider = new WorktreeWorkspaceProvider(parentRoot, lifecycle);
        ConsoleWorktreeMergePrompter prompter =
                new ConsoleWorktreeMergePrompter(terminalReader, writer);

        // Resolve chat-path for child sessions: env var > config.properties > null
        String chatPath = System.getenv("KAIRO_CODE_CHAT_PATH");
        if (chatPath == null || chatPath.isBlank()) {
            Properties fileConfig = ConfigLoader.load();
            chatPath = fileConfig.getProperty("chat-path");
        }

        Set<String> activeSkills = skillRegistry != null
                ? Set.copyOf(skillRegistry.list().stream()
                        .map(s -> s.name()).toList())
                : null;
        ReplChildSessionSpawner spawner =
                new ReplChildSessionSpawner(config, approvalHandler, writer, notificationsEnabled,
                        chatPath, skillRegistry, activeSkills);
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

    private void discoverPlugins() {
        // M-C2 migration: self-built PluginRegistry replaced by upstream
        // io.kairo.plugin.DefaultPluginManager (gains GitHub / NPM / Git source fetchers,
        // Claude-Code-compatible plugin.json format, atomic component registration).
        Path globalKairoDir = Path.of(System.getProperty("user.home"), KAIRO_CODE_DIR);
        try {
            pluginManager = io.kairo.code.core.plugin.PluginManagerFactory.create(globalKairoDir);
        } catch (Exception e) {
            log.warn("Failed to bootstrap upstream PluginManager: {}", e.getMessage());
            pluginManager = null;
        }
    }

    /**
     * Load skills from global (~/.kairo-code/skills/) and project (.kairo-code/skills/)
     * directories, plus enabled plugin skills. FS skills override classpath skills by name.
     */
    private void loadFsSkills(SkillRegistry registry) {
        Path globalDir = Path.of(System.getProperty("user.home"), KAIRO_CODE_DIR, "skills");
        Path projectDir = config.workingDir() != null && !config.workingDir().isBlank()
                ? Path.of(config.workingDir(), ".kairo-code", "skills")
                : Path.of(System.getProperty("user.dir"), ".kairo-code", "skills");

        java.util.List<Path> pluginSkillDirs =
                io.kairo.code.core.plugin.PluginManagerFactory.enabledSkillDirs(pluginManager);
        FsSkillLoader loader = new FsSkillLoader(globalDir, projectDir, pluginSkillDirs);
        for (SkillWithSource ws : loader.loadAll()) {
            registry.register(ws.skill());
            skillSources.put(ws.skill().name(), ws.priority().name());
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
            skillSources.put(ws.skill().name(), ws.priority().name());
        }
    }

    /**
     * Snapshot the runtime state into a {@link io.kairo.code.cli.statusline.StatusLineState}
     * for the shell renderer. Fields kairo can't populate (sessionName, agent.name, etc.) are
     * left null and Jackson omits them on the wire.
     */
    private static io.kairo.code.cli.statusline.StatusLineState buildStatusLineState(
            String sessionId,
            CodeAgentConfig config,
            long inputTokens,
            int contextLimit,
            Path projectRoot) {
        String modelId = config.modelName();
        return new io.kairo.code.cli.statusline.StatusLineState(
                sessionId,
                null,
                new io.kairo.code.cli.statusline.StatusLineState.ModelInfo(modelId, modelId),
                new io.kairo.code.cli.statusline.StatusLineState.WorkspaceInfo(
                        System.getProperty("user.dir"), projectRoot.toString()),
                resolveVersion(),
                io.kairo.code.cli.statusline.StatusLineState.ContextWindowInfo.from(
                        inputTokens, contextLimit, null),
                null);
    }

    private static String resolveVersion() {
        String v = ReplLoop.class.getPackage() != null
                ? ReplLoop.class.getPackage().getImplementationVersion()
                : null;
        return v != null ? v : "dev";
    }

    /** Human-readable form of an output-budget for the REPL banner: 500_000 → "500k", 2_000_000 → "2m". */
    static String formatBudget(long tokens) {
        if (tokens >= 1_000_000_000L) {
            return (tokens / 1_000_000_000L) + "b";
        }
        if (tokens >= 1_000_000L) {
            return (tokens / 1_000_000L) + "m";
        }
        if (tokens >= 1_000L) {
            return (tokens / 1_000L) + "k";
        }
        return String.valueOf(tokens);
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

    /**
     * Build a {@link SwarmCoordinator} for the :expert / :team / :swarm commands. Returns null
     * if construction fails — the commands handle null gracefully by showing an unavailable
     * message. Default worker pool size is 3, matching ExpertTeamFactory's intended use.
     */
    /**
     * Bootstrap a {@link CronScheduler} backed by an on-disk task store under
     * {@code <kairoDir>/cron/}. The scheduler is NOT started — users opt-in with {@code :cron
     * start}, otherwise a fresh REPL session won't unexpectedly fire reminders left over from
     * previous sessions. Returns null on failure so {@link commands.CronCommand} can fall back
     * to an unavailable message.
     */
    private static CronScheduler buildCronScheduler(Path kairoDir, PrintWriter writer) {
        try {
            Path cronDir = kairoDir.resolve("cron");
            Files.createDirectories(cronDir);
            var store = new io.kairo.cron.CronTaskStore(cronDir);
            io.kairo.api.cron.CronFireCallback callback = task -> {
                // Stub: log fires until the agent-bridge lands (see M-A4 follow-on).
                writer.println("[cron] fired task=" + task.id() + " prompt=" + task.prompt());
                writer.flush();
            };
            return new io.kairo.cron.DefaultCronScheduler(
                    store, callback, java.time.ZoneId.systemDefault());
        } catch (Throwable t) {
            log.warn("Failed to bootstrap CronScheduler: {}", t.getMessage());
            return null;
        }
    }

    /**
     * Bootstrap the upstream {@link LifecycleCuratorDaemon} backed by a file telemetry store
     * under {@code <kairoDir>/curator/}. Daemon is NOT auto-started — user opts in with {@code
     * :evolve curator start}.
     */
    /**
     * Build a {@link LspService} pre-registered with the upstream built-in language servers
     * (Pyright, TypeScript, gopls, rust-analyzer, clangd, jdtls). Subprocesses are NOT spawned
     * here — the service lazily starts a server only when a tool first calls
     * {@code snapshotBaseline}/{@code currentDiagnostics} on a file the registry can route.
     */
    private static LspService buildLspService() {
        try {
            var registry = new DefaultLanguageServerRegistry().registerBuiltIns();
            return DefaultLspService.builder(registry).build();
        } catch (Throwable t) {
            log.warn("Failed to bootstrap LspService: {}", t.getMessage());
            return null;
        }
    }

    private static LifecycleCuratorDaemon buildCuratorDaemon(Path kairoDir) {
        try {
            Path curatorDir = kairoDir.resolve("curator");
            Files.createDirectories(curatorDir);
            FileSkillTelemetryStore store = new FileSkillTelemetryStore(curatorDir);
            return new LifecycleCuratorDaemon(store);
        } catch (Throwable t) {
            log.warn("Failed to bootstrap LifecycleCuratorDaemon: {}", t.getMessage());
            return null;
        }
    }

    private static SwarmCoordinator buildSwarmCoordinator(CodeAgentConfig config) {
        try {
            var modelProvider =
                    CodeAgentFactory.buildModelProvider(config.apiKey(), config.baseUrl());
            return ExpertTeamFactory.create(config, modelProvider, 3);
        } catch (Throwable t) {
            log.warn("Failed to bootstrap SwarmCoordinator: {}", t.getMessage());
            return null;
        }
    }
}
