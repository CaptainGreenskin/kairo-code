package io.kairo.code.service;

import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.team.MessageBus;
import io.kairo.code.core.team.TeamManager;
import io.kairo.code.service.agent.TeamSessionPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M-Team / #60: tests for the {@code "team"} sessionMode arm of
 * {@link AgentService#createSession(CodeAgentConfig, String, boolean, String)}.
 *
 * <p>Verifies the two halves of the guard:
 * <ul>
 *   <li>When {@code teamManager} + {@code messageBus} are wired (mirrors prod DI),
 *       createSession succeeds and the entry's payload is {@link TeamSessionPayload}.</li>
 *   <li>When either primitive is missing, createSession throws
 *       {@link IllegalStateException} so misconfigured deploys fail loud.</li>
 * </ul>
 *
 * <p>Uses reflection to inject the {@code @Autowired(required=false)} fields without
 * standing up a full Spring context — the unit-test style used throughout this module.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class AgentServiceTeamModeTest {

    private static final CodeAgentConfig CONFIG = new CodeAgentConfig(
            "test-key", "https://api.openai.com", "gpt-4o", 50,
            null, null, 0, 0, null);

    @Test
    void createSession_teamMode_succeedsWhenWired() throws Exception {
        AgentService service = new AgentService();
        injectTeamPrimitives(service, new TeamManager(), new MessageBus());

        String sid = service.createSession(CONFIG, null, false, "team");
        try {
            assertThat(sid).isNotBlank();

            // listSessions surfaces the recorded mode — easiest cross-check.
            assertThat(service.listSessions())
                    .anyMatch(info -> info.sessionId().equals(sid));

            // Drill into the private session map to confirm payload type — this is the
            // only way to assert TeamSessionPayload was actually instantiated without
            // exposing an internal accessor purely for tests.
            AgentService.SessionEntry entry = sessionsFieldOf(service).get(sid);
            assertThat(entry).isNotNull();
            assertThat(entry.sessionMode()).isEqualTo("team");
            assertThat(entry.payload()).isInstanceOf(TeamSessionPayload.class);
        } finally {
            service.destroySession(sid);
        }
    }

    @Test
    void createSession_teamMode_failsWhenUnwired() {
        AgentService service = new AgentService(); // teamManager/messageBus both null

        assertThatThrownBy(() -> service.createSession(CONFIG, null, false, "team"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("team mode unavailable");
    }

    @Test
    void createSession_teamMode_failsWhenOnlyTeamManagerWired() throws Exception {
        AgentService service = new AgentService();
        injectTeamPrimitives(service, new TeamManager(), null);

        // Half-wiring should fail the same way — guards the diagonal that you don't
        // accidentally light up team mode just because one bean got registered.
        assertThatThrownBy(() -> service.createSession(CONFIG, null, false, "team"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("team mode unavailable");
    }

    // ── reflection helpers ──────────────────────────────────────────────────

    private static void injectTeamPrimitives(AgentService service,
                                             TeamManager tm,
                                             MessageBus mb) throws Exception {
        setField(service, "teamManager", tm);
        setField(service, "messageBus", mb);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = AgentService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, AgentService.SessionEntry> sessionsFieldOf(
            AgentService service) throws Exception {
        Field f = AgentService.class.getDeclaredField("sessions");
        f.setAccessible(true);
        return (Map<String, AgentService.SessionEntry>) f.get(service);
    }
}
