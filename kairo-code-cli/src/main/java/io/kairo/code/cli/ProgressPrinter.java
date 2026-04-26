package io.kairo.code.cli;

import io.kairo.api.hook.HookHandler;
import io.kairo.api.hook.HookPhase;
import io.kairo.api.hook.PreActingEvent;
import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hook handler that prints step-by-step progress to stderr during one-shot task execution.
 * Enabled by the {@code --verbose} CLI option.
 *
 * <p>Writes {@code [STEP n] 工具调用：<tool>} lines to stderr so stdout remains clean
 * for the final agent response.
 */
public class ProgressPrinter {

    private final PrintStream err;
    private final AtomicInteger step = new AtomicInteger(0);

    public ProgressPrinter() {
        this(System.err);
    }

    /** Package-private: allows tests to inject a custom stream. */
    ProgressPrinter(PrintStream err) {
        this.err = err;
    }

    @HookHandler(HookPhase.PRE_ACTING)
    public void onPreActing(PreActingEvent event) {
        int n = step.incrementAndGet();
        err.printf("[STEP %d] 工具调用：%s%n", n, event.toolName());
    }

    public int stepCount() {
        return step.get();
    }
}
