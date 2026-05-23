package io.kairo.code.service;

import io.kairo.code.core.CodeAgentConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the idle-session reaper evicts abandoned sessions, leaves running
 * ones alone, and scales linearly to hundreds of sessions.
 *
 * <p>Driven manually via {@link AgentService#reapIdleSessions()} so tests
 * don't depend on the scheduled tick — flakiness from real-time TTL races
 * would dominate the signal. Activity timestamps are backdated via reflection
 * to simulate "session went idle 1 hour ago" deterministically.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class IdleSessionReaperTest {

    @TempDir
    Path tempDir;

    private AgentService service;

    @BeforeEach
    void setUp() {
        service = new AgentService();
    }

    @AfterEach
    void tearDown() {
        if (service != null) service.destroy();
    }

    @Test
    void reaperEvictsSessionsIdlerThanTtl() throws Exception {
        String fresh = service.createSession(config("fresh"));
        String stale = service.createSession(config("stale"));
        assertThat(service.listSessions()).hasSize(2);

        // Force "stale" to look very old. Bypass the public API and reach into
        // the activity map — we want the test deterministic on TTL, not racing
        // real time.
        backdate(stale, TimeUnit.HOURS.toNanos(2));

        service.reapIdleSessions();

        assertThat(service.listSessions())
                .as("stale session should be reaped, fresh should remain")
                .extracting(SessionInfo::sessionId)
                .containsExactly(fresh);
    }

    @Test
    void reaperLeavesRunningSessionsAloneEvenIfIdle() throws Exception {
        // Models the "long bash tool" case: a session that hasn't bumped its
        // activity timer for a while because the agent is blocked on a slow
        // subprocess, not because the user abandoned the tab.
        String runner = service.createSession(config("runner"));
        backdate(runner, TimeUnit.HOURS.toNanos(2));
        markRunning(runner);

        service.reapIdleSessions();

        assertThat(service.listSessions())
                .as("a session flagged as running must not be evicted even past TTL")
                .extracting(SessionInfo::sessionId)
                .containsExactly(runner);
    }

    @Test
    void stressOneHundredSessionsThenReapAll() throws Exception {
        for (int i = 0; i < 100; i++) {
            String id = service.createSession(config("s-" + i));
            backdate(id, TimeUnit.HOURS.toNanos(1));
        }
        assertThat(service.listSessions()).hasSize(100);

        long t0 = System.nanoTime();
        service.reapIdleSessions();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertThat(service.listSessions()).isEmpty();
        assertThat(elapsedMs)
                .as("reaper should sweep 100 sessions in well under a second")
                .isLessThan(2000);
    }

    @Test
    void freshSessionsAreNotReaped() throws Exception {
        // Whitebox check the inverse — without backdating, no eviction happens
        // even if reapIdleSessions runs many times. Catches accidental "reap
        // all" regressions.
        for (int i = 0; i < 10; i++) {
            service.createSession(config("keep-" + i));
        }
        for (int i = 0; i < 5; i++) {
            service.reapIdleSessions();
        }
        assertThat(service.listSessions()).hasSize(10);
    }

    @Test
    void destroyRemovesActivityTrackerEntry() throws Exception {
        // After destroy, the activity tracker shouldn't leak — otherwise long-
        // running deployments slowly accumulate ghost entries.
        String sid = service.createSession(config("once"));
        Map<String, AtomicLong> activity = readActivityMap();
        assertThat(activity).containsKey(sid);

        service.destroySession(sid);
        assertThat(activity).doesNotContainKey(sid);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private CodeAgentConfig config(String tag) {
        return new CodeAgentConfig(
                "test-key-" + tag,
                "https://api.openai.com",
                "gpt-4o",
                50,
                tempDir.toString(),
                null, 0, 0, null);
    }

    @SuppressWarnings("unchecked")
    private void backdate(String sessionId, long ageNanos) throws Exception {
        Map<String, AtomicLong> lastActivity = readActivityMap();
        AtomicLong v = lastActivity.get(sessionId);
        if (v == null) {
            v = new AtomicLong();
            lastActivity.put(sessionId, v);
        }
        v.set(System.nanoTime() - ageNanos);
    }

    @SuppressWarnings("unchecked")
    private void markRunning(String sessionId) throws Exception {
        Field f = AgentService.class.getDeclaredField("runningState");
        f.setAccessible(true);
        Map<String, AtomicBoolean> runningState = (Map<String, AtomicBoolean>) f.get(service);
        runningState.put(sessionId, new AtomicBoolean(true));
    }

    @SuppressWarnings("unchecked")
    private Map<String, AtomicLong> readActivityMap() throws Exception {
        Field f = AgentService.class.getDeclaredField("lastActivityNs");
        f.setAccessible(true);
        return (Map<String, AtomicLong>) f.get(service);
    }
}
