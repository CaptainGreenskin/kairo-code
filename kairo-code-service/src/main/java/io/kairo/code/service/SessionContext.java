package io.kairo.code.service;

import io.kairo.code.service.AgentService.SessionEntry;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Aggregates all per-session mutable state into a single value that lives in
 * {@link SessionRegistry}'s map. Replaces the 10 independent ConcurrentHashMaps
 * that previously tracked the same sessionId across separate containers.
 *
 * <p>Lifecycle: created atomically in {@link SessionFactory#createSession},
 * registered via {@link SessionRegistry#register}, and removed via
 * {@link SessionRegistry#unregister}. Because all state lives in one object,
 * partial-cleanup races are eliminated.
 */
public final class SessionContext {

    private final String sessionId;
    private volatile SessionEntry entry;
    private final Sinks.Many<AgentEvent> eventSink;
    private final AtomicBoolean running;
    private final AtomicLong lastActivityNs;
    private final ToolProgressTracker progressTracker;
    private final AtomicReference<SessionPhase> phase;
    private volatile SessionDiagnosticsTracker diagnostics;
    private volatile String planOverview;

    public SessionContext(
            String sessionId,
            SessionEntry entry,
            Sinks.Many<AgentEvent> eventSink,
            AtomicBoolean running,
            ToolProgressTracker progressTracker,
            AtomicReference<SessionPhase> phase) {
        this.sessionId = sessionId;
        this.entry = entry;
        this.eventSink = eventSink;
        this.running = running;
        this.lastActivityNs = new AtomicLong(System.nanoTime());
        this.progressTracker = progressTracker;
        this.phase = phase;
        this.diagnostics = new SessionDiagnosticsTracker();
    }

    public String sessionId() { return sessionId; }
    public SessionEntry entry() { return entry; }
    public Sinks.Many<AgentEvent> eventSink() { return eventSink; }
    public AtomicBoolean running() { return running; }
    public AtomicLong lastActivityNs() { return lastActivityNs; }
    public ToolProgressTracker progressTracker() { return progressTracker; }
    public AtomicReference<SessionPhase> phase() { return phase; }
    public SessionDiagnosticsTracker diagnostics() { return diagnostics; }
    public String planOverview() { return planOverview; }
    public void setPlanOverview(String overview) { this.planOverview = overview; }
    public void setDiagnosticsTracker(SessionDiagnosticsTracker tracker) { this.diagnostics = tracker; }
    public void setEntry(SessionEntry entry) { this.entry = entry; }

    /** Touch activity timestamp — called on message send and session creation. */
    public void touch() {
        lastActivityNs.set(System.nanoTime());
    }
}
