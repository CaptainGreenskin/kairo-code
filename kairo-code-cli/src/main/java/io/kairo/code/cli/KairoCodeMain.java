package io.kairo.code.cli;

import io.kairo.api.agent.Agent;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentFactory;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Callable;
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
            CodeAgentConfig config = new CodeAgentConfig(
                    resolvedApiKey, resolvedBaseUrl, resolvedModel,
                    maxIterations, resolvedWorkingDir);
            RetryPolicy retryPolicy = new RetryPolicy(maxRetries);

            if (task != null && !task.isBlank()) {
                return retryPolicy.execute(() -> runOneShot(config));
            } else {
                return runRepl(config);
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private int runOneShot(CodeAgentConfig config) {
        Agent agent = CodeAgentFactory.create(config);

        Msg userMsg = Msg.of(MsgRole.USER, task);
        Msg response = agent.call(userMsg).block();

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
