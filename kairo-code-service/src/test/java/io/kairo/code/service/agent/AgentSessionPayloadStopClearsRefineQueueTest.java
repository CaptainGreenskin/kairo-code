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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that calling {@link AgentSessionPayload#stop()} clears the refinement queue
 * and resets runningState to false.
 */
@Timeout(value = 5, unit = TimeUnit.SECONDS)
class AgentSessionPayloadStopClearsRefineQueueTest {

    private static final String SESSION_ID = "stop-queue-session";

    @Test
    void stop_clearsRefineQueue_setsRunningFalse() {
        AtomicBoolean runningState = new AtomicBoolean(false);
        Sinks.Many<AgentEvent> sink = Sinks.many().replay().all();
        AtomicReference<SessionPhase> phaseRef = new AtomicReference<>(SessionPhase.PLAN_PENDING);
        AgentConcurrencyController concurrency = new AgentConcurrencyController();

        // Agent that blocks so queue items remain pending
        Agent agent = new Agent() {
            @Override
            public Mono<Msg> call(Msg input) {
                try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return Mono.just(Msg.of(MsgRole.ASSISTANT, "late"));
            }
            @Override public String id() { return "stub-id"; }
            @Override public String name() { return "stub-agent"; }
            @Override public AgentState state() { return AgentState.IDLE; }
            @Override public void interrupt() {}
        };

        AgentSessionPayload payload = createPayload(agent, runningState, phaseRef, sink, concurrency);

        // Enqueue multiple refinement messages
        payload.handleMessage(MessageRequest.text("refine-1"));
        payload.handleMessage(MessageRequest.text("refine-2"));
        payload.handleMessage(MessageRequest.text("refine-3"));

        // Small delay to let first enqueue take the lock
        try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // Call stop — should clear the queue
        payload.stop();

        assertThat(runningState.get()).isFalse();
    }

    @Test
    void stop_whenNotRunning_isNoOp() {
        AtomicBoolean runningState = new AtomicBoolean(false);
        Sinks.Many<AgentEvent> sink = Sinks.many().replay().all();
        AtomicReference<SessionPhase> phaseRef = new AtomicReference<>(SessionPhase.IDLE);
        AgentConcurrencyController concurrency = new AgentConcurrencyController();

        Agent agent = new Agent() {
            @Override public Mono<Msg> call(Msg input) { return Mono.just(Msg.of(MsgRole.ASSISTANT, "ok")); }
            @Override public String id() { return "stub-id"; }
            @Override public String name() { return "stub-agent"; }
            @Override public AgentState state() { return AgentState.IDLE; }
            @Override public void interrupt() {}
        };

        AgentSessionPayload payload = createPayload(agent, runningState, phaseRef, sink, concurrency);

        // Should not throw
        payload.stop();

        assertThat(runningState.get()).isFalse();
        assertThat(payload.isRunning()).isFalse();
    }

    // ── Helpers ──

    private AgentSessionPayload createPayload(Agent agent, AtomicBoolean runningState,
                                               AtomicReference<SessionPhase> phaseRef,
                                               Sinks.Many<AgentEvent> sink,
                                               AgentConcurrencyController concurrency) {
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
}
