package io.kairo.code.server.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kairo.code.server.config.ServerConfig.ServerProperties;
import io.kairo.code.server.config.WorkspacePersistenceService;
import io.kairo.code.service.AgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@code confirmBuild} action wiring in {@link AgentWebSocketHandler}.
 *
 * <p>Regression guard for the bug where {@code confirmBuild} was sent by the UI's
 * ConfirmBuildChip but never routed in the WS handler's switch — falling through to
 * {@code default → "unknown action: confirmBuild"} and silently breaking plan execution.
 */
class ConfirmBuildHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private AgentWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        AgentService agentService = new AgentService();
        ServerProperties props = new ServerProperties(
                "openai", "gpt-4o", "/tmp", "https://api.openai.com", "sk-test");
        WorkspacePersistenceService workspaces = new WorkspacePersistenceService(
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
                        "kairo-test-ws-" + System.nanoTime(), "workspaces.json"));
        handler = new AgentWebSocketHandler(agentService, props, workspaces,
                new io.kairo.multiagent.team.InProcessMessageBus(), null);
    }

    @Test
    void confirmBuild_missingSessionId_sendsError() throws Exception {
        StubWebSocketSession session = new StubWebSocketSession("ws-1");
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(
                "{\"action\":\"confirmBuild\"}"));

        JsonNode reply = lastReply(session);
        assertThat(reply.get("type").asText()).isEqualTo("ERR");
        assertThat(reply.get("ok").asBoolean()).isFalse();
        assertThat(reply.get("action").asText()).isEqualTo("confirmBuild");
        assertThat(reply.get("message").asText()).contains("sessionId");
    }

    @Test
    void confirmBuild_unknownSession_silentNoReply() throws Exception {
        StubWebSocketSession session = new StubWebSocketSession("ws-2");
        handler.afterConnectionEstablished(session);

        // Session does not exist in AgentService — confirmBuild returns false. The
        // handler must NOT push an ERR frame for this benign no-op (it clutters the
        // UI with a phantom error card when the user double-clicks Confirm or the
        // phase has already advanced).
        handler.handleTextMessage(session, new TextMessage(
                "{\"action\":\"confirmBuild\",\"sessionId\":\"nonexistent\"}"));

        assertThat(session.sentMessages).isEmpty();
    }

    @Test
    void confirmBuild_isNotUnknownAction() throws Exception {
        // Regression guard for the original bug: confirmBuild fell through to the
        // default branch which emitted "unknown action: confirmBuild". Even when the
        // session is unknown, the reply (if any) must not be that error.
        StubWebSocketSession session = new StubWebSocketSession("ws-3");
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(
                "{\"action\":\"confirmBuild\",\"sessionId\":\"whatever\"}"));

        // Either no reply (unknown session, the new behavior) or an ACK (a real
        // PLAN_PENDING session, not exercised here). Never "unknown action".
        for (String msg : session.sentMessages) {
            assertThat(msg).doesNotContain("unknown action");
        }
    }

    // ---- helpers ----

    private JsonNode lastReply(StubWebSocketSession session) throws Exception {
        assertThat(session.sentMessages).isNotEmpty();
        return mapper.readTree(session.sentMessages.get(session.sentMessages.size() - 1));
    }

    /**
     * Minimal WebSocketSession stub that captures sent messages.
     */
    private static class StubWebSocketSession implements WebSocketSession {

        private final String id;
        private boolean open = true;
        final List<String> sentMessages = Collections.synchronizedList(new ArrayList<>());
        private final java.util.Map<String, Object> attributes = new java.util.concurrent.ConcurrentHashMap<>();

        StubWebSocketSession(String id) {
            this.id = id;
        }

        @Override public String getId() { return id; }
        @Override public boolean isOpen() { return open; }
        @Override public java.util.Map<String, Object> getAttributes() { return attributes; }

        @Override
        public void sendMessage(org.springframework.web.socket.WebSocketMessage<?> message) {
            sentMessages.add(message.getPayload().toString());
        }

        // --- unused stubs ---
        @Override public java.net.URI getUri() { return null; }
        @Override public org.springframework.http.HttpHeaders getHandshakeHeaders() { return new org.springframework.http.HttpHeaders(); }
        @Override public java.net.InetSocketAddress getLocalAddress() { return null; }
        @Override public java.net.InetSocketAddress getRemoteAddress() { return null; }
        @Override public String getAcceptedProtocol() { return null; }
        @Override public void setTextMessageSizeLimit(int i) {}
        @Override public int getTextMessageSizeLimit() { return 0; }
        @Override public void setBinaryMessageSizeLimit(int i) {}
        @Override public int getBinaryMessageSizeLimit() { return 0; }
        @Override public List<org.springframework.web.socket.WebSocketExtension> getExtensions() { return List.of(); }
        @Override public java.security.Principal getPrincipal() { return null; }
        @Override public void close() {}
        @Override public void close(CloseStatus status) {}
    }
}
