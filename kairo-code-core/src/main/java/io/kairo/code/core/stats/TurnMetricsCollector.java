/*
 * Copyright 2025-2026 the Kairo authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.kairo.code.core.stats;

import io.kairo.api.hook.HookHandler;
import io.kairo.api.hook.HookPhase;
import io.kairo.api.hook.HookResult;
import io.kairo.api.hook.OnToolResult;
import io.kairo.api.hook.PostReasoningEvent;
import io.kairo.api.hook.ToolResultEvent;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hook handler that tracks per-turn metrics: tool call count, success count, and duration.
 *
 * <p>A "turn" is one model invocation cycle — the model may call multiple tools within a turn,
 * then produces a text response. Turn boundaries are detected via the {@link PostReasoningEvent}
 * hook. Individual tool calls within a turn are tracked via {@link ToolResultEvent}.
 *
 * <p>Registered as a hook in {@code CodeAgentFactory.SessionOptions}, this class receives
 * callbacks and accumulates per-turn snapshots that can be queried via {@link #turnSnapshots()}.
 */
public final class TurnMetricsCollector {

    private final List<TurnSnapshot> snapshots = Collections.synchronizedList(new java.util.ArrayList<>());

    // Per-turn accumulators (reset at each turn boundary)
    private final AtomicInteger currentTurnToolCalls = new AtomicInteger(0);
    private final AtomicInteger currentTurnSuccesses = new AtomicInteger(0);
    private final AtomicLong currentTurnDurationMillis = new AtomicLong(0);

    // Session-level aggregate
    private final AtomicLong totalDurationMillis = new AtomicLong(0);

    @OnToolResult
    public void onToolResult(ToolResultEvent event) {
        currentTurnToolCalls.incrementAndGet();
        if (event.success()) {
            currentTurnSuccesses.incrementAndGet();
        }
        currentTurnDurationMillis.addAndGet(event.duration().toMillis());
    }

    @HookHandler(HookPhase.POST_REASONING)
    public HookResult<PostReasoningEvent> onPostReasoning(PostReasoningEvent event) {
        // Close the current turn
        int toolCalls = currentTurnToolCalls.getAndSet(0);
        int successes = currentTurnSuccesses.getAndSet(0);
        long duration = currentTurnDurationMillis.getAndSet(0);

        if (toolCalls > 0) {
            int turnNumber = snapshots.size() + 1;
            snapshots.add(new TurnSnapshot(turnNumber, toolCalls, successes, duration));
            totalDurationMillis.addAndGet(duration);
        }

        return HookResult.proceed(event);
    }

    /**
     * Returns the total number of completed turns.
     */
    public int totalTurns() {
        return snapshots.size();
    }

    /**
     * Returns the total number of tool calls across all turns.
     */
    public int totalToolCalls() {
        return snapshots.stream().mapToInt(TurnSnapshot::toolCalls).sum();
    }

    /**
     * Returns the average number of tool calls per turn (0.0 if no turns).
     */
    public double avgToolCallsPerTurn() {
        int turns = snapshots.size();
        if (turns == 0) return 0.0;
        return (double) totalToolCalls() / turns;
    }

    /**
     * Returns the total duration in milliseconds across all turns.
     */
    public long totalDurationMillis() {
        return totalDurationMillis.get();
    }

    /**
     * Returns the average duration per turn in milliseconds (0 if no turns).
     */
    public long avgDurationPerTurnMillis() {
        int turns = snapshots.size();
        if (turns == 0) return 0;
        return totalDurationMillis.get() / turns;
    }

    /**
     * Returns an immutable list of per-turn snapshots, one per completed turn.
     */
    public List<TurnSnapshot> turnSnapshots() {
        return Collections.unmodifiableList(List.copyOf(snapshots));
    }

    /**
     * Returns the snapshot of the last completed turn, or null if no turns yet.
     */
    public TurnSnapshot lastTurn() {
        List<TurnSnapshot> list = snapshots;
        if (list.isEmpty()) return null;
        return list.get(list.size() - 1);
    }

    /**
     * Immutable value record capturing a single turn's metrics.
     */
    public record TurnSnapshot(int turnNumber, int toolCalls, int successes, long durationMillis) {

        /**
         * Returns the success rate as a percentage (0.0 to 100.0).
         */
        public double successRate() {
            if (toolCalls == 0) return 0.0;
            return (successes * 100.0) / toolCalls;
        }
    }
}
