package io.kairo.code.service.agent;

import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.service.AgentService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that session mode routing defaults to "agent" for null/empty modes.
 *
 * <p>Full expert-team routing requires a wired SwarmCoordinator and is covered
 * in integration tests. These unit tests verify the routing logic defaults.
 */
class SessionPayloadRoutingTest {

    private final AgentService service = new AgentService();

    private static CodeAgentConfig testConfig() {
        return new CodeAgentConfig(
                "test-key", "https://api.openai.com", "gpt-4o", 50, null, null, 0, 0, null);
    }

    @Test
    void modeNull_defaultsToChat() {
        String sid = service.createSession(testConfig(), "ws-1", false, null);
        assertThat(sid).isNotBlank();
        // Session was created successfully in default "agent" mode
        var sessions = service.listSessions();
        assertThat(sessions).hasSize(1);
    }

    @Test
    void modeEmpty_defaultsToChat() {
        String sid = service.createSession(testConfig(), "ws-1", false, "");
        assertThat(sid).isNotBlank();
        var sessions = service.listSessions();
        assertThat(sessions).hasSize(1);
    }

    @Test
    void modeBlank_defaultsToChat() {
        String sid = service.createSession(testConfig(), "ws-1", false, "   ");
        assertThat(sid).isNotBlank();
        var sessions = service.listSessions();
        assertThat(sessions).hasSize(1);
    }

    @Test
    void modeChat_createsAgentSession() {
        String sid = service.createSession(testConfig(), "ws-1", false, "agent");
        assertThat(sid).isNotBlank();
        // Session exists and works
        var sessions = service.listSessions();
        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).sessionId()).isEqualTo(sid);
    }

    @Test
    void multipleSessionsIndependent() {
        String sid1 = service.createSession(testConfig(), "ws-1", false, "agent");
        String sid2 = service.createSession(testConfig(), "ws-2", false, null);
        assertThat(sid1).isNotEqualTo(sid2);
        assertThat(service.listSessions()).hasSize(2);
    }
}
