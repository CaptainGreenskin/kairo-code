package io.kairo.code.service;

import io.kairo.api.agent.Agent;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.team.MessageBus;
import io.kairo.code.core.team.SwarmCoordinator;
import io.kairo.code.core.team.TeamManager;
import io.kairo.code.service.agent.AgentSessionPayload;
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

/**
 * Unified-mode tests: verifies that all legacy session modes ("team", "experts")
 * collapse to "agent" with optional auto-escalation config injection.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class AgentServiceTeamModeTest {

    private static final CodeAgentConfig CONFIG = new CodeAgentConfig(
            "test-key", "https://api.openai.com", "gpt-4o", 50,
            null, null, 0, 0, null);

    @Test
    void createSession_teamMode_normalizesToAgent() throws Exception {
        AgentService service = new AgentService();
        injectTeamPrimitives(service, new TeamManager(), new MessageBus());

        String sid = service.createSession(CONFIG, null, false, "team");
        try {
            AgentService.SessionEntry entry = sessionsFieldOf(service).get(sid);
            assertThat(entry).isNotNull();
            assertThat(entry.sessionMode()).isEqualTo("agent");
            assertThat(entry.payload()).isInstanceOf(AgentSessionPayload.class);
        } finally {
            service.destroySession(sid);
        }
    }

    @Test
    void createSession_teamMode_succeedsEvenWhenUnwired() throws Exception {
        AgentService service = new AgentService();

        String sid = service.createSession(CONFIG, null, false, "team");
        try {
            AgentService.SessionEntry entry = sessionsFieldOf(service).get(sid);
            assertThat(entry).isNotNull();
            assertThat(entry.sessionMode()).isEqualTo("agent");
            assertThat(entry.payload()).isInstanceOf(AgentSessionPayload.class);
        } finally {
            service.destroySession(sid);
        }
    }

    @Test
    void createSession_expertsMode_normalizesToAgentWithEscalation() throws Exception {
        AgentService service = new AgentService();
        injectTeamPrimitives(service, new TeamManager(), new MessageBus());
        injectExpertsPrimitives(service, newStubSwarmCoordinator(), goal -> true);

        String sid = service.createSession(withWorkingDir(), null, false, "experts");
        try {
            AgentService.SessionEntry entry = sessionsFieldOf(service).get(sid);
            assertThat(entry).isNotNull();
            assertThat(entry.sessionMode()).isEqualTo("agent");
            assertThat(entry.payload()).isInstanceOf(AgentSessionPayload.class);

            AgentSessionPayload payload = (AgentSessionPayload) entry.payload();
            assertThat(escalationConfigOf(payload))
                    .as("full primitives wired → escalation config must be injected")
                    .isNotNull();
        } finally {
            service.destroySession(sid);
        }
    }

    @Test
    void createSession_expertsMode_noEscalationWhenSwarmCoordinatorMissing() throws Exception {
        AgentService service = new AgentService();
        injectTeamPrimitives(service, new TeamManager(), new MessageBus());
        injectExpertsPrimitives(service, null, goal -> true);

        String sid = service.createSession(withWorkingDir(), null, false, "experts");
        try {
            AgentService.SessionEntry entry = sessionsFieldOf(service).get(sid);
            assertThat(entry).isNotNull();
            AgentSessionPayload payload = (AgentSessionPayload) entry.payload();
            assertThat(escalationConfigOf(payload))
                    .as("missing SwarmCoordinator → no escalation config")
                    .isNull();
        } finally {
            service.destroySession(sid);
        }
    }

    @Test
    void createSession_expertsMode_noEscalationWhenTriageGateMissing() throws Exception {
        AgentService service = new AgentService();
        injectTeamPrimitives(service, new TeamManager(), new MessageBus());
        injectExpertsPrimitives(service, newStubSwarmCoordinator(), null);

        String sid = service.createSession(withWorkingDir(), null, false, "experts");
        try {
            AgentService.SessionEntry entry = sessionsFieldOf(service).get(sid);
            assertThat(entry).isNotNull();
            AgentSessionPayload payload = (AgentSessionPayload) entry.payload();
            assertThat(escalationConfigOf(payload))
                    .as("missing TriageGate → no escalation config")
                    .isNull();
        } finally {
            service.destroySession(sid);
        }
    }

    @Test
    void createSession_expertsMode_wiresNarratorModelProvider() throws Exception {
        AgentService service = new AgentService();
        injectTeamPrimitives(service, new TeamManager(), new MessageBus());
        injectExpertsPrimitives(service, newStubSwarmCoordinator(), goal -> true);

        String sid = service.createSession(withWorkingDir(), null, false, "experts");
        try {
            AgentService.SessionEntry entry = sessionsFieldOf(service).get(sid);
            assertThat(entry).isNotNull();
            AgentSessionPayload payload = (AgentSessionPayload) entry.payload();
            AgentSessionPayload.EscalationConfig esc = escalationConfigOf(payload);
            assertThat(esc).isNotNull();
            assertThat(esc.narratorModelProvider())
                    .as("escalation config must wire a dedicated ModelProvider for narrator calls")
                    .isNotNull();
        } finally {
            service.destroySession(sid);
        }
    }

    @Test
    void createSession_agentMode_noEscalationWithoutPrimitives() throws Exception {
        AgentService service = new AgentService();
        String sid = service.createSession(CONFIG, null, false, "agent");
        try {
            AgentService.SessionEntry entry = sessionsFieldOf(service).get(sid);
            assertThat(entry).isNotNull();
            AgentSessionPayload payload = (AgentSessionPayload) entry.payload();
            assertThat(escalationConfigOf(payload))
                    .as("agent mode without team primitives → no escalation config")
                    .isNull();
        } finally {
            service.destroySession(sid);
        }
    }

    private static CodeAgentConfig withWorkingDir() throws Exception {
        Path tmp = Files.createTempDirectory("kairo-unified-test-");
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

    private static AgentSessionPayload.EscalationConfig escalationConfigOf(
            AgentSessionPayload payload) throws Exception {
        Field f = AgentSessionPayload.class.getDeclaredField("escalationConfig");
        f.setAccessible(true);
        return (AgentSessionPayload.EscalationConfig) f.get(payload);
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
