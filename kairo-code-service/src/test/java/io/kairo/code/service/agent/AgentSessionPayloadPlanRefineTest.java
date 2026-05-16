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
import reactor.core.publisher.Flux;
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
 * Tests plan refinement queuing when session is in PLAN_PENDING state.
 * Verifies requests are enqueued (not started immediately) and eventually processed.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class AgentSessionPayloadPlanRefineTest {

    private static final String SESSION_ID = "refine-session";

    private AgentConcurrencyController concurrency;
    private Sinks.Many<AgentEvent> sink;
    private AtomicBoolean runningState;
    private AtomicReference<SessionPhase> phaseRef;
    private List<Msg> agentCallLog;

    @BeforeEach
    void setUp() {
        concurrency = new AgentConcurrencyController();
        sink = Sinks.many().replay().all();
        runningState = new AtomicBoolean(false);
        phaseRef = new AtomicReference<>(SessionPhase.PLAN_PENDING);
        agentCallLog = new ArrayList<>();
    }

    @Test
    void planPending_enqueuesRefinement_notStartAgent() {
        Agent agent = stubAgent(input -> {
            agentCallLog.add(input);
            return Mono.just(Msg.of(MsgRole.ASSISTANT, "refined"));
        });
        AgentSessionPayload payload = createPayload(agent);

        // When phase is PLAN_PENDING, handleMessage should enqueue
        Flux<AgentEvent> result = payload.handleMessage(MessageRequest.text("refine this"));

        // The result is the sink flux (refinement is processed asynchronously)
        assertThat(result).isNotNull();

        // Wait briefly for async processing to occur
        await(() -> !agentCallLog.isEmpty(), Duration.ofSeconds(3));

        // Agent was called (refinement processed from the queue)
        assertThat(agentCallLog).hasSize(1);
        assertThat(agentCallLog.get(0).text()).isEqualTo("refine this");
    }

    @Test
    void planPending_multipleRefinements_allProcessed() {
        List<String> callOrder = new ArrayList<>();
        Agent agent = stubAgent(input -> {
            synchronized (callOrder) { callOrder.add(input.text()); }
            return Mono.just(Msg.of(MsgRole.ASSISTANT, "ok"));
        });
        AgentSessionPayload payload = createPayload(agent);

        payload.handleMessage(MessageRequest.text("first"));
        payload.handleMessage(MessageRequest.text("second"));

        await(() -> { synchronized (callOrder) { return callOrder.size() >= 2; } }, Duration.ofSeconds(3));

        // Both refinements are processed (order depends on scheduling)
        synchronized (callOrder) {
            assertThat(callOrder).containsExactlyInAnyOrder("first", "second");
        }
    }

    @Test
    void planPending_queueFull_returnsError() throws Exception {
        CountDownLatch agentBlocking = new CountDownLatch(1);
        CountDownLatch releaseAgent = new CountDownLatch(1);

        Agent agent = stubAgent(input -> {
            agentBlocking.countDown();
            try { releaseAgent.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return Mono.just(Msg.of(MsgRole.ASSISTANT, "ok"));
        });
        AgentSessionPayload payload = createPayload(agent);

        // Make the first call from a SEPARATE thread to acquire the lock on that thread.
        // This ensures subsequent calls from THIS thread cannot re-enter the lock.
        Thread firstCallThread = new Thread(() ->
                payload.handleMessage(MessageRequest.text("msg-1")));
        firstCallThread.start();

        // Wait until agent is actually blocking (lock is held by firstCallThread)
        assertThat(agentBlocking.await(3, TimeUnit.SECONDS)).isTrue();

        // Now the lock is held by firstCallThread. Calls from THIS thread's tryLock fail,
        // so messages just enqueue. Fill to MAX (5).
        payload.handleMessage(MessageRequest.text("msg-2"));
        payload.handleMessage(MessageRequest.text("msg-3"));
        payload.handleMessage(MessageRequest.text("msg-4"));
        payload.handleMessage(MessageRequest.text("msg-5"));
        payload.handleMessage(MessageRequest.text("msg-6"));

        // 7th should exceed the 5-item queue limit
        Flux<AgentEvent> overflow = payload.handleMessage(MessageRequest.text("msg-7"));
        List<AgentEvent> events = overflow.take(1).collectList().block();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo(AgentEvent.EventType.AGENT_ERROR);
        assertThat(events.get(0).errorType()).isEqualTo("REFINEMENT_QUEUE_FULL");

        // Cleanup
        releaseAgent.countDown();
        firstCallThread.join(3000);
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
                phase -> {}, concurrency);
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
