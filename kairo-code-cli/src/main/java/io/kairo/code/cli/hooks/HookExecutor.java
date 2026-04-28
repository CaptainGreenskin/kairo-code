package io.kairo.code.cli.hooks;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes shell hooks asynchronously when events fire.
 *
 * <p>Finds matching hook entries (exact matcher preferred, {@code "*"} as fallback),
 * substitutes {@code {{tool_name}}} / {@code {{tool_input}}} placeholders in the command,
 * and runs each via {@code /bin/sh -c}.
 *
 * <p>Hook execution is non-blocking: failures are logged at WARN and never affect the agent flow.
 * Each command is capped at a 5-second timeout.
 */
public class HookExecutor {

    private static final Logger log = LoggerFactory.getLogger(HookExecutor.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HooksConfig config;
    private final ExecutorService executor;

    public HookExecutor(HooksConfig config) {
        this.config = config;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "hook-executor");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Fire hooks for the given event.
     *
     * @param event event name (e.g., "PreToolUse", "PostToolUse", "Stop")
     * @param toolName tool name that triggered the event
     * @param toolInputOrResult the tool input (for PreToolUse) or result content (for PostToolUse)
     */
    public void fire(String event, String toolName, String toolInputOrResult) {
        List<HookEntry> entries = config.getHooks(event);
        if (entries.isEmpty()) {
            return;
        }

        // Exact matcher entries first, then wildcard entries
        List<HookEntry> exact = entries.stream()
                .filter(e -> !"*".equals(e.matcher()))
                .filter(e -> e.matches(toolName))
                .toList();
        List<HookEntry> wildcard = entries.stream()
                .filter(e -> "*".equals(e.matcher()))
                .toList();

        for (HookEntry entry : exact) {
            execute(entry.command(), event, toolName, toolInputOrResult);
        }
        for (HookEntry entry : wildcard) {
            execute(entry.command(), event, toolName, toolInputOrResult);
        }
    }

    /** Shut down the executor. No-op if already shut down. */
    public void shutdown() {
        executor.shutdownNow();
    }

    private void execute(String command, String event, String toolName, String toolInputOrResult) {
        String resolved = command
                .replace("{{tool_name}}", toolName != null ? toolName : "")
                .replace("{{tool_input}}", toolInputOrResult != null ? toolInputOrResult : "");

        log.debug("Firing hook [{}]: {}", event, resolved);

        CompletableFuture.runAsync(() -> {
            try {
                Process process = new ProcessBuilder("/bin/sh", "-c", resolved)
                        .redirectErrorStream(true)
                        .start();

                // Read output to prevent blocking
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.debug("[hook] {}", line);
                    }
                }

                boolean finished = process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    log.warn("Hook command timed out after {}s: {}", TIMEOUT.getSeconds(), resolved);
                } else {
                    int exitCode = process.exitValue();
                    if (exitCode != 0) {
                        log.warn("Hook command exited with code {}: {}", exitCode, resolved);
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to execute hook command: {} — {}", resolved, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.debug("Hook execution interrupted: {}", resolved);
            }
        }, executor);
    }
}
