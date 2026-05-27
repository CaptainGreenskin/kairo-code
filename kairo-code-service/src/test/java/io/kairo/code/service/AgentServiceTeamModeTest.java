package io.kairo.code.service;

import io.kairo.api.agent.Agent;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.team.MessageBus;
import io.kairo.code.core.team.SwarmCoordinator;
import io.kairo.code.core.team.TeamManager;
import io.kairo.code.service.agent.TeamSessionPayload;
import io.kairo.code.service.agent.tools.NoNarrationTool;
import io.kairo.code.service.team.TriageGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    // ── #61 M-Experts-Upgrade: experts arm extensions ──────────────────────

    @Test
    void createSession_expertsMode_succeedsWhenWired() throws Exception {
        AgentService service = new AgentService();
        injectTeamPrimitives(service, new TeamManager(), new MessageBus());
        injectExpertsPrimitives(service, newStubSwarmCoordinator(), goal -> true);

        String sid = service.createSession(expertsConfig(), null, false, "experts");
        try {
            AgentService.SessionEntry entry = sessionsFieldOf(service).get(sid);
            assertThat(entry).isNotNull();
            assertThat(entry.sessionMode()).isEqualTo("experts");
            assertThat(entry.payload()).isInstanceOf(TeamSessionPayload.class);
            TeamSessionPayload payload = (TeamSessionPayload) entry.payload();
            // The preset must be wired — proves we took the experts arm, not the team arm.
            assertThat(payload.preset()).isNotNull();
        } finally {
            service.destroySession(sid);
        }
    }

    @Test
    void createSession_expertsMode_failsWhenSwarmCoordinatorMissing() throws Exception {
        AgentService service = new AgentService();
        injectTeamPrimitives(service, new TeamManager(), new MessageBus());
        injectExpertsPrimitives(service, null, goal -> true);

        assertThatThrownBy(() -> service.createSession(expertsConfig(), null, false, "experts"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("experts mode unavailable");
    }

    @Test
    void createSession_expertsMode_failsWhenTriageGateMissing() throws Exception {
        AgentService service = new AgentService();
        injectTeamPrimitives(service, new TeamManager(), new MessageBus());
        injectExpertsPrimitives(service, newStubSwarmCoordinator(), null);

        assertThatThrownBy(() -> service.createSession(expertsConfig(), null, false, "experts"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("experts mode unavailable");
    }

    @Test
    void createSession_expertsMode_wiresNarratorModelProvider() throws Exception {
        AgentService service = new AgentService();
        injectTeamPrimitives(service, new TeamManager(), new MessageBus());
        injectExpertsPrimitives(service, newStubSwarmCoordinator(), goal -> true);

        String sid = service.createSession(expertsConfig(), null, false, "experts");
        try {
            AgentService.SessionEntry entry = sessionsFieldOf(service).get(sid);
            assertThat(entry).isNotNull();
            TeamSessionPayload payload = (TeamSessionPayload) entry.payload();
            assertThat(payload.preset()).isNotNull();
            assertThat(payload.preset().narratorModelProvider())
                    .as("experts arm must wire a dedicated ModelProvider for narrator calls")
                    .isNotNull();
            // no_narration should NOT be in the session agent's tool registry (direct model call)
            boolean hasNoNarration = payload.session().toolRegistry().getAll().stream()
                    .anyMatch(t -> NoNarrationTool.NAME.equals(t.name()));
            assertThat(hasNoNarration)
                    .as("no_narration must not pollute the session agent's tool registry")
                    .isFalse();
        } finally {
            service.destroySession(sid);
        }
    }

    @Test
    void createSession_agentMode_doesNotRegisterNoNarrationTool() throws Exception {
        // Mode-isolation regression: the no_narration tool must NOT leak into agent mode.
        AgentService service = new AgentService();
        String sid = service.createSession(CONFIG, null, false, "agent");
        try {
            AgentService.SessionEntry entry = sessionsFieldOf(service).get(sid);
            assertThat(entry).isNotNull();
            boolean hasNoNarration = entry.session().toolRegistry().getAll().stream()
                    .anyMatch(t -> NoNarrationTool.NAME.equals(t.name()));
            assertThat(hasNoNarration)
                    .as("agent mode must not see the experts-only no_narration tool")
                    .isFalse();
        } finally {
            service.destroySession(sid);
        }
    }

    /**
     * Experts mode resolves {@code config.workingDir()} into a {@code Path} for the lesson
     * store (see AgentService:323). Tests use a tmp dir to avoid NPE without polluting
     * a real workspace.
     */
    private static CodeAgentConfig expertsConfig() throws Exception {
        Path tmp = Files.createTempDirectory("kairo-experts-test-");
        tmp.toFile().deleteOnExit();
        return new CodeAgentConfig(
                "test-key", "https://api.openai.com", "gpt-4o", 50,
                tmp.toString(), null, 0, 0, null);
    }

    // ── reflection helpers ──────────────────────────────────────────────────

    private static void injectTeamPrimitives(AgentService service,
                                             TeamManager tm,
                                             MessageBus mb) throws Exception {
        setField(service, "teamManager", tm);
        setField(service, "messageBus", mb);
    }

    private static void injectExpertsPrimitives(AgentService service,
                                                SwarmCoordinator sc,
                                                TriageGate tg) throws Exception {
        setField(service, "swarmCoordinator", sc);
        setField(service, "triageGate", tg);
    }

    private static SwarmCoordinator newStubSwarmCoordinator() {
        var registry = new io.kairo.expertteam.role.ExpertRoleRegistry();
        var planner = new io.kairo.expertteam.internal.DefaultPlanner(registry, null, null);
        var coord = new io.kairo.expertteam.ExpertTeamCoordinator(
                null, new io.kairo.expertteam.SimpleEvaluationStrategy(),
                null, planner, registry);
        return new SwarmCoordinator(
                coord, registry, new io.kairo.expertteam.tck.NoopMessageBus(), List.<Agent>of());
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
