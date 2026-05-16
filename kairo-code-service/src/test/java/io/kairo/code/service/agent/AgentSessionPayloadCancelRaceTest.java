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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency race condition test: launch handleMessage in one thread, call stop()
 * concurrently. Asserts that runningState terminal state is ALWAYS false after each iteration.
 */
@Tag("concurrency")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class AgentSessionPayloadCancelRaceTest {

    @Test
    void handleMessageAndStop_raceCondition_runningAlwaysFalseAtEnd() throws Exception {
        // JVM warmup: run 10 iterations without assertions
        for (int i = 0; i < 10; i++) {
            runSingleIteration();
        }

        // Actual test: 1000 iterations
        AtomicInteger failures = new AtomicInteger(0);
        for (int i = 0; i < 1000; i++) {
            AtomicBoolean finalRunning = runSingleIteration();

            // Allow doFinally to fire on boundedElastic
            awaitFalse(finalRunning, Duration.ofSeconds(2));

            if (finalRunning.get()) {
                failures.incrementAndGet();
            }
        }

        assertThat(failures.get()).isZero();
    }

    private AtomicBoolean runSingleIteration() throws Exception {
        AtomicBoolean runningState = new AtomicBoolean(false);
        Sinks.Many<AgentEvent> sink = Sinks.many().multicast().onBackpressureBuffer();
        AtomicReference<SessionPhase> phaseRef = new AtomicReference<>(SessionPhase.IDLE);
        AgentConcurrencyController concurrency = new AgentConcurrencyController();

        CountDownLatch started = new CountDownLatch(1);
        Agent agent = new Agent() {
            @Override
            public Mono<Msg> call(Msg input) {
                started.countDown();
                // Simulate a tiny amount of work
                return Mono.delay(Duration.ofMillis(1))
                        .map(l -> Msg.of(MsgRole.ASSISTANT, "done"));
            }
            @Override public String id() { return "race-id"; }
            @Override public String name() { return "race-agent"; }
            @Override public AgentState state() { return AgentState.IDLE; }
            @Override public void interrupt() {}
        };

        CodeAgentConfig config = new CodeAgentConfig(
                "test-key", "https://api.openai.com", "gpt-4o", 50, null, null, 0, 0, null);
        DefaultToolRegistry registry = new DefaultToolRegistry();
        DefaultToolExecutor executor = new DefaultToolExecutor(registry, new DefaultPermissionGuard());
        CodeAgentSession session = new CodeAgentSession(agent, executor, registry, Set.of());
        AgentRuntimeContext ctx = new AgentRuntimeContext(
                "race-session", sink, runningState, phaseRef,
                phase -> {}, concurrency);
        AgentSessionPayload payload = new AgentSessionPayload(config, session, ctx);

        ExecutorService pool = Executors.newFixedThreadPool(2);

        // Launch handleMessage in one thread
        pool.submit(() -> payload.handleMessage(MessageRequest.text("go")));

        // Wait for agent subscription to start, then stop() concurrently
        started.await(2, TimeUnit.SECONDS);
        pool.submit(payload::stop);

        pool.shutdown();
        pool.awaitTermination(3, TimeUnit.SECONDS);

        return runningState;
    }

    private static void awaitFalse(AtomicBoolean flag, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (flag.get()) {
            if (System.currentTimeMillis() > deadline) {
                break; // Don't throw — let the assertion at the call site report the failure
            }
            Thread.onSpinWait();
        }
    }
}
