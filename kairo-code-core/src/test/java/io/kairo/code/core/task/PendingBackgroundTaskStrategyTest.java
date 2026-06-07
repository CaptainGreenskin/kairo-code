package io.kairo.code.core.task;

import static org.junit.jupiter.api.Assertions.*;

import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.model.ModelResponse;
import io.kairo.core.agent.continuation.ContinuationContext;
import io.kairo.core.agent.continuation.ContinuationDecision;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PendingBackgroundTaskStrategyTest {

    private ContinuationContext buildCtx() {
        Msg assistantMsg = Msg.of(MsgRole.ASSISTANT, "Waiting for workers...");
        return new ContinuationContext(
                "test-agent", 5, 100,
                List.of(assistantMsg), assistantMsg,
                ModelResponse.StopReason.END_TURN,
                0.3f, 0, false, 0, Map.of());
    }

    @Test
    void passWhenNoActiveSubagents() {
        var registry = new SubagentRegistry();
        var queue = new BackgroundTaskNotificationQueue();
        var strategy = new PendingBackgroundTaskStrategy(registry, queue);

        ContinuationDecision decision = strategy.decide(buildCtx()).block(Duration.ofSeconds(2));
        assertInstanceOf(ContinuationDecision.Pass.class, decision);
    }

    @Test
    void nudgeWhenNotificationArrives() {
        var registry = new SubagentRegistry();
        registry.register("worker-1", "t-001");
        var queue = new BackgroundTaskNotificationQueue();
        queue.offer("<task-notification task_id=\"t-001\" status=\"completed\">done</task-notification>");

        var strategy = new PendingBackgroundTaskStrategy(registry, queue);
        ContinuationDecision decision = strategy.decide(buildCtx()).block(Duration.ofSeconds(2));

        assertInstanceOf(ContinuationDecision.Nudge.class, decision);
        var nudge = (ContinuationDecision.Nudge) decision;
        assertEquals("background_task_completed", nudge.reason());
        assertTrue(nudge.syntheticUserMessage().text().contains("task-notification"));
        assertEquals(1, strategy.nudgeCount());
    }

    @Test
    void terminateOnTimeout() {
        var registry = new SubagentRegistry();
        registry.register("worker-1", "t-001");
        var queue = new BackgroundTaskNotificationQueue();

        // Short timeout for test speed
        var strategy = new PendingBackgroundTaskStrategy(registry, queue, 1, 50);
        ContinuationDecision decision = strategy.decide(buildCtx()).block(Duration.ofSeconds(5));

        assertInstanceOf(ContinuationDecision.Terminate.class, decision);
        assertEquals("background_task_timeout", ((ContinuationDecision.Terminate) decision).reason());
    }

    @Test
    void terminateAfterMaxNudges() {
        var registry = new SubagentRegistry();
        registry.register("worker-1", "t-001");
        var queue = new BackgroundTaskNotificationQueue();

        var strategy = new PendingBackgroundTaskStrategy(registry, queue, 1, 2);

        // Nudge 1
        queue.offer("notification-1");
        ContinuationDecision d1 = strategy.decide(buildCtx()).block(Duration.ofSeconds(2));
        assertInstanceOf(ContinuationDecision.Nudge.class, d1);

        // Nudge 2
        queue.offer("notification-2");
        ContinuationDecision d2 = strategy.decide(buildCtx()).block(Duration.ofSeconds(2));
        assertInstanceOf(ContinuationDecision.Nudge.class, d2);

        // Nudge 3 — should hit max and terminate
        queue.offer("notification-3");
        ContinuationDecision d3 = strategy.decide(buildCtx()).block(Duration.ofSeconds(2));
        assertInstanceOf(ContinuationDecision.Terminate.class, d3);
        assertEquals("background_task_max_nudges",
                ((ContinuationDecision.Terminate) d3).reason());
    }

    @Test
    void blocksUntilNotificationFromAnotherThread() throws InterruptedException {
        var registry = new SubagentRegistry();
        registry.register("worker-1", "t-001");
        var queue = new BackgroundTaskNotificationQueue();

        var strategy = new PendingBackgroundTaskStrategy(registry, queue, 10, 50);

        // Fire notification after a delay
        Thread.startVirtualThread(() -> {
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            queue.offer("delayed-notification");
        });

        long start = System.currentTimeMillis();
        ContinuationDecision decision = strategy.decide(buildCtx()).block(Duration.ofSeconds(5));
        long elapsed = System.currentTimeMillis() - start;

        assertInstanceOf(ContinuationDecision.Nudge.class, decision);
        assertTrue(elapsed >= 50, "should have blocked at least 50ms, was " + elapsed);
    }

    @Test
    void nameReturnsExpected() {
        var strategy = new PendingBackgroundTaskStrategy(
                new SubagentRegistry(), new BackgroundTaskNotificationQueue());
        assertEquals("PendingBackgroundTask", strategy.name());
    }
}
