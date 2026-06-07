package io.kairo.code.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Thread-safe registry of all live {@link SessionContext} instances.
 *
 * <p>Replaces the 10 independent ConcurrentHashMaps that previously existed in AgentService.
 * Owns idle-session reaping and LRU eviction so the session pool stays bounded.
 */
@Component
public class SessionRegistry implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    private final Map<String, SessionContext> contexts = new ConcurrentHashMap<>();

    private volatile ScheduledExecutorService idleReaper;
    private final long idleTtlMillis;
    private final long reaperPeriodMillis;
    private volatile int sessionPoolSize;
    private volatile Consumer<String> destroyCallback;

    public SessionRegistry() {
        this.idleTtlMillis = resolveIdleTtlMillis();
        this.reaperPeriodMillis = Math.max(1_000L, Math.min(idleTtlMillis / 10, 60_000L));
        this.sessionPoolSize = resolveSessionPoolSize();
    }

    // ── Public API ──────────────────────────────────────────────────────────────

    public void register(SessionContext ctx) {
        contexts.put(ctx.sessionId(), ctx);
    }

    public SessionContext get(String sessionId) {
        return contexts.get(sessionId);
    }

    public SessionContext unregister(String sessionId) {
        return contexts.remove(sessionId);
    }

    public int size() {
        return contexts.size();
    }

    public List<SessionContext> list() {
        return List.copyOf(contexts.values());
    }

    public boolean contains(String sessionId) {
        return contexts.containsKey(sessionId);
    }

    public Set<String> sessionIds() {
        return Set.copyOf(contexts.keySet());
    }

    /**
     * Returns the event Flux for a session. Callers (WebSocket handlers) subscribe
     * to receive all events emitted by the agent loop.
     */
    public Flux<AgentEvent> sessionEvents(String sessionId) {
        SessionContext ctx = contexts.get(sessionId);
        if (ctx == null) return Flux.empty();
        return ctx.eventSink().asFlux();
    }

    /**
     * Set a callback invoked when the registry needs to destroy a session
     * (idle reap or LRU eviction). The callback receives the sessionId and
     * is responsible for full cleanup (persistence, worktree release, etc.).
     * When no callback is set, the registry does minimal cleanup itself.
     */
    public void setDestroyCallback(Consumer<String> callback) {
        this.destroyCallback = callback;
    }

    /**
     * Start the idle reaper. Called by AgentService after wiring the destroy callback.
     * Not started in the constructor to avoid reaping before the callback is set.
     */
    public void startReaper() {
        startIdleReaper();
    }

    /**
     * Evict the least-recently-active idle session if the pool is at capacity.
     * Called before creating a new session to enforce the pool size cap.
     * Skips sessions that are currently running.
     */
    public void evictLruIfFull() {
        if (contexts.size() < sessionPoolSize) return;
        contexts.values().stream()
                .filter(c -> !c.running().get())
                .min(Comparator.comparingLong(c -> c.lastActivityNs().get()))
                .ifPresent(lru -> {
                    log.info("Pool full ({}/{}), evicting LRU session {}",
                            contexts.size(), sessionPoolSize, lru.sessionId());
                    invokeDestroy(lru.sessionId());
                });
    }

    /** Visible for testing. */
    void setSessionPoolSizeForTesting(int cap) {
        this.sessionPoolSize = cap;
    }

    // ── Idle Reaper ─────────────────────────────────────────────────────────────

    private void startIdleReaper() {
        ScheduledThreadPoolExecutor exec = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "session-idle-reaper");
            t.setDaemon(true);
            return t;
        });
        exec.setRemoveOnCancelPolicy(true);
        this.idleReaper = exec;
        idleReaper.scheduleAtFixedRate(this::reapIdleSessions,
                reaperPeriodMillis, reaperPeriodMillis, TimeUnit.MILLISECONDS);
    }

    void reapIdleSessions() {
        long nowNs = System.nanoTime();
        long thresholdNs = TimeUnit.MILLISECONDS.toNanos(idleTtlMillis);
        int reaped = 0;
        for (SessionContext ctx : List.copyOf(contexts.values())) {
            long idle = nowNs - ctx.lastActivityNs().get();
            if (idle <= thresholdNs) continue;
            if (ctx.running().get()) continue;
            log.info("Reaping idle session {} (idle {}s)",
                    ctx.sessionId(), TimeUnit.NANOSECONDS.toSeconds(idle));
            invokeDestroy(ctx.sessionId());
            reaped++;
        }
        if (reaped > 0) log.info("Idle reaper evicted {} session(s)", reaped);
    }

    private void invokeDestroy(String sessionId) {
        Consumer<String> cb = destroyCallback;
        if (cb != null) {
            cb.accept(sessionId);
        } else {
            SessionContext ctx = contexts.remove(sessionId);
            if (ctx != null) cleanupContext(ctx);
        }
    }

    private void cleanupContext(SessionContext ctx) {
        try {
            ctx.entry().approvalHandler().cancelAll();
            ctx.entry().payload().stop();
            ctx.eventSink().tryEmitComplete();
        } catch (Exception e) {
            log.warn("Error cleaning up session {}: {}", ctx.sessionId(), e.getMessage());
        }
    }

    // ── Spring lifecycle ────────────────────────────────────────────────────────

    @Override
    public void destroy() {
        if (idleReaper != null) {
            idleReaper.shutdownNow();
        }
        contexts.values().forEach(this::cleanupContext);
        contexts.clear();
    }

    // ── Config resolution ───────────────────────────────────────────────────────

    private static long resolveIdleTtlMillis() {
        String env = System.getenv("KAIRO_CODE_SESSION_IDLE_TTL_MINUTES");
        if (env == null || env.isBlank()) return TimeUnit.MINUTES.toMillis(60);
        try {
            long mins = Long.parseLong(env.trim());
            if (mins <= 0) return TimeUnit.MINUTES.toMillis(60);
            return TimeUnit.MINUTES.toMillis(mins);
        } catch (NumberFormatException ignored) {
            return TimeUnit.MINUTES.toMillis(60);
        }
    }

    private static int resolveSessionPoolSize() {
        String env = System.getenv("KAIRO_CODE_SESSION_POOL_SIZE");
        if (env == null || env.isBlank()) return 64;
        try {
            int v = Integer.parseInt(env.trim());
            return v > 0 ? v : 64;
        } catch (NumberFormatException ignored) {
            return 64;
        }
    }
}
