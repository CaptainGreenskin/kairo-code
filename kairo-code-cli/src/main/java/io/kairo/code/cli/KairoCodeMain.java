package io.kairo.code.cli;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentSnapshot;
import io.kairo.api.agent.AgentState;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.model.ModelProvider;
import io.kairo.code.core.CheckpointData;
import io.kairo.code.core.CheckpointLoader;
import io.kairo.code.core.config.ConfigLoader;
import io.kairo.code.core.config.ProviderRegistry;
import io.kairo.acp.server.AcpStdioServer;
import io.kairo.acp.server.DefaultAcpAgent;
import io.kairo.api.acp.AcpCapabilities;
import io.kairo.api.acp.AcpImplementation;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentFactory;
import io.kairo.code.core.LlmClassifierConfig;
import io.kairo.code.core.mcp.McpConfig;
import io.kairo.code.core.hook.PlanModeHook;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kairo.core.model.anthropic.AnthropicProvider;
import io.kairo.core.model.openai.OpenAIProvider;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * CLI entry point for Kairo Code.
 *
 * <p>When {@code --task} is provided, executes a single task and exits (one-shot mode).
 * When no task is given, enters an interactive REPL with JLine support.
 */
@Command(
        name = "kairo-code",
        description = "Kairo Code — Same Models. Governable.",
        mixinStandardHelpOptions = true,
        version = "kairo-code 0.2.0")
public class KairoCodeMain implements Callable<Integer> {

    @Option(names = "--task", description = "Task to execute (omit for interactive REPL)")
    private String task;

    @Option(names = "--task-file", description = "Path to a Markdown file containing the task description")
    private Path taskFile;

    @Option(names = "--api-key", description = "API key (or set KAIRO_CODE_API_KEY env)")
    private String apiKey;

    @Option(names = "--model", description = "Model name", defaultValue = "gpt-4o")
    private String model;

    @Option(names = "--base-url", description = "API base URL",
            defaultValue = "https://api.openai.com")
    private String baseUrl;

    @Option(names = "--max-iterations", description = "Maximum ReAct loop iterations",
            defaultValue = "50")
    private int maxIterations;

    @Option(names = "--working-dir", description = "Working directory for tools")
    private String workingDir;

    @Option(names = "--max-retries", description = "Max retries on transient failure (0-5)",
            defaultValue = "0")
    private int maxRetries;

    @Option(names = "--timeout", description = "Task timeout in seconds (0 = no limit)",
            defaultValue = "0")
    private int timeoutSeconds;

    @Option(names = "--verbose", description = "Show step-by-step progress on stderr")
    private boolean verbose;

    @Option(names = "--provider",
            description = "Model API provider: openai (default), anthropic, qianwen, glm",
            defaultValue = "openai")
    private String provider;

    @Option(names = "--chat-path", description = "Chat completions path suffix (or set KAIRO_CODE_CHAT_PATH env)")
    private String chatPath;

    @Option(names = "--task-list",
            description = "JSON Lines file with multiple tasks to run in parallel")
    private Path taskListFile;

    @Option(names = "--output", description = "Write final response to this file instead of stdout")
    private Path outputFile;

    @Option(names = "--show-usage", description = "Print token usage stats to stderr after completion")
    private boolean showUsage;

    @Option(names = "--no-notifications", description = "Disable desktop notifications on task completion")
    private boolean noNotifications;

    @Option(names = "--tool-budget",
            description = "Max tool calls before forced wrap-up (default: 100, 0 = disabled). "
                    + "Overrides KAIRO_CODE_TOOL_BUDGET_FORCE env var.")
    private int toolBudget = 0;

    @Option(names = "--thinking-budget",
            description = "Anthropic extended thinking budget in tokens (null = disabled)")
    private Integer thinkingBudget;

    @Option(names = "--no-hooks",
            description = "Disable all auto-registered hooks (for debugging / testing)")
    private boolean noHooks = false;

    @Option(names = "--resume", description = "Resume from last checkpoint in working directory")
    private boolean resume;

    @Option(names = "--plan", description = "Show execution plan and wait for confirmation before acting")
    private boolean planMode;

    @Option(names = "--llm-classifier",
            description = "Enable the LLM-backed bash-command classifier as a fallback "
                    + "for heuristically-UNKNOWN commands inside the dangerous-command guardrail. "
                    + "Equivalent to setting llm-classifier=true in config or "
                    + "KAIRO_CODE_LLM_CLASSIFIER=true.")
    private boolean llmClassifierEnabled;

    @Option(names = "--llm-classifier-model",
            description = "Override the model used by --llm-classifier "
                    + "(defaults to the agent's primary model). "
                    + "Equivalent to llm-classifier-model in config or "
                    + "KAIRO_CODE_LLM_CLASSIFIER_MODEL.")
    private String llmClassifierModel;

    @Option(names = "--acp-server",
            description =
                    "Run as an Agent Client Protocol stdio server. Reads JSON-RPC 2.0 frames"
                            + " from stdin, writes responses + session/update notifications to"
                            + " stdout. Use this when an editor (Zed, OpenCode, ...) spawns"
                            + " kairo-code as a subprocess.")
    boolean acpServer;

    // Provider / model / base URL knowledge lives in ProviderRegistry now.
    // Don't re-declare them here — see core/config/ProviderRegistry for the
    // canonical list and how to add a new provider.

    /** Discipline prefix prepended to one-shot task prompts. */
    static final String ONE_SHOT_DISCIPLINE_PREFIX =
            "Complete this task fully. Use your tools to investigate, implement, and verify."
                    + " Do not stop after planning — execute each step with tool calls.\n\n"
                    + "When all tests pass and the task is complete, commit your changes with:\n"
                    + "  git add -A && git commit -m \"<type>(<scope>): <concise description>\"\n"
                    + "Use conventional commit format. Focus the message on WHAT changed and WHY.\n"
                    + "Do NOT include \"completed\" or \"task done\" in the message.\n\n";

    @Override
    public Integer call() {
        Properties fileConfig = ConfigLoader.load();

        // Resolve API key: CLI arg > env var > config file
        String resolvedApiKey = apiKey;
        if (resolvedApiKey == null || resolvedApiKey.isBlank()) {
            resolvedApiKey = System.getenv("KAIRO_CODE_API_KEY");
        }
        if (resolvedApiKey == null || resolvedApiKey.isBlank()) {
            resolvedApiKey = fileConfig.getProperty("api-key");
        }
        if (resolvedApiKey == null || resolvedApiKey.isBlank()) {
            System.err.println("Error: API key is required. " +
                    "Set --api-key, KAIRO_CODE_API_KEY, or api-key in ~/.kairo-code/config.properties.");
            return 1;
        }

        // Resolve provider: CLI arg > env var > config file > default (openai).
        // Normalize through ProviderRegistry so aliases (e.g. "zhipu" → "glm")
        // and case differences don't slip through to downstream code.
        String resolvedProvider = provider;
        String envProvider = System.getenv("KAIRO_CODE_PROVIDER");
        if (envProvider != null && !envProvider.isBlank() && "openai".equals(provider)) {
            resolvedProvider = envProvider;
        } else if ("openai".equals(provider) && fileConfig.getProperty("provider") != null) {
            resolvedProvider = fileConfig.getProperty("provider");
        }
        resolvedProvider = ProviderRegistry.normalizeId(resolvedProvider);
        if (!ProviderRegistry.isKnown(resolvedProvider)) {
            System.err.printf("Error: unknown provider '%s'. Valid: %s%n",
                    resolvedProvider, String.join(", ", ProviderRegistry.knownIds()));
            return 1;
        }

        // Resolve model: CLI arg > env var > config file > registry default.
        String resolvedModel = model;
        String envModel = System.getenv("KAIRO_CODE_MODEL");
        if (envModel != null && !envModel.isBlank() && "gpt-4o".equals(model)) {
            resolvedModel = envModel;
        } else if ("gpt-4o".equals(model) && fileConfig.getProperty("model") != null) {
            resolvedModel = fileConfig.getProperty("model");
        } else if ("gpt-4o".equals(model)) {
            // No explicit model — use the provider's registry default.
            resolvedModel = ProviderRegistry.defaultModel(resolvedProvider);
        }

        // Resolve base URL: CLI arg > env var > config file > registry default.
        String resolvedBaseUrl = baseUrl;
        String envBaseUrl = System.getenv("KAIRO_CODE_BASE_URL");
        if (envBaseUrl != null && !envBaseUrl.isBlank()
                && "https://api.openai.com".equals(baseUrl)) {
            resolvedBaseUrl = envBaseUrl;
        } else if ("https://api.openai.com".equals(baseUrl)
                && fileConfig.getProperty("base-url") != null) {
            resolvedBaseUrl = fileConfig.getProperty("base-url");
        } else if ("https://api.openai.com".equals(baseUrl)) {
            // User didn't override and we're on a non-openai provider — use registry default.
            resolvedBaseUrl = ProviderRegistry.resolveBaseUrl(resolvedProvider);
        }

        // Resolve chat path: CLI arg > env var > config file > null (use default)
        String resolvedChatPath = chatPath;
        String envChatPath = System.getenv("KAIRO_CODE_CHAT_PATH");
        if (envChatPath != null && !envChatPath.isBlank()
                && (resolvedChatPath == null || resolvedChatPath.isBlank())) {
            resolvedChatPath = envChatPath;
        } else if ((resolvedChatPath == null || resolvedChatPath.isBlank())
                && fileConfig.getProperty("chat-path") != null) {
            resolvedChatPath = fileConfig.getProperty("chat-path");
        }

        // Resolve working directory
        String resolvedWorkingDir = workingDir;
        if (resolvedWorkingDir == null || resolvedWorkingDir.isBlank()) {
            resolvedWorkingDir = System.getProperty("user.dir");
        }

        try {
            long mutuallyExclusiveCount = java.util.stream.Stream
                    .of(task, taskFile, taskListFile)
                    .filter(java.util.Objects::nonNull).count();
            if (mutuallyExclusiveCount > 1) {
                System.err.println(
                        "Error: --task, --task-file, and --task-list are mutually exclusive.");
                return 1;
            }

            McpConfig mcpConfig = McpConfig.loadDefault();

            LlmClassifierConfig resolvedLlmClassifier =
                    resolveLlmClassifierConfig(fileConfig);

            CodeAgentConfig config = new CodeAgentConfig(
                    resolvedApiKey, resolvedBaseUrl, resolvedModel,
                    maxIterations, resolvedWorkingDir, mcpConfig, toolBudget, 0,
                    thinkingBudget, resolvedLlmClassifier);
            ModelProvider modelProvider = buildModelProvider(
                    resolvedProvider, resolvedApiKey, resolvedBaseUrl, resolvedChatPath);

            if (taskListFile != null) {
                if (!Files.exists(taskListFile)) {
                    System.err.println("Error: task-list file not found: " + taskListFile);
                    return 1;
                }
                return new ParallelTaskRunner(config, modelProvider, timeoutSeconds, System.err)
                        .run(taskListFile);
            }

            String resolvedTask = task;
            if (taskFile != null) {
                if (!Files.exists(taskFile)) {
                    System.err.println("Error: task file not found: " + taskFile);
                    return 1;
                }
                resolvedTask = Files.readString(taskFile, StandardCharsets.UTF_8);
            }

            if (toolBudget < 0) {
                System.err.println("Error: --tool-budget must be >= 0");
                return 1;
            }

            RetryPolicy retryPolicy = new RetryPolicy(maxRetries);

            if (acpServer) {
                return runAcpServer(config, modelProvider);
            } else if (resolvedTask != null && !resolvedTask.isBlank()) {
                final String taskToRun = resolvedTask;
                return retryPolicy.execute(() -> runOneShot(config, taskToRun, modelProvider, planMode));
            } else {
                return runRepl(config, !noNotifications);
            }
        } catch (Exception e) {
            if (isTimeoutException(e)) {
                System.err.printf("Error: task timed out after %d seconds%n", timeoutSeconds);
                return 2;
            }
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private static boolean isTimeoutException(Throwable e) {
        if (e instanceof TimeoutException) return true;
        Throwable cause = e.getCause();
        return cause instanceof TimeoutException;
    }

    private ModelProvider buildModelProvider(
            String resolvedProvider, String apiKey, String baseUrl, String chatPath) {
        if ("anthropic".equals(resolvedProvider)) {
            return new AnthropicProvider(apiKey, baseUrl);
        }
        if (chatPath != null && !chatPath.isBlank()) {
            return new OpenAIProvider(apiKey, baseUrl, chatPath);
        }
        return new OpenAIProvider(apiKey, baseUrl);
    }

    /**
     * Resolve the LLM-classifier knobs through the same precedence ladder we use elsewhere:
     * explicit CLI flag &gt; env var &gt; config file &gt; off. Returns {@code null} so the
     * {@link CodeAgentConfig} compact constructor normalizes it to
     * {@link LlmClassifierConfig#disabled()} — that keeps the off-path a single source of truth
     * and avoids confusing the user with two distinct &quot;disabled&quot; representations.
     */
    LlmClassifierConfig resolveLlmClassifierConfig(Properties fileConfig) {
        boolean enabled = llmClassifierEnabled;
        if (!enabled) {
            enabled = isTruthy(System.getenv("KAIRO_CODE_LLM_CLASSIFIER"));
        }
        if (!enabled && fileConfig != null) {
            enabled = isTruthy(fileConfig.getProperty("llm-classifier"));
        }
        if (!enabled) {
            return null;
        }

        String resolvedModel = llmClassifierModel;
        if (resolvedModel == null || resolvedModel.isBlank()) {
            resolvedModel = System.getenv("KAIRO_CODE_LLM_CLASSIFIER_MODEL");
        }
        if ((resolvedModel == null || resolvedModel.isBlank()) && fileConfig != null) {
            resolvedModel = fileConfig.getProperty("llm-classifier-model");
        }
        if (resolvedModel != null && resolvedModel.isBlank()) {
            resolvedModel = null;
        }

        // cacheSize / timeoutMillis stay on defaults — exposing them as CLI flags now would
        // ossify the surface before we have any tuning evidence. The record's compact ctor
        // backfills 512 / 5_000ms when zero is passed.
        return new LlmClassifierConfig(true, resolvedModel, 0, 0L);
    }

    private static boolean isTruthy(String raw) {
        if (raw == null) return false;
        String v = raw.trim().toLowerCase();
        return v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("on");
    }

    /**
     * Serve as an ACP stdio server. The editor is the client; we receive JSON-RPC frames on
     * stdin, write responses + session/update notifications on stdout. Logging stays on stderr
     * (configured by SLF4J/Logback) so stdout remains a pure protocol stream.
     */
    private int runAcpServer(CodeAgentConfig config, ModelProvider modelProvider) {
        // Fast-exit for test/validation: serve() blocks on stdin reading JSON-RPC frames forever
        // and would hang surefire. Same dryrun gate that runOneShot uses.
        if (Boolean.getBoolean("kairo.code.dryrun")) {
            return 0;
        }
        try {
            CodeAgentFactory.SessionOptions opts =
                    CodeAgentFactory.SessionOptions.empty().withModelProvider(modelProvider);
            Agent agent = CodeAgentFactory.createSession(config, opts).agent();

            DefaultAcpAgent acpAgent =
                    new DefaultAcpAgent(
                            agent,
                            new io.kairo.acp.server.AcpSessionManager(),
                            new AcpImplementation("kairo-code", "0.2.0"),
                            AcpCapabilities.textOnly());
            new AcpStdioServer(acpAgent).serve();
            return 0;
        } catch (Exception e) {
            System.err.println("ACP server failed: " + e.getMessage());
            return 1;
        }
    }

    private int runOneShot(CodeAgentConfig config, String resolvedTask, ModelProvider modelProvider,
            boolean planMode) {
        // Fast-exit for test/validation: skip agent execution when dry-run is enabled.
        if (Boolean.getBoolean("kairo.code.dryrun")) {
            return 0;
        }

        PlanModeHook planHook = planMode ? new PlanModeHook(true) : null;
        PrintWriter sysErr = new PrintWriter(System.err, true);
        AgentEventPrinter eventPrinter = new AgentEventPrinter(sysErr, "", false, null, false);
        List<Object> baseHooks = noHooks ? List.of() : List.of(new ProgressPrinter(), eventPrinter);
        List<Object> hooks;
        if (planHook != null) {
            List<Object> merged = new java.util.ArrayList<>();
            merged.add(planHook);
            merged.addAll(baseHooks);
            hooks = List.copyOf(merged);
        } else {
            hooks = baseHooks;
        }
        String taskWithDiscipline = ONE_SHOT_DISCIPLINE_PREFIX + resolvedTask;
        Msg userMsg = Msg.of(MsgRole.USER, taskWithDiscipline);

        // Handle --resume: load checkpoint and inject messages via AgentSnapshot.
        AgentSnapshot restoreSnapshot = null;
        if (resume && config.workingDir() != null && !config.workingDir().isBlank()) {
            restoreSnapshot = tryLoadCheckpoint(config.workingDir());
        }

        Agent agent;
        if (restoreSnapshot != null) {
            CodeAgentFactory.SessionOptions opts = CodeAgentFactory.SessionOptions.empty()
                    .withModelProvider(modelProvider)
                    .withRestoreFrom(restoreSnapshot)
                    .withCheckpointInitialMessage(userMsg);
            if (!noHooks) {
                opts = opts.withHooks(hooks);
            }
            agent = CodeAgentFactory.createSession(config, opts).agent();
        } else {
            CodeAgentFactory.SessionOptions opts = CodeAgentFactory.SessionOptions.empty()
                    .withModelProvider(modelProvider)
                    .withCheckpointInitialMessage(userMsg);
            if (!noHooks) {
                opts = opts.withHooks(hooks);
            }
            agent = CodeAgentFactory.createSession(config, opts).agent();
        }

        long startTime = System.nanoTime();
        var mono = agent.call(userMsg);
        if (timeoutSeconds > 0) {
            mono = mono.timeout(Duration.ofSeconds(timeoutSeconds));
        }
        Msg response = mono.block();
        long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;

        // Print session summary for one-shot mode
        if (!noHooks) {
            eventPrinter.printSessionSummary(elapsedMs);
        }

        // Handle plan rejection: exit code 3.
        if (planHook != null && planHook.wasRejected()) {
            System.err.println("[kairo-code] Plan rejected by user. No changes made.");
            return 3;
        }

        if (response != null) {
            if (showUsage) {
                try {
                    AgentSnapshot snap = agent.snapshot();
                    long tokens = snap.totalTokensUsed();
                    io.kairo.code.core.cost.CostEstimator
                            .estimate(config.modelName(), tokens)
                            .ifPresentOrElse(
                                    cost ->
                                            System.err.printf(
                                                    "[USAGE] total_tokens=%d iterations=%d"
                                                            + " est_cost_usd=~%s%n",
                                                    tokens,
                                                    snap.iteration(),
                                                    io.kairo.code.core.cost.CostEstimator.format(
                                                            cost)),
                                    () ->
                                            System.err.printf(
                                                    "[USAGE] total_tokens=%d iterations=%d%n",
                                                    tokens, snap.iteration()));
                } catch (UnsupportedOperationException ignored) {
                    System.err.println("[USAGE] token stats not available for this agent");
                }
            }

            // Verbose completion summary — reads from KAIRO_SESSION_RESULT.json if available.
            if (verbose) {
                printVerboseSummary(config.workingDir(), elapsedMs);
            }

            try {
                if (outputFile != null) {
                    Files.writeString(outputFile, response.text(), StandardCharsets.UTF_8);
                } else {
                    System.out.println(response.text());
                }
            } catch (java.io.IOException e) {
                System.err.println("Error writing output file: " + e.getMessage());
                return 1;
            }
            return 0;
        } else {
            System.err.println("Error: Agent returned no response.");
            return 1;
        }
    }

    private static AgentSnapshot tryLoadCheckpoint(String workingDir) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            return CheckpointLoader.load(Path.of(workingDir), mapper)
                    .map(cp -> {
                        System.err.printf(
                                "[kairo-code] Resuming from iteration %d (session %s)%n",
                                cp.iteration(), cp.sessionId());
                        return new AgentSnapshot(
                                cp.sessionId(),
                                "kairo-code-resume",
                                AgentState.IDLE,
                                cp.iteration(),
                                0L,
                                cp.messages(),
                                Map.of(),
                                Instant.now());
                    })
                    .orElse(null);
        } catch (Exception e) {
            // Best-effort: if checkpoint loading fails, start fresh.
            return null;
        }
    }

    /**
     * Print a compact completion summary to stderr.
     * Reads data from KAIRO_SESSION_RESULT.json written by SessionResultWriterHook.
     */
    private void printVerboseSummary(String workingDir, long elapsedMs) {
        String finalState = "COMPLETED";
        int iterations = 0;
        int tools = 0;

        if (workingDir != null && !workingDir.isBlank()) {
            Path resultFile = Path.of(workingDir).resolve("KAIRO_SESSION_RESULT.json");
            try {
                if (Files.exists(resultFile)) {
                    String json = Files.readString(resultFile, StandardCharsets.UTF_8);
                    // Minimal JSON parsing — no extra dependency needed.
                    finalState = extractJsonStringField(json, "finalState");
                    iterations = extractJsonIntField(json, "iterations");
                }
            } catch (Exception e) {
                // Ignore — best-effort summary.
            }
        }

        long elapsedSec = elapsedMs / 1000;
        System.err.printf("[kairo-code] Task completed in %ds | iterations=%d | tools=%d | final-state=%s%n",
                elapsedSec, iterations, tools, finalState);
    }

    private static String extractJsonStringField(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return "UNKNOWN";
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return "UNKNOWN";
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return "UNKNOWN";
        int end = json.indexOf('"', start + 1);
        if (end < 0) return "UNKNOWN";
        return json.substring(start + 1, end);
    }

    private static int extractJsonIntField(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return 0;
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return 0;
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        if (end == start) return 0;
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private int runRepl(CodeAgentConfig config, boolean notificationsEnabled) {
        // Create event printer for hook-based output during agent calls
        PrintWriter sysWriter = new PrintWriter(System.out, true);
        boolean supportsColor = AgentEventPrinter.supportsColor();
        ModelCallSpinner spinner = new ModelCallSpinner(sysWriter, supportsColor);
        boolean verboseThinking = "true".equalsIgnoreCase(System.getenv("KAIRO_SHOW_THINKING"));
        AgentEventPrinter eventPrinter = new AgentEventPrinter(
                sysWriter, "", true, spinner, verboseThinking);

        // ReplLoop owns the ConsoleApprovalHandler lifecycle —
        // it creates the handler wired to JLine's terminal I/O.
        new ReplLoop(config, List.of(eventPrinter), null, notificationsEnabled, eventPrinter).run();
        spinner.shutdown();
        return 0;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new KairoCodeMain()).execute(args);
        System.exit(exitCode);
    }
}
