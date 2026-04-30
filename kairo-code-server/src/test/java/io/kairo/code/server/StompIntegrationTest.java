package io.kairo.code.server;

import io.kairo.code.service.AgentEvent;
import io.kairo.code.service.AgentService;
import io.kairo.code.server.dto.AgentMessageRequest;
import io.kairo.code.server.dto.CreateSessionRequest;
import io.kairo.code.server.dto.CreateSessionResponse;
import io.kairo.code.server.dto.ToolApprovalRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;
import reactor.core.publisher.Flux;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full STOMP integration test — connects via real WebSocket to the
 * embedded server, sends STOMP messages, and verifies responses.
 *
 * <p>Uses a hand-written fake {@link AgentService} because Mockito cannot
 * instrument classes on JVM 25.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = KairoCodeServerApplication.class
)
@TestPropertySource(properties = {
        "kairo.code.api-key=sk-test-key",
        "kairo.model.api-key=sk-test-key",
        "anthropic.api-key=sk-test-key",
        "openai.api-key=sk-test-key",
})
@org.springframework.context.annotation.Import(StompIntegrationTest.MockAgentConfig.class)
class StompIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private FakeAgentService fakeAgentService;

    private WebSocketStompClient stompClient;
    private StompSession session;

    @BeforeEach
    void setUp() throws Exception {
        List<Transport> transports = List.of(new WebSocketTransport(new StandardWebSocketClient()));
        SockJsClient sockJsClient = new SockJsClient(transports);
        stompClient = new WebSocketStompClient(sockJsClient);
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        String url = "http://localhost:" + port + "/ws";
        session = stompClient.connectAsync(url, new StompSessionHandlerAdapter() {}).get(5, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() {
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
    }

    @Test
    void createSession_receivesSessionId() throws Exception {
        var future = new CompletableFuture<CreateSessionResponse>();
        subscribeToJson("/topic/session/created", CreateSessionResponse.class, future::complete);

        CreateSessionRequest request = new CreateSessionRequest("/test-workspace", null, "gpt-4o", null);
        session.send("/app/agent/create", request);

        CreateSessionResponse response = future.get(5, TimeUnit.SECONDS);
        assertThat(response.sessionId()).isNotBlank();
        assertThat(response.workingDir()).isEqualTo("/test-workspace");
        assertThat(response.model()).isEqualTo("gpt-4o");
    }

    @Test
    void sendMessage_receivesThinkingThenDone() throws Exception {
        // Create session first
        var createFuture = new CompletableFuture<CreateSessionResponse>();
        subscribeToJson("/topic/session/created", CreateSessionResponse.class, createFuture::complete);
        session.send("/app/agent/create", new CreateSessionRequest("/ws", null, "gpt-4o", null));
        CreateSessionResponse createResp = createFuture.get(5, TimeUnit.SECONDS);

        // Configure fake service to emit AGENT_THINKING then AGENT_DONE
        AgentEvent thinking = AgentEvent.thinking(createResp.sessionId());
        AgentEvent done = AgentEvent.done(createResp.sessionId(), 100, 0.005);
        fakeAgentService.setSendMessageResult(Flux.just(thinking, done));

        // Subscribe to the session topic
        var events = new CopyOnWriteArrayList<AgentEvent>();
        var doneFuture = new CompletableFuture<Void>();
        subscribeToJson("/topic/session/" + createResp.sessionId(), AgentEvent.class, event -> {
            events.add(event);
            if (event.type() == AgentEvent.EventType.AGENT_DONE) {
                doneFuture.complete(null);
            }
        });

        // Send message
        session.send("/app/agent/message", new AgentMessageRequest(createResp.sessionId(), "hello"));

        doneFuture.get(10, TimeUnit.SECONDS);

        assertThat(events).hasSize(2);
        assertThat(events.get(0).type()).isEqualTo(AgentEvent.EventType.AGENT_THINKING);
        assertThat(events.get(1).type()).isEqualTo(AgentEvent.EventType.AGENT_DONE);
    }

    @Test
    void sendMessage_receivesTextChunk() throws Exception {
        // Create session first
        var createFuture = new CompletableFuture<CreateSessionResponse>();
        subscribeToJson("/topic/session/created", CreateSessionResponse.class, createFuture::complete);
        session.send("/app/agent/create", new CreateSessionRequest("/ws", null, "gpt-4o", null));
        CreateSessionResponse createResp = createFuture.get(5, TimeUnit.SECONDS);

        // Configure fake service to emit TEXT_CHUNK then AGENT_DONE
        AgentEvent chunk = AgentEvent.textChunk(createResp.sessionId(), "Hello world");
        AgentEvent done = AgentEvent.done(createResp.sessionId(), 50, 0.002);
        fakeAgentService.setSendMessageResult(Flux.just(chunk, done));

        // Subscribe to session events
        var events = new CopyOnWriteArrayList<AgentEvent>();
        var doneFuture = new CompletableFuture<Void>();
        subscribeToJson("/topic/session/" + createResp.sessionId(), AgentEvent.class, event -> {
            events.add(event);
            if (event.type() == AgentEvent.EventType.AGENT_DONE) {
                doneFuture.complete(null);
            }
        });

        session.send("/app/agent/message", new AgentMessageRequest(createResp.sessionId(), "hi"));

        doneFuture.get(10, TimeUnit.SECONDS);

        assertThat(events).hasSize(2);
        assertThat(events.get(0).type()).isEqualTo(AgentEvent.EventType.TEXT_CHUNK);
        assertThat(events.get(0).content()).isEqualTo("Hello world");
    }

    @Test
    void approveTool_delegatesToService() throws Exception {
        // Create session first
        var createFuture = new CompletableFuture<CreateSessionResponse>();
        subscribeToJson("/topic/session/created", CreateSessionResponse.class, createFuture::complete);
        session.send("/app/agent/create", new CreateSessionRequest("/ws", null, "gpt-4o", null));
        CreateSessionResponse createResp = createFuture.get(5, TimeUnit.SECONDS);

        // Approve tool
        session.send("/app/agent/approve",
                new ToolApprovalRequest(createResp.sessionId(), "tc-1", true, null));

        Thread.sleep(500);

        assertThat(fakeAgentService.approveToolCalled).isTrue();
        assertThat(fakeAgentService.lastApproveToolCallId).isEqualTo("tc-1");
        assertThat(fakeAgentService.lastApproved).isTrue();
    }

    @Test
    void stopAgent_delegatesToService() throws Exception {
        // Create session first
        var createFuture = new CompletableFuture<CreateSessionResponse>();
        subscribeToJson("/topic/session/created", CreateSessionResponse.class, createFuture::complete);
        session.send("/app/agent/create", new CreateSessionRequest("/ws", null, "gpt-4o", null));
        CreateSessionResponse createResp = createFuture.get(5, TimeUnit.SECONDS);

        // Stop agent
        session.send("/app/agent/stop", new AgentMessageRequest(createResp.sessionId(), "ignored"));

        Thread.sleep(500);

        assertThat(fakeAgentService.stopAgentCalled).isTrue();
        assertThat(fakeAgentService.lastStoppedSessionId).isEqualTo(createResp.sessionId());
    }

    /**
     * Subscribe to a STOMP topic and parse JSON messages into the given type.
     */
    private <T> void subscribeToJson(String topic, Class<T> type, java.util.function.Consumer<T> handler) {
        session.subscribe(topic, new org.springframework.messaging.simp.stomp.StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return type;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                handler.accept(type.cast(payload));
            }
        });
    }

    /**
     * Hand-written fake AgentService — replaces @MockBean which doesn't work on JVM 25.
     */
    static class FakeAgentService extends AgentService {

        volatile Flux<AgentEvent> sendMessageResult = Flux.empty();
        volatile boolean approveToolCalled = false;
        volatile String lastApproveToolCallId;
        volatile boolean lastApproved;
        volatile boolean stopAgentCalled = false;
        volatile String lastStoppedSessionId;

        void setSendMessageResult(Flux<AgentEvent> result) {
            this.sendMessageResult = result;
        }

        @Override
        public String createSession(io.kairo.code.core.CodeAgentConfig config) {
            return super.createSession(config);
        }

        @Override
        public Flux<AgentEvent> sendMessage(String sessionId, String text) {
            return sendMessageResult;
        }

        @Override
        public boolean approveTool(String sessionId, String toolCallId, boolean approved, String reason) {
            approveToolCalled = true;
            lastApproveToolCallId = toolCallId;
            lastApproved = approved;
            return true;
        }

        @Override
        public void stopAgent(String sessionId) {
            stopAgentCalled = true;
            lastStoppedSessionId = sessionId;
        }
    }

    @Configuration
    static class MockAgentConfig {

        @Bean
        @Primary
        FakeAgentService fakeAgentService() {
            return new FakeAgentService();
        }
    }
}
