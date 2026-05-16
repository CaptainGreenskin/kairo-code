package io.kairo.code.service.agent;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentState;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.code.service.AgentEvent;
import io.kairo.code.service.SessionPhase;
import io.kairo.code.service.concurrency.AgentConcurrencyController;
import io.kairo.core.tool.DefaultPermissionGuard;
import io.kairo.core.tool.DefaultToolExecutor;
import io.kairo.core.tool.DefaultToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Main path test for {@link AgentSessionPayload#handleMessage(MessageRequest)}.
 * Verifies: runningState transitions, slot acquired+released, events emitted to sink,
 * and phase transitions (IDLE→PLANNING→IDLE).
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class AgentSessionPayloadMainPathTest {

    private static final String SESSION_ID = "test-session-1";

    private AgentConcurrencyController concurrency;
    private Sinks.Many<AgentEvent> sink;
    private AtomicBoolean runningState;
    private AtomicReference<SessionPhase> phaseRef;
    private List<SessionPhase> persistedPhases;

    @BeforeEach
    void setUp() {
        concurrency = new AgentConcurrencyController();
        sink = Sinks.many().replay().all();
        runningState = new AtomicBoolean(false);
        phaseRef = new AtomicReference<>(SessionPhase.IDLE);
        persistedPhases = new ArrayList<>();
    }

    @Test
    void handleMessage_mainPath_runningStateTransitions() throws Exception {
        CountDownLatch agentStarted = new CountDownLatch(1);
        CountDownLatch agentRelease = new CountDownLatch(1);

        Agent agent = stubAgent(input -> Mono.defer(() -> {
            agentStarted.countDown();
            try { agentRelease.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return Mono.just(Msg.of(MsgRole.ASSISTANT, "response"));
        }));

        AgentSessionPayload payload = createPayload(agent);

        assertThat(runningState.get()).isFalse();

        payload.handleMessage(MessageRequest.text("hello"));

        // Wait for agent to start processing
        assertThat(agentStarted.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(runningState.get()).isTrue();

        // Release the agent
        agentRelease.countDown();

        // Wait for doFinally to fire
        Thread.sleep(200);
        assertThat(runningState.get()).isFalse();
    }

    @Test
    void handleMessage_mainPath_emitsThinkingToSink() {
        Agent agent = stubAgent(input -> Mono.just(Msg.of(MsgRole.ASSISTANT, "done")));
        AgentSessionPayload payload = createPayload(agent);

        // Subscribe to sink before calling handleMessage
        List<AgentEvent> events = new ArrayList<>();
        sink.asFlux().subscribe(events::add);

        payload.handleMessage(MessageRequest.text("hi"));

        // Wait for async completion
        await(() -> !runningState.get(), Duration.ofSeconds(3));

        assertThat(events).isNotEmpty();
        assertThat(events.get(0).type()).isEqualTo(AgentEvent.EventType.AGENT_THINKING);
        assertThat(events.get(0).sessionId()).isEqualTo(SESSION_ID);
    }

    @Test
    void handleMessage_mainPath_slotAcquiredAndReleased() {
        Agent agent = stubAgent(input -> Mono.just(Msg.of(MsgRole.ASSISTANT, "ok")));
        AgentSessionPayload payload = createPayload(agent);

        payload.handleMessage(MessageRequest.text("test"));

        // Wait for completion
        await(() -> !runningState.get(), Duration.ofSeconds(3));

        // After completion, slot should be released
        assertThat(concurrency.globalActiveCount()).isEqualTo(0);
        assertThat(concurrency.sessionActiveCount(SESSION_ID)).isEqualTo(0);
    }

    @Test
    void handleMessage_mainPath_phaseTransitions() {
        Agent agent = stubAgent(input -> Mono.just(Msg.of(MsgRole.ASSISTANT, "result")));
        AgentSessionPayload payload = createPayload(agent);

        assertThat(phaseRef.get()).isEqualTo(SessionPhase.IDLE);

        payload.handleMessage(MessageRequest.text("plan something"));

        // Wait for doFinally to complete (which sets phase PLANNING→IDLE)
        await(() -> phaseRef.get() == SessionPhase.IDLE && !runningState.get(), Duration.ofSeconds(3));

        // After agent completes without hook intervention, phase goes PLANNING→IDLE
        assertThat(phaseRef.get()).isEqualTo(SessionPhase.IDLE);
        assertThat(persistedPhases).contains(SessionPhase.PLANNING);
    }

    // ── Helpers ──

    private AgentSessionPayload createPayload(Agent agent) {
        CodeAgentConfig config = new CodeAgentConfig(
                "test-key", "https://api.openai.com", "gpt-4o", 50, null, null, 0, 0, null);
        DefaultToolRegistry registry = new DefaultToolRegistry();
        DefaultToolExecutor executor = new DefaultToolExecutor(registry, new DefaultPermissionGuard());
        CodeAgentSession session = new CodeAgentSession(agent, executor, registry, Set.of());
        AgentRuntimeContext ctx = new AgentRuntimeContext(
                SESSION_ID, sink, runningState, phaseRef,
                phase -> persistedPhases.add(phase), concurrency);
        return new AgentSessionPayload(config, session, ctx);
    }

    private static Agent stubAgent(java.util.function.Function<Msg, Mono<Msg>> callFn) {
        return new Agent() {
            @Override public Mono<Msg> call(Msg input) { return callFn.apply(input); }
            @Override public String id() { return "stub-id"; }
            @Override public String name() { return "stub-agent"; }
            @Override public AgentState state() { return AgentState.IDLE; }
            @Override public void interrupt() {}
        };
    }

    private static void await(java.util.function.BooleanSupplier condition, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Condition not met within " + timeout);
            }
            Thread.onSpinWait();
        }
    }
}
