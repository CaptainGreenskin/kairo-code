package io.kairo.code.server.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kairo.api.event.KairoEvent;
import io.kairo.api.event.KairoEventBus;
import io.kairo.api.team.TeamEvent;
import io.kairo.api.team.TeamEventType;
import io.kairo.code.core.team.persistence.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Tests for {@link TeamEventBridge}.
 */
class TeamEventBridgeTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private StubKairoEventBus stubEventBus;
    private StubTeamRepository stubRepository;
    private TeamEventBridge bridge;
    private AgentWebSocketHandler wsHandler;

    @BeforeEach
    void setUp() {
        io.kairo.code.service.AgentService agentService = new io.kairo.code.service.AgentService();
        io.kairo.code.server.config.ServerConfig.ServerProperties props =
                new io.kairo.code.server.config.ServerConfig.ServerProperties(
                        "openai", "gpt-4o", "/tmp", "https://api.openai.com", "sk-test");
        io.kairo.code.server.config.WorkspacePersistenceService workspaces =
                new io.kairo.code.server.config.WorkspacePersistenceService(
                        java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
                                "kairo-test-bridge-" + System.nanoTime(), "workspaces.json"));
        wsHandler = new AgentWebSocketHandler(agentService, props, workspaces,
                new io.kairo.multiagent.team.InProcessMessageBus(), null);

        stubEventBus = new StubKairoEventBus();
        stubRepository = new StubTeamRepository();
        bridge = new TeamEventBridge(wsHandler, stubEventBus, stubRepository, mapper);
        bridge.init();
    }

    // ---- Test 1: Event published on KairoEventBus → arrives at subscriber session ----

    @Test
    void eventPublished_arrivesAtSubscriberSession() throws Exception {
        StubWebSocketSession session = subscribeSession("ws-1", "team-abc");

        // Publish a team event
        publishTeamEvent("team-abc", TeamEventType.STEP_COMPLETED, Map.of("stepId", "step-1"));

        // Session should receive the event
        assertThat(session.sentMessages).anyMatch(m -> m.contains("TEAM_EVENT"));
        assertThat(session.sentMessages).anyMatch(m -> m.contains("STEP_COMPLETED"));
    }

    // ---- Test 2: Seq numbers are monotonically increasing per team ----

    @Test
    void seqNumbers_monotonicallyIncreasing() throws Exception {
        StubWebSocketSession session = subscribeSession("ws-2", "team-seq");

        publishTeamEvent("team-seq", TeamEventType.STEP_ASSIGNED, Map.of("stepId", "s1"));
        publishTeamEvent("team-seq", TeamEventType.STEP_COMPLETED, Map.of("stepId", "s1"));
        publishTeamEvent("team-seq", TeamEventType.STEP_THINKING, Map.of("text", "thinking..."));

        // Extract seq numbers from messages
        List<Long> seqNumbers = new ArrayList<>();
        for (String msg : session.sentMessages) {
            if (msg.contains("TEAM_EVENT")) {
                JsonNode node = mapper.readTree(msg);
                seqNumbers.add(node.get("seq").asLong());
            }
        }

        assertThat(seqNumbers).hasSize(3);
        assertThat(seqNumbers).isSorted();
        assertThat(seqNumbers.get(0)).isEqualTo(1L);
        assertThat(seqNumbers.get(1)).isEqualTo(2L);
        assertThat(seqNumbers.get(2)).isEqualTo(3L);
    }

    // ---- Test 3: Different teams have independent seq counters ----

    @Test
    void differentTeams_independentSeqCounters() throws Exception {
        StubWebSocketSession sessionA = subscribeSession("ws-a", "team-alpha");
        StubWebSocketSession sessionB = subscribeSession("ws-b", "team-beta");

        publishTeamEvent("team-alpha", TeamEventType.TEAM_STARTED, Map.of());
        publishTeamEvent("team-alpha", TeamEventType.STEP_ASSIGNED, Map.of("stepId", "s1"));
        publishTeamEvent("team-beta", TeamEventType.TEAM_STARTED, Map.of());

        // team-alpha should be at seq 2, team-beta at seq 1
        assertThat(bridge.currentSeq("team-alpha")).isEqualTo(2L);
        assertThat(bridge.currentSeq("team-beta")).isEqualTo(1L);

        // Verify the session messages
        JsonNode betaEvent = findTeamEvent(sessionB, "TEAM_STARTED");
        assertThat(betaEvent).isNotNull();
        assertThat(betaEvent.get("seq").asLong()).isEqualTo(1L);
    }

    // ---- Test 4: High-freq event with no subscribers → no error ----

    @Test
    void highFreqEvent_noSubscribers_noError() {
        assertThatNoException().isThrownBy(() ->
                publishTeamEvent("no-such-team", TeamEventType.STEP_THINKING, Map.of("text", "hello")));
    }

    // ---- Test 5: JSON format matches expected structure ----

    @Test
    void jsonFormat_matchesExpectedStructure() throws Exception {
        StubWebSocketSession session = subscribeSession("ws-fmt", "team-fmt");

        publishTeamEvent("team-fmt", TeamEventType.STEP_TOOL_CALL,
                Map.of("stepId", "step-coder-1", "toolName", "read_file"));

        String eventMsg = session.sentMessages.stream()
                .filter(m -> m.contains("TEAM_EVENT"))
                .findFirst()
                .orElseThrow();

        JsonNode json = mapper.readTree(eventMsg);
        assertThat(json.get("type").asText()).isEqualTo("TEAM_EVENT");
        assertThat(json.get("teamId").asText()).isEqualTo("team-fmt");
        assertThat(json.get("eventType").asText()).isEqualTo("STEP_TOOL_CALL");
        assertThat(json.get("seq").asLong()).isEqualTo(1L);
        assertThat(json.get("stepId").asText()).isEqualTo("step-coder-1");
        assertThat(json.has("attributes")).isTrue();
        assertThat(json.get("attributes").get("toolName").asText()).isEqualTo("read_file");
        assertThat(json.has("timestamp")).isTrue();
        // Timestamp should be ISO-8601 parseable
        assertThatNoException().isThrownBy(() -> Instant.parse(json.get("timestamp").asText()));
    }

    // ---- Test 6: replayEvents sends only events with seq > fromSeq ----

    @Test
    void replayEvents_sendsOnlyEventsAfterFromSeq() throws Exception {
        StubWebSocketSession session = subscribeSession("ws-replay", "team-replay");

        // Pre-publish events to advance seq counter
        publishTeamEvent("team-replay", TeamEventType.TEAM_STARTED, Map.of());
        publishTeamEvent("team-replay", TeamEventType.STEP_ASSIGNED, Map.of("stepId", "s1"));
        publishTeamEvent("team-replay", TeamEventType.STEP_COMPLETED, Map.of("stepId", "s1"));

        // Clear sent messages to isolate replay
        session.sentMessages.clear();

        // Set up repository to return events for replay
        stubRepository.setEventsForTeam("team-replay", List.of(
                new TeamEvent(TeamEventType.TEAM_STARTED, "team-replay", "req-1", Instant.now(), Map.of()),
                new TeamEvent(TeamEventType.STEP_ASSIGNED, "team-replay", "req-1", Instant.now(), Map.of("stepId", "s1")),
                new TeamEvent(TeamEventType.STEP_COMPLETED, "team-replay", "req-1", Instant.now(), Map.of("stepId", "s1"))
        ));

        // Replay from seq 4 (i.e. only events with seq > 4 should arrive)
        // Current seq is 3, so replay generates seq 4, 5, 6. All > 4: 5, 6
        bridge.replayEvents(session, "team-replay", 4L);

        // Should have received 2 events (seq 5 and 6)
        long teamEventCount = session.sentMessages.stream()
                .filter(m -> m.contains("TEAM_EVENT"))
                .count();
        assertThat(teamEventCount).isEqualTo(2L);
    }

    // ---- helpers ----

    private StubWebSocketSession subscribeSession(String wsId, String teamId) throws Exception {
        StubWebSocketSession session = new StubWebSocketSession(wsId);
        wsHandler.afterConnectionEstablished(session);
        wsHandler.handleTextMessage(session, new TextMessage(
                "{\"action\":\"subscribeTeam\",\"teamId\":\"" + teamId + "\"}"));
        // Clear the ACK message so we only see team events
        session.sentMessages.clear();
        return session;
    }

    private void publishTeamEvent(String teamId, TeamEventType type, Map<String, Object> attributes) {
        TeamEvent teamEvent = new TeamEvent(type, teamId, "req-" + System.nanoTime(),
                Instant.parse("2026-05-13T07:00:00Z"), attributes);
        KairoEvent kairoEvent = teamEvent.toKairoEvent();
        stubEventBus.emit(kairoEvent);
    }

    private JsonNode findTeamEvent(StubWebSocketSession session, String eventType) throws Exception {
        for (String msg : session.sentMessages) {
            if (msg.contains("TEAM_EVENT")) {
                JsonNode node = mapper.readTree(msg);
                if (eventType.equals(node.get("eventType").asText())) {
                    return node;
                }
            }
        }
        return null;
    }

    // ---- stubs ----

    /**
     * Stub KairoEventBus that allows direct event emission for testing.
     */
    private static class StubKairoEventBus implements KairoEventBus {

        private final Sinks.Many<KairoEvent> sink = Sinks.many().multicast().onBackpressureBuffer();

        @Override
        public void publish(KairoEvent event) {
            sink.tryEmitNext(event);
        }

        @Override
        public Flux<KairoEvent> subscribe() {
            return sink.asFlux();
        }

        @Override
        public Flux<KairoEvent> subscribe(String domain) {
            return sink.asFlux().filter(e -> domain.equals(e.domain()));
        }

        void emit(KairoEvent event) {
            sink.tryEmitNext(event);
        }
    }

    /**
     * Stub TeamRepository that returns pre-configured events.
     */
    private static class StubTeamRepository implements TeamRepository {

        private final java.util.concurrent.ConcurrentHashMap<String, List<TeamEvent>> events =
                new java.util.concurrent.ConcurrentHashMap<>();

        void setEventsForTeam(String teamId, List<TeamEvent> teamEvents) {
            events.put(teamId, teamEvents);
        }

        @Override
        public Flux<TeamEvent> loadEvents(String teamId) {
            List<TeamEvent> list = events.getOrDefault(teamId, List.of());
            return Flux.fromIterable(list);
        }

        @Override
        public reactor.core.publisher.Mono<Void> saveManifest(String teamId,
                io.kairo.code.core.team.persistence.TeamManifest manifest) {
            return reactor.core.publisher.Mono.empty();
        }

        @Override
        public reactor.core.publisher.Mono<Void> saveStepOutcome(String teamId, String stepId,
                io.kairo.code.core.team.persistence.StepOutcomeRecord outcome) {
            return reactor.core.publisher.Mono.empty();
        }

        @Override
        public reactor.core.publisher.Mono<Void> appendEvent(String teamId, TeamEvent event) {
            return reactor.core.publisher.Mono.empty();
        }

        @Override
        public reactor.core.publisher.Mono<io.kairo.code.core.team.persistence.TeamManifest> loadManifest(String teamId) {
            return reactor.core.publisher.Mono.empty();
        }

        @Override
        public reactor.core.publisher.Mono<io.kairo.code.core.team.persistence.StepOutcomeRecord> loadStepOutcome(
                String teamId, String stepId) {
            return reactor.core.publisher.Mono.empty();
        }

        @Override
        public Flux<io.kairo.code.core.team.persistence.TeamManifest> loadIncomplete() {
            return Flux.empty();
        }

        @Override
        public reactor.core.publisher.Mono<Void> delete(String teamId) {
            return reactor.core.publisher.Mono.empty();
        }
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
