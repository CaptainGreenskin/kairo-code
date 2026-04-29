package io.kairo.code.cli;

import java.io.PrintWriter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Animated terminal spinner shown while the model is processing.
 * Renders on a single line using \r to overwrite.
 * Stopped and cleared when the first token arrives or the call errors.
 */
public final class ModelCallSpinner {

    private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static final String LABEL = " Thinking";

    private final PrintWriter writer;
    private final boolean colorEnabled;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "kairo-spinner");
                t.setDaemon(true);
                return t;
            });
    private ScheduledFuture<?> task;
    private final AtomicBoolean active = new AtomicBoolean(false);
    private int frameIndex = 0;

    public ModelCallSpinner(PrintWriter writer, boolean colorEnabled) {
        this.writer = writer;
        this.colorEnabled = colorEnabled;
    }

    /** Start spinning. No-op if already spinning. */
    public synchronized void start() {
        if (active.compareAndSet(false, true)) {
            task = scheduler.scheduleAtFixedRate(this::tick, 0, 80, TimeUnit.MILLISECONDS);
        }
    }

    /** Stop and clear the spinner line. */
    public synchronized void stop() {
        if (active.compareAndSet(true, false)) {
            if (task != null) {
                task.cancel(false);
            }
            // Clear spinner line
            writer.print("\r" + " ".repeat(20) + "\r");
            writer.flush();
        }
    }

    private synchronized void tick() {
        if (!active.get()) return;
        String frame = FRAMES[frameIndex % FRAMES.length];
        frameIndex++;
        String line = colorEnabled
                ? "\r\u001B[36m" + frame + LABEL + "\u001B[0m"
                : "\r" + frame + LABEL;
        writer.print(line);
        writer.flush();
    }

    public void shutdown() {
        stop();
        scheduler.shutdownNow();
    }

    /** Whether the spinner is currently animating. Package-private for testing. */
    boolean isActive() {
        return active.get();
    }
}
