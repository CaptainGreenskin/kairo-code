package io.kairo.code.service;

import io.kairo.code.core.CodeAgentConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the idle-session reaper evicts abandoned sessions, leaves running
 * ones alone, and scales linearly to hundreds of sessions.
 *
 * <p>Driven manually via {@link SessionRegistry#reapIdleSessions()} so tests
 * don't depend on the scheduled tick.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class IdleSessionReaperTest {

    @TempDir
    Path tempDir;

    private AgentService service;
    private SessionRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        service = new AgentService();
        Field rf = AgentService.class.getDeclaredField("registry");
        rf.setAccessible(true);
        registry = (SessionRegistry) rf.get(service);
        registry.setDestroyCallback(service::destroySession);
    }

    @AfterEach
    void tearDown() {
        if (service != null) service.destroy();
    }

    @Test
    void reaperEvictsSessionsIdlerThanTtl() {
        String fresh = service.createSession(config("fresh"));
        String stale = service.createSession(config("stale"));
        assertThat(service.listSessions()).hasSize(2);

        backdate(stale, TimeUnit.HOURS.toNanos(2));

        registry.reapIdleSessions();

        assertThat(service.listSessions())
                .as("stale session should be reaped, fresh should remain")
                .extracting(SessionInfo::sessionId)
                .containsExactly(fresh);
    }

    @Test
    void reaperLeavesRunningSessionsAloneEvenIfIdle() {
        String runner = service.createSession(config("runner"));
        backdate(runner, TimeUnit.HOURS.toNanos(2));
        markRunning(runner);

        registry.reapIdleSessions();

        assertThat(service.listSessions())
                .as("a session flagged as running must not be evicted even past TTL")
                .extracting(SessionInfo::sessionId)
                .containsExactly(runner);
    }

    @Test
    void stressOneHundredSessionsThenReapAll() {
        registry.setSessionPoolSizeForTesting(200);
        for (int i = 0; i < 100; i++) {
            String id = service.createSession(config("s-" + i));
            backdate(id, TimeUnit.HOURS.toNanos(1));
        }
        assertThat(service.listSessions()).hasSize(100);

        long t0 = System.nanoTime();
        registry.reapIdleSessions();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertThat(service.listSessions()).isEmpty();
        assertThat(elapsedMs)
                .as("reaper should sweep 100 sessions in well under a second")
                .isLessThan(2000);
    }

    @Test
    void freshSessionsAreNotReaped() {
        for (int i = 0; i < 10; i++) {
            service.createSession(config("keep-" + i));
        }
        for (int i = 0; i < 5; i++) {
            registry.reapIdleSessions();
        }
        assertThat(service.listSessions()).hasSize(10);
    }

    @Test
    void destroyRemovesFromRegistry() {
        String sid = service.createSession(config("once"));
        assertThat(registry.get(sid)).isNotNull();

        service.destroySession(sid);
        assertThat(registry.get(sid)).isNull();
    }

    @Test
    void poolCapEvictsLruWhenAtCapacity() {
        registry.setSessionPoolSizeForTesting(3);

        String a = service.createSession(config("a"));
        backdate(a, TimeUnit.MINUTES.toNanos(10));
        String b = service.createSession(config("b"));
        backdate(b, TimeUnit.MINUTES.toNanos(5));
        String c = service.createSession(config("c"));
        assertThat(service.listSessions()).hasSize(3);

        String d = service.createSession(config("d"));
        assertThat(service.listSessions())
                .as("'a' (oldest activity) should have been evicted to fit 'd'")
                .extracting(SessionInfo::sessionId)
                .containsExactlyInAnyOrder(b, c, d);
    }

    @Test
    void poolCapSkipsRunningSessions() {
        registry.setSessionPoolSizeForTesting(2);

        String r1 = service.createSession(config("r1"));
        backdate(r1, TimeUnit.MINUTES.toNanos(10));
        markRunning(r1);
        String r2 = service.createSession(config("r2"));
        backdate(r2, TimeUnit.MINUTES.toNanos(5));
        markRunning(r2);

        String r3 = service.createSession(config("r3"));
        assertThat(service.listSessions())
                .as("no eviction when every candidate is running")
                .hasSize(3);
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

    private void backdate(String sessionId, long ageNanos) {
        SessionContext ctx = registry.get(sessionId);
        if (ctx != null) {
            ctx.lastActivityNs().set(System.nanoTime() - ageNanos);
        }
    }

    private void markRunning(String sessionId) {
        SessionContext ctx = registry.get(sessionId);
        if (ctx != null) {
            ctx.running().set(true);
        }
    }
}
