package io.kairo.code.server;

import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.server.controller.SessionController;
import io.kairo.code.service.AgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionCountControllerTest {

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
        ResponseEntity<Map<String, Integer>> resp = sessionController.count();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).containsEntry("count", 0);
    }

    @Test
    void count_reflectsCreatedSessions() {
        agentService.createSession(newConfig("/ws"));
        agentService.createSession(newConfig("/ws2"));

        ResponseEntity<Map<String, Integer>> resp = sessionController.count();
        assertThat(resp.getBody()).containsEntry("count", 2);
    }

    @Test
    void count_decreasesAfterDestroy() {
        agentService.createSession(newConfig("/ws"));
        String sessionId = agentService.listSessions().get(0).sessionId();
        agentService.destroySession(sessionId);

        ResponseEntity<Map<String, Integer>> resp = sessionController.count();
        assertThat(resp.getBody()).containsEntry("count", 0);
    }
}
