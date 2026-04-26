package io.kairo.code.cli;

import io.kairo.api.agent.Agent;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentFactory;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
        version = "kairo-code 0.1.0")
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

    @Override
    public Integer call() {
        // Resolve API key: CLI arg takes precedence over env variable
        String resolvedApiKey = apiKey;
        if (resolvedApiKey == null || resolvedApiKey.isBlank()) {
            resolvedApiKey = System.getenv("KAIRO_CODE_API_KEY");
        }
        if (resolvedApiKey == null || resolvedApiKey.isBlank()) {
            System.err.println("Error: API key is required. " +
                    "Set --api-key or KAIRO_CODE_API_KEY environment variable.");
            return 1;
        }

        // Resolve model from env if not explicitly set
        String resolvedModel = model;
        String envModel = System.getenv("KAIRO_CODE_MODEL");
        if (envModel != null && !envModel.isBlank() && "gpt-4o".equals(model)) {
            resolvedModel = envModel;
        }

        // Resolve base URL from env if not explicitly set
        String resolvedBaseUrl = baseUrl;
        String envBaseUrl = System.getenv("KAIRO_CODE_BASE_URL");
        if (envBaseUrl != null && !envBaseUrl.isBlank()
                && "https://api.openai.com".equals(baseUrl)) {
            resolvedBaseUrl = envBaseUrl;
        }

        // Resolve working directory
        String resolvedWorkingDir = workingDir;
        if (resolvedWorkingDir == null || resolvedWorkingDir.isBlank()) {
            resolvedWorkingDir = System.getProperty("user.dir");
        }

        try {
            if (task != null && taskFile != null) {
                System.err.println("Error: --task and --task-file are mutually exclusive.");
                return 1;
            }

            String resolvedTask = task;
            if (taskFile != null) {
                if (!Files.exists(taskFile)) {
                    System.err.println("Error: task file not found: " + taskFile);
                    return 1;
                }
                resolvedTask = Files.readString(taskFile, StandardCharsets.UTF_8);
            }

            CodeAgentConfig config = new CodeAgentConfig(
                    resolvedApiKey, resolvedBaseUrl, resolvedModel,
                    maxIterations, resolvedWorkingDir);
            RetryPolicy retryPolicy = new RetryPolicy(maxRetries);

            if (resolvedTask != null && !resolvedTask.isBlank()) {
                final String taskToRun = resolvedTask;
                return retryPolicy.execute(() -> runOneShot(config, taskToRun));
            } else {
                return runRepl(config);
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

    private int runOneShot(CodeAgentConfig config, String resolvedTask) {
        Agent agent = verbose
                ? CodeAgentFactory.create(config, null, List.of(new ProgressPrinter()))
                : CodeAgentFactory.create(config);

        Msg userMsg = Msg.of(MsgRole.USER, resolvedTask);
        var mono = agent.call(userMsg);
        if (timeoutSeconds > 0) {
            mono = mono.timeout(Duration.ofSeconds(timeoutSeconds));
        }
        Msg response = mono.block();

        if (response != null) {
            System.out.println(response.text());
            return 0;
        } else {
            System.err.println("Error: Agent returned no response.");
            return 1;
        }
    }

    private int runRepl(CodeAgentConfig config) {
        // Create event printer for hook-based output during agent calls
        PrintWriter sysWriter = new PrintWriter(System.out, true);
        AgentEventPrinter eventPrinter = new AgentEventPrinter(sysWriter);

        // ReplLoop owns the ConsoleApprovalHandler lifecycle —
        // it creates the handler wired to JLine's terminal I/O.
        new ReplLoop(config, List.of(eventPrinter)).run();
        return 0;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new KairoCodeMain()).execute(args);
        System.exit(exitCode);
    }
}
