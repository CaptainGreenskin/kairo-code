package io.kairo.code.server.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kairo.api.team.TeamEventType;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Tests for the team subscription extensions in {@link AgentWebSocketHandler}.
 */
class TeamSubscriptionTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private AgentWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        AgentService agentService = new AgentService();
        ServerProperties props = new ServerProperties("openai", "gpt-4o", "/tmp", "https://api.openai.com", "sk-test");
        WorkspacePersistenceService workspaces = new WorkspacePersistenceService(
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "kairo-test-ws-" + System.nanoTime(), "workspaces.json"));
        handler = new AgentWebSocketHandler(agentService, props, workspaces,
                new io.kairo.multiagent.team.InProcessMessageBus());
    }

    // ---- subscribeTeam ----

    @Test
    void subscribeTeam_validTeamId_sendsAck() throws Exception {
        StubWebSocketSession session = new StubWebSocketSession("ws-1");
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(
                "{\"action\":\"subscribeTeam\",\"teamId\":\"team-abc\"}"));

        JsonNode reply = lastReply(session);
        assertThat(reply.get("type").asText()).isEqualTo("ACK");
        assertThat(reply.get("ok").asBoolean()).isTrue();
        assertThat(reply.get("action").asText()).isEqualTo("subscribeTeam");

        // Subscriber should be tracked
        assertThat(handler.getTeamSubscribers("team-abc")).contains(session);
    }

    @Test
    void subscribeTeam_blankTeamId_sendsError() throws Exception {
        StubWebSocketSession session = new StubWebSocketSession("ws-2");
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(
                "{\"action\":\"subscribeTeam\",\"teamId\":\"\"}"));

        JsonNode reply = lastReply(session);
        assertThat(reply.get("type").asText()).isEqualTo("ERR");
        assertThat(reply.get("ok").asBoolean()).isFalse();
        assertThat(reply.get("action").asText()).isEqualTo("subscribeTeam");
    }

    @Test
    void subscribeTeam_missingTeamId_sendsError() throws Exception {
        StubWebSocketSession session = new StubWebSocketSession("ws-3");
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(
                "{\"action\":\"subscribeTeam\"}"));

        JsonNode reply = lastReply(session);
        assertThat(reply.get("type").asText()).isEqualTo("ERR");
    }

    // ---- unsubscribeTeam ----

    @Test
    void unsubscribeTeam_removesSubscription() throws Exception {
        StubWebSocketSession session = new StubWebSocketSession("ws-4");
        handler.afterConnectionEstablished(session);

        // Subscribe first
        handler.handleTextMessage(session, new TextMessage(
                "{\"action\":\"subscribeTeam\",\"teamId\":\"team-xyz\"}"));
        assertThat(handler.getTeamSubscribers("team-xyz")).contains(session);

        // Now unsubscribe
        handler.handleTextMessage(session, new TextMessage(
                "{\"action\":\"unsubscribeTeam\",\"teamId\":\"team-xyz\"}"));

        JsonNode reply = lastReply(session);
        assertThat(reply.get("type").asText()).isEqualTo("ACK");
        assertThat(reply.get("action").asText()).isEqualTo("unsubscribeTeam");
        assertThat(handler.getTeamSubscribers("team-xyz")).doesNotContain(session);
    }

    // ---- afterConnectionClosed cleans up team subscriptions ----

    @Test
    void afterConnectionClosed_cleansUpTeamSubscriptions() throws Exception {
        StubWebSocketSession session = new StubWebSocketSession("ws-5");
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(
                "{\"action\":\"subscribeTeam\",\"teamId\":\"team-1\"}"));
        handler.handleTextMessage(session, new TextMessage(
                "{\"action\":\"subscribeTeam\",\"teamId\":\"team-2\"}"));
        assertThat(handler.getTeamSubscribers("team-1")).contains(session);
        assertThat(handler.getTeamSubscribers("team-2")).contains(session);

        // Close connection
        session.markClosed();
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThat(handler.getTeamSubscribers("team-1")).doesNotContain(session);
        assertThat(handler.getTeamSubscribers("team-2")).doesNotContain(session);
    }

    // ---- broadcastTeamEvent ----

    @Test
    void broadcastTeamEvent_sendsToAllSubscribers() throws Exception {
        StubWebSocketSession s1 = new StubWebSocketSession("ws-a");
        StubWebSocketSession s2 = new StubWebSocketSession("ws-b");
        handler.afterConnectionEstablished(s1);
        handler.afterConnectionEstablished(s2);

        handler.handleTextMessage(s1, new TextMessage(
                "{\"action\":\"subscribeTeam\",\"teamId\":\"team-broadcast\"}"));
        handler.handleTextMessage(s2, new TextMessage(
                "{\"action\":\"subscribeTeam\",\"teamId\":\"team-broadcast\"}"));

        String eventJson = "{\"type\":\"TEAM_EVENT\",\"teamId\":\"team-broadcast\",\"eventType\":\"STEP_THINKING\",\"seq\":1}";
        handler.broadcastTeamEvent("team-broadcast", eventJson);

        // Both sessions should have received the event (in addition to the ACK from subscribe)
        assertThat(s1.sentMessages).anyMatch(m -> m.contains("TEAM_EVENT"));
        assertThat(s2.sentMessages).anyMatch(m -> m.contains("TEAM_EVENT"));
    }

    @Test
    void broadcastTeamEvent_noSubscribers_doesNothing() {
        // Should not throw
        assertThatNoException().isThrownBy(() ->
                handler.broadcastTeamEvent("no-such-team", "{\"type\":\"TEAM_EVENT\"}"));
    }

    // ---- getTeamSubscribers ----

    @Test
    void getTeamSubscribers_unknownTeam_returnsEmptySet() {
        Set<WebSocketSession> subs = handler.getTeamSubscribers("nonexistent");
        assertThat(subs).isEmpty();
    }

    // ---- TeamEventType new values ----

    @Test
    void teamEventType_newValuesExist() {
        assertThat(TeamEventType.valueOf("STEP_THINKING")).isNotNull();
        assertThat(TeamEventType.valueOf("STEP_TOOL_CALL")).isNotNull();
        assertThat(TeamEventType.valueOf("STEP_ARTIFACT_CHUNK")).isNotNull();
    }

    @Test
    void teamEventType_originalValuesStillExist() {
        assertThat(TeamEventType.valueOf("TEAM_STARTED")).isNotNull();
        assertThat(TeamEventType.valueOf("STEP_ASSIGNED")).isNotNull();
        assertThat(TeamEventType.valueOf("STEP_COMPLETED")).isNotNull();
        assertThat(TeamEventType.valueOf("TEAM_COMPLETED")).isNotNull();
        assertThat(TeamEventType.valueOf("TEAM_FAILED")).isNotNull();
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

        void markClosed() { this.open = false; }

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
        @Override public java.util.List<org.springframework.web.socket.WebSocketExtension> getExtensions() { return List.of(); }
        @Override public java.security.Principal getPrincipal() { return null; }
        @Override public void close() {}
        @Override public void close(CloseStatus status) {}
    }
}
