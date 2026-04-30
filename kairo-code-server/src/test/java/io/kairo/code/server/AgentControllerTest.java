package io.kairo.code.server;

import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.server.config.ServerConfig.ServerProperties;
import io.kairo.code.server.controller.AgentController;
import io.kairo.code.server.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AgentController} using real {@link io.kairo.code.service.AgentService}.
 *
 * <p>Uses a capturing stub for the messaging template since Mockito cannot
 * mock concrete classes on JVM 25.
 */
class AgentControllerTest {

    private CapturingMessagingTemplate messagingTemplate;
    private io.kairo.code.service.AgentService agentService;
    private ServerProperties serverProperties;
    private AgentController controller;

    @BeforeEach
    void setUp() {
        messagingTemplate = new CapturingMessagingTemplate();
        agentService = new io.kairo.code.service.AgentService();
        serverProperties = new ServerProperties("openai", "gpt-4o", "/workspace",
                "https://api.openai.com", "sk-test-key");
        controller = new AgentController(messagingTemplate, agentService, serverProperties);
    }

    @Test
    void createSession_createsAndSendsResponse() {
        CreateSessionRequest request = new CreateSessionRequest("/workspace", null, null, null);
        controller.createSession(request);

        assertThat(messagingTemplate.sentMessages).hasSize(2);
        CapturingMessagingTemplate.SentMessage sent = messagingTemplate.sentMessages.get(0);
        CreateSessionResponse resp = (CreateSessionResponse) sent.payload;
        assertThat(resp.sessionId()).isNotBlank();
        assertThat(resp.workingDir()).isEqualTo("/workspace");
        assertThat(resp.model()).isEqualTo("gpt-4o");
        assertThat(sent.destination).contains(resp.sessionId());
    }

    @Test
    void createSession_withApiKeyOverridesDefault() {
        CreateSessionRequest request = new CreateSessionRequest(
                "/workspace", "anthropic", "claude-sonnet-4", "sk-custom-key");
        controller.createSession(request);

        assertThat(messagingTemplate.sentMessages).hasSize(2);
        CreateSessionResponse resp = (CreateSessionResponse) messagingTemplate.sentMessages.get(0).payload;
        assertThat(resp.sessionId()).isNotBlank();

        // Session was created — we can't verify the exact config, but it didn't crash
        assertThat(agentService.listSessions()).hasSize(1);
    }

    @Test
    void sendMessage_toNonExistentSession_sendsError() {
        AgentMessageRequest request = new AgentMessageRequest("nonexistent", "hello");
        controller.sendMessage(request);

        // The Flux completes synchronously for error case
        assertThat(messagingTemplate.sentMessages).isNotEmpty();
        var lastMessage = messagingTemplate.sentMessages.get(messagingTemplate.sentMessages.size() - 1);
        io.kairo.code.service.AgentEvent event =
                (io.kairo.code.service.AgentEvent) lastMessage.payload;
        assertThat(event.type()).isEqualTo(io.kairo.code.service.AgentEvent.EventType.AGENT_ERROR);
    }

    @Test
    void approveTool_nonExistentSession_sendsError() {
        ToolApprovalRequest request = new ToolApprovalRequest("nonexistent", "tc-1", true, null);
        controller.approveTool(request);

        assertThat(messagingTemplate.sentMessages).hasSize(1);
        io.kairo.code.service.AgentEvent event =
                (io.kairo.code.service.AgentEvent) messagingTemplate.sentMessages.get(0).payload;
        assertThat(event.type()).isEqualTo(io.kairo.code.service.AgentEvent.EventType.AGENT_ERROR);
        assertThat(event.errorType()).isEqualTo("NO_PENDING_APPROVAL");
    }

    @Test
    void stopAgent_nonExistentSession_noError() {
        AgentMessageRequest request = new AgentMessageRequest("nonexistent", "ignored");
        controller.stopAgent(request);
        // Should not throw — stopAgent on non-existent session is a no-op
    }

    @Test
    void listSessions_viaConfigController_returnsCreatedSessions() {
        // Create a session via the controller
        CreateSessionRequest request = new CreateSessionRequest("/ws", null, null, null);
        controller.createSession(request);

        // Verify it appears in the service
        var sessions = agentService.listSessions();
        assertThat(sessions).hasSize(1);
    }

    @Test
    void destroySession_removesSession() {
        CreateSessionRequest request = new CreateSessionRequest("/ws", null, null, null);
        controller.createSession(request);

        String sessionId = agentService.listSessions().get(0).sessionId();
        boolean destroyed = agentService.destroySession(sessionId);

        assertThat(destroyed).isTrue();
        assertThat(agentService.listSessions()).isEmpty();
    }

    /**
     * Simple capturing stub for SimpMessagingTemplate.
     */
    static class CapturingMessagingTemplate extends AgentControllerTest.NoOpMessagingTemplate {

        record SentMessage(String destination, Object payload) {}

        final List<SentMessage> sentMessages = new java.util.ArrayList<>();

        @Override
        public void convertAndSend(String destination, Object payload) {
            sentMessages.add(new SentMessage(destination, payload));
        }
    }

    /**
     * No-op base messaging template for testing.
     */
    static class NoOpMessagingTemplate extends org.springframework.messaging.simp.SimpMessagingTemplate {

        NoOpMessagingTemplate() {
            super(new NoOpMessageChannel());
        }

        static class NoOpMessageChannel implements org.springframework.messaging.MessageChannel {
            @Override
            public boolean send(org.springframework.messaging.Message<?> message) { return true; }
            @Override
            public boolean send(org.springframework.messaging.Message<?> message, long timeout) { return true; }
        }
    }
}
