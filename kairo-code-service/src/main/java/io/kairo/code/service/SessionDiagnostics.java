package io.kairo.code.service;

import java.util.Map;

/**
 * Read-only snapshot of a session's runtime telemetry, surfaced via
 * {@code /api/sessions/{id}/diagnostics} so dashboards / dev tools can answer
 * "is this agent actually doing anything?" without subscribing to the event firehose.
 *
 * <p>{@code msSinceLastEvent} is the most useful single field — pair it with the watchdog
 * threshold and you have a synchronous "is this stalled?" signal. {@code eventCounts}
 * surfaces the lifecycle distribution so an operator can spot e.g. "0 AGENT_DONE" as a
 * sign of the prior double-emit / drop bug returning.
 *
 * @param sessionId the session identifier
 * @param running   whether the agent loop is currently active
 * @param lastEventAt unix millis of the most recent emitted event (0 if none yet)
 * @param msSinceLastEvent age of the last event in millis (Long.MAX_VALUE if no events)
 * @param eventCounts per-{@link AgentEvent.EventType} counts since session start
 * @param wsClients   number of subscribers currently attached to the session sink
 */
public record SessionDiagnostics(
        String sessionId,
        boolean running,
        long lastEventAt,
        long msSinceLastEvent,
        Map<String, Long> eventCounts,
        int wsClients) {}
