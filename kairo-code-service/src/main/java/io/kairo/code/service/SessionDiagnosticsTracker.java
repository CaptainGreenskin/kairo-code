package io.kairo.code.service;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mutable per-session tally of emitted events. Lives behind {@link AgentService}, written by
 * {@link AgentEventBridgeHook} on each emit and read by the diagnostics endpoint.
 *
 * <p>Counters use {@link AtomicLong} so reads from the HTTP thread don't tear with writes from
 * the agent's reactive thread. Snapshot conversion (string-keyed map) is cheap because the
 * EventType enum is small.
 */
final class SessionDiagnosticsTracker {

    private final Map<AgentEvent.EventType, AtomicLong> counts =
            new EnumMap<>(AgentEvent.EventType.class);
    private final AtomicLong lastEventAt = new AtomicLong(0);

    SessionDiagnosticsTracker() {
        for (AgentEvent.EventType t : AgentEvent.EventType.values()) {
            counts.put(t, new AtomicLong(0));
        }
    }

    void record(AgentEvent.EventType type, long timestamp) {
        AtomicLong c = counts.get(type);
        if (c != null) {
            c.incrementAndGet();
        }
        lastEventAt.accumulateAndGet(timestamp, Math::max);
    }

    long lastEventAt() {
        return lastEventAt.get();
    }

    Map<String, Long> snapshotCounts() {
        Map<String, Long> out = new HashMap<>(counts.size());
        for (var e : counts.entrySet()) {
            long v = e.getValue().get();
            if (v > 0) {
                out.put(e.getKey().name(), v);
            }
        }
        return out;
    }
}
