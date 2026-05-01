package io.kairo.code.server;

import io.kairo.code.service.AgentEvent;
import io.kairo.code.service.AgentService;
import io.kairo.code.server.dto.AgentMessageRequest;
import io.kairo.code.server.dto.CreateSessionRequest;
import io.kairo.code.server.dto.CreateSessionResponse;
import io.kairo.code.server.dto.ServerConfigResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E integration test covering REST + WebSocket endpoints.
 *
 * <p>Tests:
 * <ul>
 *   <li>GET /api/config → 200 OK, body non-empty</li>
 *   <li>GET /api/sessions → 200 OK, returns JSON array</li>
 *   <li>WebSocket /ws — STOMP connect → connection success</li>
 *   <li>STOMP: send to /app/agent/create → receive /topic/session/{id} response</li>
 *   <li>STOMP: send to /app/agent/stop → stops session</li>
 * </ul>
 *
 * <p>Uses a hand-written fake {@link AgentService} because Mockito cannot
 * instrument classes on JVM 25.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = KairoCodeServerApplication.class
)
@TestPropertySource(properties = {
        "kairo.model.provider=openai",
        "kairo.model.api-key=test-key",
        "kairo.model.base-url=http://localhost:9999",
        "kairo.code.provider=openai",
        "kairo.code.api-key=test-key",
        "kairo.code.base-url=http://localhost:9999"
})
@org.springframework.context.annotation.Import(E2EWebSocketIT.MockAgentConfig.class)
class E2EWebSocketIT {

    @LocalServerPort
    int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private FakeAgentService fakeAgentService;

    private WebSocketStompClient stompClient;
    private StompSession stompSession;

    @BeforeEach
    void setUp() throws Exception {
        List<Transport> transports = List.of(new WebSocketTransport(new StandardWebSocketClient()));
        SockJsClient sockJsClient = new SockJsClient(transports);
        stompClient = new WebSocketStompClient(sockJsClient);
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
    }

    @AfterEach
    void tearDown() {
        if (stompSession != null && stompSession.isConnected()) {
            stompSession.disconnect();
        }
    }

    @Test
    void configEndpointReturns200() {
        ResponseEntity<ServerConfigResponse> response = restTemplate.getForEntity(
                "/api/config", ServerConfigResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().provider()).isNotBlank();
    }

    @Test
    void sessionsEndpointReturns200() {
        ResponseEntity<List> response = restTemplate.getForEntity(
                "/api/sessions", List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    void webSocketConnects() throws Exception {
        String url = "http://localhost:" + port + "/ws";
        stompSession = stompClient.connectAsync(url, new StompSessionHandlerAdapter() {}).get(5, TimeUnit.SECONDS);

        assertThat(stompSession).isNotNull();
        assertThat(stompSession.isConnected()).isTrue();
    }

    @Test
    void createSessionViaWebSocket() throws Exception {
        String url = "http://localhost:" + port + "/ws";
        stompSession = stompClient.connectAsync(url, new StompSessionHandlerAdapter() {}).get(5, TimeUnit.SECONDS);

        var future = new CompletableFuture<CreateSessionResponse>();
        subscribeToJson("/topic/session/created", CreateSessionResponse.class, future::complete);

        CreateSessionRequest request = new CreateSessionRequest("/test-workspace", "openai", "gpt-4o", null);
        stompSession.send("/app/agent/create", request);

        CreateSessionResponse response = future.get(5, TimeUnit.SECONDS);
        assertThat(response.sessionId()).isNotBlank();
        assertThat(response.workingDir()).isEqualTo("/test-workspace");
        assertThat(response.model()).isEqualTo("gpt-4o");
    }

    @Test
    void stopSessionViaWebSocket() throws Exception {
        String url = "http://localhost:" + port + "/ws";
        stompSession = stompClient.connectAsync(url, new StompSessionHandlerAdapter() {}).get(5, TimeUnit.SECONDS);

        // Create session first
        var createFuture = new CompletableFuture<CreateSessionResponse>();
        subscribeToJson("/topic/session/created", CreateSessionResponse.class, createFuture::complete);
        stompSession.send("/app/agent/create", new CreateSessionRequest("/ws", "openai", "gpt-4o", null));
        CreateSessionResponse createResp = createFuture.get(5, TimeUnit.SECONDS);

        // Stop the session
        stompSession.send("/app/agent/stop", new AgentMessageRequest(createResp.sessionId(), "ignored"));

        Thread.sleep(500);

        assertThat(fakeAgentService.stopAgentCalled).isTrue();
        assertThat(fakeAgentService.lastStoppedSessionId).isEqualTo(createResp.sessionId());
    }

    /**
     * Subscribe to a STOMP topic and parse JSON messages into the given type.
     */
    private <T> void subscribeToJson(String topic, Class<T> type, java.util.function.Consumer<T> handler) {
        stompSession.subscribe(topic, new StompFrameHandler() {
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

        volatile boolean stopAgentCalled = false;
        volatile String lastStoppedSessionId;

        @Override
        public String createSession(io.kairo.code.core.CodeAgentConfig config) {
            return super.createSession(config);
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

        @Bean
        io.kairo.code.service.concurrency.AgentConcurrencyController agentConcurrencyController() {
            return new io.kairo.code.service.concurrency.AgentConcurrencyController();
        }
    }
}
