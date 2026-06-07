package io.kairo.code.service;

import io.kairo.api.agent.AgentDiagnostics;
import io.kairo.code.service.AgentService.SessionEntry;
import io.kairo.code.service.agent.AgentRuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Manages session diagnostics, metrics collection, and the progress heartbeat ticker.
 *
 * <p>Extracted from AgentService to isolate observability concerns from session lifecycle.
 */
@Component
public class SessionDiagnosticsService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SessionDiagnosticsService.class);
    private static final long PROGRESS_THRESHOLD_MS = 30_000L;
    private static final Duration PROGRESS_TICK = Duration.ofSeconds(5);

    private final SessionRegistry registry;
    private volatile reactor.core.Disposable progressTickSubscription;

    public SessionDiagnosticsService(SessionRegistry registry) {
        this.registry = registry;
    }

    /** Start the heartbeat ticker if not already running. Called after first session creation. */
    public synchronized void startProgressTickerIfNeeded() {
        if (progressTickSubscription != null && !progressTickSubscription.isDisposed()) {
            return;
        }
        progressTickSubscription =
                Flux.interval(PROGRESS_TICK, Schedulers.parallel())
                        .doOnNext(tick -> emitProgressHeartbeats())
                        .onErrorContinue(
                                (err, o) -> log.warn("TOOL_PROGRESS tick failed: {}", err.toString()))
                        .subscribe();
    }

    private void emitProgressHeartbeats() {
        for (SessionContext ctx : registry.list()) {
            ToolProgressTracker tracker = ctx.progressTracker();
            if (tracker == null || tracker.isEmpty()) continue;
            Sinks.Many<AgentEvent> sink = ctx.eventSink();
            if (sink == null) continue;
            String sessionId = ctx.sessionId();
            tracker.snapshotIfStale(PROGRESS_THRESHOLD_MS, inflight -> {
                AgentEvent event = AgentEvent.toolProgress(
                        sessionId,
                        inflight.toolCallId(),
                        inflight.toolName(),
                        inflight.phase().name(),
                        inflight.elapsedMs());
                Sinks.EmitResult emit = AgentRuntimeContext.emitSerialized(sink, event);
                if (emit.isFailure()) {
                    log.debug("Skipped TOOL_PROGRESS for {} ({}): {}",
                            sessionId, inflight.toolCallId(), emit);
                } else {
                    touchAgentDiagnostics(ctx);
                }
            });
        }
    }

    private void touchAgentDiagnostics(SessionContext ctx) {
        try {
            var diag = ctx.entry().session().agent().diagnostics();
            if (diag != null) {
                java.lang.reflect.Method m = diag.getClass().getMethod("recordEvent", String.class);
                m.invoke(diag, "tool_progress");
            }
        } catch (Exception e) {
            // Best-effort; don't let diagnostics failure break the heartbeat loop.
        }
    }

    // ── Query methods ───────────────────────────────────────────────────────────

    public SessionDiagnostics getSessionDiagnostics(String sessionId) {
        SessionContext ctx = registry.get(sessionId);
        if (ctx == null) return null;
        SessionEntry entry = ctx.entry();
        int wsClients = ctx.eventSink().currentSubscriberCount();

        AgentDiagnostics coreDiag = entry.session().agent().diagnostics();
        if (coreDiag != null) {
            long lastEpochMs = coreDiag.lastEventAt() != null
                    ? coreDiag.lastEventAt().toEpochMilli() : 0L;
            return new SessionDiagnostics(
                    sessionId,
                    coreDiag.running(),
                    lastEpochMs,
                    coreDiag.msSinceLastEvent(),
                    coreDiag.eventCounts(),
                    wsClients);
        }

        SessionDiagnosticsTracker tracker = ctx.diagnostics();
        long lastEventAt = tracker.lastEventAt();
        long now = System.currentTimeMillis();
        long msSince = lastEventAt > 0 ? Math.max(0, now - lastEventAt) : Long.MAX_VALUE;
        Map<String, Long> counts = tracker.snapshotCounts();
        boolean running = ctx.running().get();
        return new SessionDiagnostics(sessionId, running, lastEventAt, msSince, counts, wsClients);
    }

    public AgentService.SessionMetricsSnapshot getSessionMetrics(String sessionId) {
        SessionContext ctx = registry.get(sessionId);
        if (ctx == null) return null;
        SessionEntry entry = ctx.entry();

        io.kairo.code.core.hook.SessionMetricsCollector collector = null;
        try {
            collector = entry.session().sessionMetricsCollector();
        } catch (RuntimeException ignored) {
        }

        Map<String, Integer> toolCallCounts = Map.of();
        List<Map<String, Object>> redundantReads = List.of();
        int iterationsNoTool = 0;
        if (collector != null) {
            toolCallCounts = collector.toolCallCountsSnapshot();
            redundantReads = collector.redundantReads().stream()
                    .map(e -> Map.<String, Object>of("file", e.getKey(), "count", e.getValue()))
                    .toList();
            iterationsNoTool = collector.iterationsWithoutToolsCount();
        }

        long tokensUsed = 0L;
        int iterations = 0;
        try {
            AgentDiagnostics agentDiag = entry.session().agent().diagnostics();
            if (agentDiag != null) {
                tokensUsed = agentDiag.totalTokensConsumed();
                iterations = agentDiag.currentIteration();
            }
        } catch (RuntimeException ignored) {
        }
        long durationMs = Math.max(0, System.currentTimeMillis() - entry.createdAt());

        return new AgentService.SessionMetricsSnapshot(
                sessionId, tokensUsed, iterations, durationMs,
                iterationsNoTool, toolCallCounts, redundantReads);
    }

    public Map<String, Map<String, Object>> getSessionToolStats(String sessionId) {
        SessionContext ctx = registry.get(sessionId);
        if (ctx == null) return Map.of();
        SessionEntry entry = ctx.entry();
        try {
            var usageTracker = entry.session().toolUsageTracker();
            if (usageTracker == null) return Map.of();
            return usageTracker.snapshot().entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey,
                            e -> {
                                var s = e.getValue();
                                return Map.<String, Object>of(
                                        "calls", s.calls(),
                                        "successes", s.successes(),
                                        "totalMillis", s.totalMillis(),
                                        "successRate", s.successRate(),
                                        "avgMillis", s.avgMillis());
                            }));
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    public SessionDiagnosticsTracker diagnosticsTrackerFor(String sessionId) {
        SessionContext ctx = registry.get(sessionId);
        if (ctx == null) return new SessionDiagnosticsTracker();
        return ctx.diagnostics();
    }

    @Override
    public void destroy() {
        if (progressTickSubscription != null) {
            progressTickSubscription.dispose();
        }
    }
}
