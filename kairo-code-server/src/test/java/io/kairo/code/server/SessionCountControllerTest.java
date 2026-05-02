package io.kairo.code.server;

import io.kairo.code.server.config.ServerConfig.ServerProperties;
import io.kairo.code.server.controller.AgentController;
import io.kairo.code.server.controller.SessionController;
import io.kairo.code.server.dto.CreateSessionRequest;
import io.kairo.code.service.AgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionCountControllerTest {

    private AgentService agentService;
    private SessionController sessionController;
    private AgentController agentController;

    @BeforeEach
    void setUp() {
        agentService = new AgentService();
        sessionController = new SessionController(agentService);
        ServerProperties props = new ServerProperties("openai", "gpt-4o", "/workspace",
                "https://api.openai.com", "sk-test-key");
        agentController = new AgentController(
                new AgentControllerTest.NoOpMessagingTemplate(), agentService, props);
    }

    @Test
    void count_returnsZeroWhenNoSessions() {
        ResponseEntity<Map<String, Integer>> resp = sessionController.count();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).containsEntry("count", 0);
    }

    @Test
    void count_reflectsCreatedSessions() {
        agentController.createSession(new CreateSessionRequest("/ws", null, null, null));
        agentController.createSession(new CreateSessionRequest("/ws2", null, null, null));

        ResponseEntity<Map<String, Integer>> resp = sessionController.count();
        assertThat(resp.getBody()).containsEntry("count", 2);
    }

    @Test
    void count_decreasesAfterDestroy() {
        agentController.createSession(new CreateSessionRequest("/ws", null, null, null));
        String sessionId = agentService.listSessions().get(0).sessionId();
        agentService.destroySession(sessionId);

        ResponseEntity<Map<String, Integer>> resp = sessionController.count();
        assertThat(resp.getBody()).containsEntry("count", 0);
    }
}
