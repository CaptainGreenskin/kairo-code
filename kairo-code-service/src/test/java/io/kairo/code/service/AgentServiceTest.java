package io.kairo.code.service;

import io.kairo.code.core.CodeAgentConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentServiceTest {

    private final AgentService service = new AgentService();

    @Test
    void createSessionReturnsNonEmptyUuid() {
        String id = service.createSession(testConfig());
        assertThat(id).isNotBlank();
    }

    @Test
    void listSessionsContainsCreatedSession() {
        String id = service.createSession(testConfig());
        var sessions = service.listSessions();
        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).sessionId()).isEqualTo(id);
        assertThat(sessions.get(0).model()).isEqualTo("gpt-4o");
        assertThat(sessions.get(0).running()).isFalse();
    }

    @Test
    void destroySessionRemovesFromList() {
        String id = service.createSession(testConfig());
        assertThat(service.listSessions()).hasSize(1);

        boolean destroyed = service.destroySession(id);
        assertThat(destroyed).isTrue();
        assertThat(service.listSessions()).isEmpty();
    }

    @Test
    void destroySessionReturnsFalseForUnknownId() {
        assertThat(service.destroySession("unknown")).isFalse();
    }

    @Test
    void listSessionsReturnsEmptyInitially() {
        assertThat(service.listSessions()).isEmpty();
    }

    @Test
    void stopAgentOnNonRunningSessionDoesNotThrow() {
        String id = service.createSession(testConfig());
        service.stopAgent(id); // should not throw
    }

    @Test
    void stopAgentOnNonExistentSessionDoesNotThrow() {
        service.stopAgent("nonexistent"); // should not throw
    }

    @Test
    void sendMessageToNonExistentSessionEmitsError() {
        var flux = service.sendMessage("nonexistent", "hello");
        var events = flux.collectList().block();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo(AgentEvent.EventType.AGENT_ERROR);
        assertThat(events.get(0).errorMessage()).contains("Session not found");
    }

    @Test
    void createMultipleSessions() {
        String id1 = service.createSession(testConfig());
        String id2 = service.createSession(testConfig());
        assertThat(id1).isNotEqualTo(id2);
        assertThat(service.listSessions()).hasSize(2);
    }

    @Test
    void destroyAllSessions() {
        service.createSession(testConfig());
        service.createSession(testConfig());
        for (var info : service.listSessions()) {
            service.destroySession(info.sessionId());
        }
        assertThat(service.listSessions()).isEmpty();
    }

    private static CodeAgentConfig testConfig() {
        return new CodeAgentConfig(
                "test-api-key", "https://api.openai.com", "gpt-4o", 50, null, null, 0, 0);
    }
}
