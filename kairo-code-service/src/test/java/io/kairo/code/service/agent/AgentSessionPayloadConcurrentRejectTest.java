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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that a second concurrent handleMessage call returns SESSION_BUSY immediately
 * when runningState is already true.
 */
@Timeout(value = 5, unit = TimeUnit.SECONDS)
class AgentSessionPayloadConcurrentRejectTest {

    private static final String SESSION_ID = "busy-session";

    @Test
    void runningState_true_returnsBusyError() {
        AtomicBoolean runningState = new AtomicBoolean(true); // pre-set to true
        Sinks.Many<AgentEvent> sink = Sinks.many().replay().all();
        AtomicReference<SessionPhase> phaseRef = new AtomicReference<>(SessionPhase.IDLE);
        AgentConcurrencyController concurrency = new AgentConcurrencyController();

        Agent agent = new Agent() {
            @Override public Mono<Msg> call(Msg input) { return Mono.just(Msg.of(MsgRole.ASSISTANT, "never")); }
            @Override public String id() { return "stub-id"; }
            @Override public String name() { return "stub-agent"; }
            @Override public AgentState state() { return AgentState.IDLE; }
            @Override public void interrupt() {}
        };

        CodeAgentConfig config = new CodeAgentConfig(
                "test-key", "https://api.openai.com", "gpt-4o", 50, null, null, 0, 0, null);
        DefaultToolRegistry registry = new DefaultToolRegistry();
        DefaultToolExecutor executor = new DefaultToolExecutor(registry, new DefaultPermissionGuard());
        CodeAgentSession session = new CodeAgentSession(agent, executor, registry, Set.of());
        AgentRuntimeContext ctx = new AgentRuntimeContext(
                SESSION_ID, sink, runningState, phaseRef,
                phase -> {}, concurrency);
        AgentSessionPayload payload = new AgentSessionPayload(config, session, ctx);

        // Call handleMessage while running
        Flux<AgentEvent> result = payload.handleMessage(MessageRequest.text("try me"));

        // Should return a single error event immediately
        List<AgentEvent> events = result.take(1).collectList().block();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo(AgentEvent.EventType.AGENT_ERROR);
        assertThat(events.get(0).errorType()).isEqualTo("SESSION_BUSY");
        assertThat(events.get(0).errorMessage()).contains("already running");

        // Agent was never called — still running
        assertThat(runningState.get()).isTrue();
    }

    @Test
    void failedExecution_phase_returnsRevertRequired() {
        AtomicBoolean runningState = new AtomicBoolean(false);
        Sinks.Many<AgentEvent> sink = Sinks.many().replay().all();
        AtomicReference<SessionPhase> phaseRef = new AtomicReference<>(SessionPhase.FAILED_EXECUTION);
        AgentConcurrencyController concurrency = new AgentConcurrencyController();

        Agent agent = new Agent() {
            @Override public Mono<Msg> call(Msg input) { return Mono.just(Msg.of(MsgRole.ASSISTANT, "never")); }
            @Override public String id() { return "stub-id"; }
            @Override public String name() { return "stub-agent"; }
            @Override public AgentState state() { return AgentState.IDLE; }
            @Override public void interrupt() {}
        };

        CodeAgentConfig config = new CodeAgentConfig(
                "test-key", "https://api.openai.com", "gpt-4o", 50, null, null, 0, 0, null);
        DefaultToolRegistry registry = new DefaultToolRegistry();
        DefaultToolExecutor executor = new DefaultToolExecutor(registry, new DefaultPermissionGuard());
        CodeAgentSession session = new CodeAgentSession(agent, executor, registry, Set.of());
        AgentRuntimeContext ctx = new AgentRuntimeContext(
                SESSION_ID, sink, runningState, phaseRef,
                phase -> {}, concurrency);
        AgentSessionPayload payload = new AgentSessionPayload(config, session, ctx);

        Flux<AgentEvent> result = payload.handleMessage(MessageRequest.text("retry"));
        List<AgentEvent> events = result.take(1).collectList().block();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo(AgentEvent.EventType.AGENT_ERROR);
        assertThat(events.get(0).errorType()).isEqualTo("REVERT_REQUIRED");
    }
}
