package io.kairo.code.core;

import io.kairo.api.tool.ApprovalResult;
import io.kairo.api.tool.ToolCallRequest;
import io.kairo.api.tool.UserApprovalHandler;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Console-based {@link UserApprovalHandler} that prompts the user for approval before executing
 * risky tools.
 *
 * <p>Supports session-scoped memory: users can choose "always" or "never" to remember their
 * decision for a specific tool for the rest of the session.
 *
 * <p><b>Cancellation safety:</b> Uses {@link Mono#create} with {@code sink.onDispose()} to
 * register a cleanup action that interrupts the reading thread when the Reactor subscription
 * is disposed (e.g., by Ctrl+C). The reading loop uses {@link Reader#ready()} with short sleeps
 * so it responds to {@link Thread#interrupt()} within ~50ms.
 */
public final class ConsoleApprovalHandler implements UserApprovalHandler {

    private static final Logger log = LoggerFactory.getLogger(ConsoleApprovalHandler.class);
    private static final long POLL_INTERVAL_MS = 50;

    /** Remembered approval decisions for the current session. */
    public enum ApprovalDecision {
        ALWAYS_ALLOW,
        ALWAYS_DENY
    }

    private final ConcurrentHashMap<String, ApprovalDecision> approvalMemory =
            new ConcurrentHashMap<>();
    private final BufferedReader reader;
    private final PrintWriter writer;
    private final Object promptLock = new Object();

    /**
     * Create a new handler with the given I/O streams.
     *
     * @param reader input reader for user responses (e.g., JLine terminal reader)
     * @param writer output writer for prompts (e.g., JLine terminal writer)
     */
    public ConsoleApprovalHandler(BufferedReader reader, PrintWriter writer) {
        this.reader = reader;
        this.writer = writer;
    }

    @Override
    public Mono<ApprovalResult> requestApproval(ToolCallRequest request) {
        // Check session memory first — no prompt needed
        ApprovalDecision remembered = approvalMemory.get(request.toolName());
        if (remembered != null) {
            return remembered == ApprovalDecision.ALWAYS_ALLOW
                    ? Mono.just(ApprovalResult.allow())
                    : Mono.just(ApprovalResult.denied("User permanently denied"));
        }

        // Use Mono.create so we can register an onDispose callback that interrupts
        // the reading thread. This is the key to making Ctrl+C cancellation work:
        // Reactor dispose() triggers sink.onDispose(), which calls thread.interrupt(),
        // which breaks the polling sleep in readLineInterruptibly().
        return Mono.<ApprovalResult>create(sink -> {
            Thread thread = new Thread(() -> {
                try {
                    ApprovalResult result = promptUser(request);
                    sink.success(result);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    sink.success(ApprovalResult.denied("Cancelled"));
                } catch (Exception e) {
                    log.warn("Approval prompt error", e);
                    sink.success(ApprovalResult.denied("Error: " + e.getMessage()));
                }
            }, "approval-prompt");
            thread.setDaemon(true);
            thread.start();
            sink.onDispose(() -> thread.interrupt());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Prompt the user on the console and return their decision. Synchronized so only one prompt
     * is active at a time.
     *
     * @throws InterruptedException if the thread is interrupted (e.g., by Ctrl+C cancellation)
     */
    private ApprovalResult promptUser(ToolCallRequest request) throws InterruptedException {
        synchronized (promptLock) {
            String argsDisplay = formatArgs(request);
            writer.println();
            writer.printf("⚠ %s wants to execute: %s%n", request.toolName(), argsDisplay);
            writer.printf("Side effect: %s%n", request.sideEffect());
            writer.print("[y]es / [n]o / [a]lways / ne[v]er > ");
            writer.flush();

            String input;
            try {
                input = readLineInterruptibly(reader);
            } catch (InterruptedException e) {
                throw e;
            } catch (IOException e) {
                log.warn("Failed to read approval input", e);
                return ApprovalResult.denied("No response");
            }

            if (input == null || input.isBlank()) {
                return ApprovalResult.denied("No response");
            }

            String trimmed = input.strip().toLowerCase();
            return switch (trimmed) {
                case "y", "yes" -> ApprovalResult.allow();
                case "n", "no" -> ApprovalResult.denied("User denied");
                case "a", "always" -> {
                    approvalMemory.put(request.toolName(), ApprovalDecision.ALWAYS_ALLOW);
                    yield ApprovalResult.allow();
                }
                case "v", "never" -> {
                    approvalMemory.put(request.toolName(), ApprovalDecision.ALWAYS_DENY);
                    yield ApprovalResult.denied("User permanently denied");
                }
                default -> ApprovalResult.denied("No response");
            };
        }
    }

    /**
     * Read a line from the reader using a polling approach that respects thread interruption.
     *
     * <p>Instead of blocking on {@code reader.readLine()} (which ignores {@code Thread.interrupt()}),
     * this method polls {@code reader.ready()} and sleeps between checks. The sleep is interruptible,
     * so when the Reactor subscription is disposed and the thread is interrupted, we exit cleanly.
     *
     * @param reader the reader to read from
     * @return the line read, or {@code null} on EOF
     * @throws IOException if an I/O error occurs
     * @throws InterruptedException if the thread is interrupted while waiting for input
     */
    private String readLineInterruptibly(BufferedReader reader)
            throws IOException, InterruptedException {
        StringBuilder sb = new StringBuilder();
        while (!Thread.currentThread().isInterrupted()) {
            if (reader.ready()) {
                int ch = reader.read();
                if (ch == -1) {
                    return sb.isEmpty() ? null : sb.toString();
                }
                if (ch == '\n') {
                    return sb.toString();
                }
                if (ch != '\r') {
                    sb.append((char) ch);
                }
            } else {
                Thread.sleep(POLL_INTERVAL_MS);
            }
        }
        throw new InterruptedException("Approval prompt interrupted");
    }

    private String formatArgs(ToolCallRequest request) {
        if (request.args() == null || request.args().isEmpty()) {
            return "(no args)";
        }
        // For bash-like tools, show the command; otherwise show key=value pairs
        Object command = request.args().get("command");
        if (command != null) {
            return command.toString();
        }
        return request.args().toString();
    }

    /** Clear all remembered approval decisions. Called by the {@code :clear} command. */
    public void resetApprovals() {
        approvalMemory.clear();
    }

    /**
     * Return a snapshot of the current approval state. Used by {@code :resume} to persist session
     * state.
     *
     * @return an unmodifiable view of the current approval decisions
     */
    public Map<String, ApprovalDecision> getApprovalState() {
        return Collections.unmodifiableMap(new ConcurrentHashMap<>(approvalMemory));
    }

    /**
     * Restore approval state from a previous snapshot. Used by {@code :resume} to recover session
     * state.
     *
     * @param state the approval state to restore
     */
    public void restoreApprovals(Map<String, ApprovalDecision> state) {
        approvalMemory.clear();
        if (state != null) {
            approvalMemory.putAll(state);
        }
    }
}
