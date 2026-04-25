package io.kairo.code.cli;

import io.kairo.api.agent.Agent;
import io.kairo.api.message.Msg;
import java.io.PrintWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;

/**
 * Encapsulates async agent execution with cancellation support for the REPL.
 *
 * <p>Subscribes to {@code agent.call(msg)} reactively, stores the {@link Disposable}
 * so that a signal handler (Ctrl+C) can cancel in-flight execution from another thread.
 * The calling thread blocks on a {@link CountDownLatch} until the Mono completes,
 * errors, or is cancelled.
 */
public class StreamingAgentRunner {

    private static final Logger log = LoggerFactory.getLogger(StreamingAgentRunner.class);

    private final AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();
    private final AtomicReference<Agent> agentRef = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final PrintWriter writer;

    public StreamingAgentRunner(PrintWriter writer) {
        this.writer = writer;
    }

    /**
     * Execute an agent call, blocking the current thread until the response is available,
     * an error occurs, or the execution is cancelled via {@link #cancel()}.
     *
     * @param userMessage the user's input message
     * @param agent the agent to invoke
     * @return the response Msg, or {@code null} if cancelled or an error occurred
     */
    public Msg run(Msg userMessage, Agent agent) {
        AtomicReference<Msg> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        agentRef.set(agent);
        running.set(true);

        Disposable disposable = agent.call(userMessage)
                .doFinally(signal -> {
                    running.set(false);
                    latch.countDown();
                })
                .subscribe(
                        msg -> resultRef.set(msg),
                        error -> {
                            if (!isCancellation(error)) {
                                errorRef.set(error);
                            }
                        }
                );

        subscriptionRef.set(disposable);

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancel();
        }

        // Clean up references
        subscriptionRef.set(null);
        agentRef.set(null);

        // Handle error case
        Throwable error = errorRef.get();
        if (error != null) {
            throw new AgentExecutionException(error.getMessage(), error);
        }

        return resultRef.get();
    }

    /**
     * Cancel the in-flight agent execution. Thread-safe — designed to be called
     * from a signal handler thread.
     */
    public void cancel() {
        Disposable disposable = subscriptionRef.getAndSet(null);
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }

        Agent agent = agentRef.get();
        if (agent != null) {
            try {
                agent.interrupt();
            } catch (Exception e) {
                log.debug("Error interrupting agent", e);
            }
        }

        if (running.getAndSet(false)) {
            writer.println();
            writer.println("⏹ Cancelled.");
            writer.flush();
        }
    }

    /** Whether the agent is currently executing. */
    public boolean isRunning() {
        return running.get();
    }

    private static boolean isCancellation(Throwable error) {
        return error instanceof java.util.concurrent.CancellationException
                || error.getClass().getSimpleName().contains("Cancel")
                || error.getClass().getSimpleName().contains("Dispose");
    }

    /** Exception wrapper for agent execution failures. */
    public static class AgentExecutionException extends RuntimeException {
        public AgentExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
