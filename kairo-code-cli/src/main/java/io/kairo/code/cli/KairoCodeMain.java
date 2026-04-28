package io.kairo.code.cli;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentSnapshot;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.model.ModelProvider;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentFactory;
import io.kairo.code.core.mcp.McpConfig;
import io.kairo.core.model.anthropic.AnthropicProvider;
import io.kairo.core.model.openai.OpenAIProvider;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.Set;
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
            description = "Model API provider: openai (default), anthropic, qianwen",
            defaultValue = "openai")
    private String provider;

    @Option(names = "--task-list",
            description = "JSON Lines file with multiple tasks to run in parallel")
    private Path taskListFile;

    @Option(names = "--output", description = "Write final response to this file instead of stdout")
    private Path outputFile;

    @Option(names = "--show-usage", description = "Print token usage stats to stderr after completion")
    private boolean showUsage;

    @Option(names = "--no-notifications", description = "Disable desktop notifications on task completion")
    private boolean noNotifications;

    private static final String DASHSCOPE_BASE_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final Set<String> VALID_PROVIDERS = Set.of("openai", "anthropic", "qianwen");

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

        // Resolve provider: CLI arg > env var > config file > default (openai)
        String resolvedProvider = provider;
        String envProvider = System.getenv("KAIRO_CODE_PROVIDER");
        if (envProvider != null && !envProvider.isBlank() && "openai".equals(provider)) {
            resolvedProvider = envProvider;
        } else if ("openai".equals(provider) && fileConfig.getProperty("provider") != null) {
            resolvedProvider = fileConfig.getProperty("provider");
        }
        resolvedProvider = resolvedProvider.toLowerCase();
        if (!VALID_PROVIDERS.contains(resolvedProvider)) {
            System.err.printf("Error: unknown provider '%s'. Valid: openai, anthropic, qianwen%n",
                    resolvedProvider);
            return 1;
        }

        // Resolve model: CLI arg > env var > config file > default (gpt-4o / qwen-max)
        String resolvedModel = model;
        String envModel = System.getenv("KAIRO_CODE_MODEL");
        if (envModel != null && !envModel.isBlank() && "gpt-4o".equals(model)) {
            resolvedModel = envModel;
        } else if ("gpt-4o".equals(model) && fileConfig.getProperty("model") != null) {
            resolvedModel = fileConfig.getProperty("model");
        } else if ("gpt-4o".equals(model) && "qianwen".equals(resolvedProvider)) {
            resolvedModel = "qwen-max";
        }

        // Resolve base URL: CLI arg > env var > config file > default
        String resolvedBaseUrl = baseUrl;
        String envBaseUrl = System.getenv("KAIRO_CODE_BASE_URL");
        if (envBaseUrl != null && !envBaseUrl.isBlank()
                && "https://api.openai.com".equals(baseUrl)) {
            resolvedBaseUrl = envBaseUrl;
        } else if ("https://api.openai.com".equals(baseUrl)
                && fileConfig.getProperty("base-url") != null) {
            resolvedBaseUrl = fileConfig.getProperty("base-url");
        } else if ("https://api.openai.com".equals(baseUrl)
                && "qianwen".equals(resolvedProvider)) {
            resolvedBaseUrl = DASHSCOPE_BASE_URL;
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

            CodeAgentConfig config = new CodeAgentConfig(
                    resolvedApiKey, resolvedBaseUrl, resolvedModel,
                    maxIterations, resolvedWorkingDir, mcpConfig);
            ModelProvider modelProvider = buildModelProvider(
                    resolvedProvider, resolvedApiKey, resolvedBaseUrl);

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

            RetryPolicy retryPolicy = new RetryPolicy(maxRetries);

            if (resolvedTask != null && !resolvedTask.isBlank()) {
                final String taskToRun = resolvedTask;
                return retryPolicy.execute(() -> runOneShot(config, taskToRun, modelProvider));
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

    private ModelProvider buildModelProvider(String resolvedProvider, String apiKey, String baseUrl) {
        if ("anthropic".equals(resolvedProvider)) {
            return new AnthropicProvider(apiKey, baseUrl);
        }
        // KAIRO_CODE_CHAT_PATH lets callers override the appended path (default: /v1/chat/completions).
        // Needed for providers like GLM Coding Plan whose base URL already contains the version
        // segment (e.g. https://open.bigmodel.cn/api/coding/paas/v4 + /chat/completions).
        String chatPath = System.getenv("KAIRO_CODE_CHAT_PATH");
        if (chatPath != null && !chatPath.isBlank()) {
            return new OpenAIProvider(apiKey, baseUrl, chatPath);
        }
        return new OpenAIProvider(apiKey, baseUrl);
    }

    private int runOneShot(CodeAgentConfig config, String resolvedTask, ModelProvider modelProvider) {
        Agent agent = verbose
                ? CodeAgentFactory.create(config, modelProvider, null, List.of(new ProgressPrinter()))
                : CodeAgentFactory.create(config, modelProvider);

        Msg userMsg = Msg.of(MsgRole.USER, resolvedTask);
        var mono = agent.call(userMsg);
        if (timeoutSeconds > 0) {
            mono = mono.timeout(Duration.ofSeconds(timeoutSeconds));
        }
        Msg response = mono.block();

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

    private int runRepl(CodeAgentConfig config, boolean notificationsEnabled) {
        // Create event printer for hook-based output during agent calls
        PrintWriter sysWriter = new PrintWriter(System.out, true);
        AgentEventPrinter eventPrinter = new AgentEventPrinter(sysWriter, "", true);

        // ReplLoop owns the ConsoleApprovalHandler lifecycle —
        // it creates the handler wired to JLine's terminal I/O.
        new ReplLoop(config, List.of(eventPrinter), null, notificationsEnabled).run();
        return 0;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new KairoCodeMain()).execute(args);
        System.exit(exitCode);
    }
}
