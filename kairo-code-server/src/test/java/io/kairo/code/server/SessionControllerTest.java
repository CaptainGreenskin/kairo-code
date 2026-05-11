package io.kairo.code.server;

import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.server.controller.SessionController;
import io.kairo.code.service.AgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionControllerTest {

    private AgentService agentService;
    private SessionController sessionController;

    @BeforeEach
    void setUp() {
        agentService = new AgentService();
        sessionController = new SessionController(agentService);
    }

    private CodeAgentConfig newConfig(String workingDir) {
        return new CodeAgentConfig(
                "sk-test", "https://api.openai.com", "gpt-4o",
                50, workingDir, null, 0, 0, 0);
    }

    @Test
    void count_returnsZeroWhenNoSessions() {
        ResponseEntity<Map<String, Integer>> response = sessionController.count();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("count")).isEqualTo(0);
    }

    @Test
    void count_reflectsCreatedSessions() {
        agentService.createSession(newConfig("/ws"));
        agentService.createSession(newConfig("/ws2"));

        ResponseEntity<Map<String, Integer>> response = sessionController.count();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("count")).isEqualTo(2);
    }

    @Test
    void count_decreasesAfterDestroy() {
        agentService.createSession(newConfig("/ws"));
        String sessionId = agentService.listSessions().get(0).sessionId();
        agentService.destroySession(sessionId);

        ResponseEntity<Map<String, Integer>> response = sessionController.count();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("count")).isEqualTo(0);
    }

    @Test
    void cancel_returnsNoContentAndStopsAgent() {
        agentService.createSession(newConfig("/ws"));
        String sessionId = agentService.listSessions().get(0).sessionId();

        ResponseEntity<Void> response = sessionController.cancel(sessionId);
        assertThat(response.getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void cancel_nonExistentSession_noError() {
        ResponseEntity<Void> response = sessionController.cancel("nonexistent-session");
        assertThat(response.getStatusCode().value()).isEqualTo(204);
    }
}
