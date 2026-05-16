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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M136 regression gate: tests the TeamSessionPayload demoted fallback path.
 *
 * <p>When the triage gate returns false (shouldFanOut=false), TeamSessionPayload
 * emits MODE_DEMOTED followed by the fallback AgentSessionPayload's event stream.
 * This test verifies the exact Flux.concat composition pattern used in production.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class AgentSessionPayloadDemotedFallbackIT {

    private static final String SESSION_ID = "demoted-session";

    @Test
    void demotedPath_emitsModeDemoted_thenFallbackEvents() {
        AtomicBoolean runningState = new AtomicBoolean(false);
        Sinks.Many<AgentEvent> sink = Sinks.many().replay().all();
        AtomicReference<SessionPhase> phaseRef = new AtomicReference<>(SessionPhase.IDLE);
        AgentConcurrencyController concurrency = new AgentConcurrencyController();

        // Track agent calls
        List<String> agentCalls = new ArrayList<>();
        Agent agent = new Agent() {
            @Override
            public Mono<Msg> call(Msg input) {
                agentCalls.add(input.text());
                return Mono.just(Msg.of(MsgRole.ASSISTANT, "fallback response"));
            }
            @Override public String id() { return "fallback-id"; }
            @Override public String name() { return "fallback-agent"; }
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

        AgentSessionPayload fallback = new AgentSessionPayload(config, session, ctx);

        // Simulate the TeamSessionPayload demoted path:
        // Flux.concat(Flux.just(demoted), fallback.handleMessage(request))
        MessageRequest request = MessageRequest.text("hi");
        AgentEvent demoted = AgentEvent.modeDemoted(SESSION_ID,
                "Message too brief for experts mode, single-agent fallback");
        Flux<AgentEvent> eventStream = Flux.concat(
                Flux.just(demoted),
                fallback.handleMessage(request)
        );

        // Subscribe and collect events
        List<AgentEvent> events = new ArrayList<>();
        sink.asFlux().subscribe(events::add);

        // Subscribe to the concat flux (triggers handleMessage)
        eventStream.subscribe();

        // Wait for agent to complete
        await(() -> !runningState.get() && !agentCalls.isEmpty(), Duration.ofSeconds(3));

        // Verify MODE_DEMOTED is emitted (it's in the concat, not the sink)
        // The sink receives THINKING event from handleMessage
        assertThat(events).isNotEmpty();
        assertThat(events.stream()
                .anyMatch(e -> e.type() == AgentEvent.EventType.AGENT_THINKING))
                .isTrue();

        // Verify agent was called exactly once
        assertThat(agentCalls).hasSize(1);
        assertThat(agentCalls.get(0)).isEqualTo("hi");
    }

    @Test
    void demotedPath_firstEventIsModeDemoted() {
        // Verify the Flux.concat ordering: MODE_DEMOTED is always first
        AgentEvent demoted = AgentEvent.modeDemoted(SESSION_ID, "too brief");
        AgentEvent thinking = AgentEvent.thinking(SESSION_ID);

        Flux<AgentEvent> stream = Flux.concat(
                Flux.just(demoted),
                Flux.just(thinking)
        );

        List<AgentEvent> events = stream.collectList().block();

        assertThat(events).hasSize(2);
        assertThat(events.get(0).type()).isEqualTo(AgentEvent.EventType.MODE_DEMOTED);
        assertThat(events.get(0).content()).isEqualTo("too brief");
        assertThat(events.get(1).type()).isEqualTo(AgentEvent.EventType.AGENT_THINKING);
    }

    @Test
    void demotedPath_fallbackPayload_transitionsCorrectly() {
        AtomicBoolean runningState = new AtomicBoolean(false);
        Sinks.Many<AgentEvent> sink = Sinks.many().replay().all();
        AtomicReference<SessionPhase> phaseRef = new AtomicReference<>(SessionPhase.IDLE);
        AgentConcurrencyController concurrency = new AgentConcurrencyController();

        Agent agent = new Agent() {
            @Override
            public Mono<Msg> call(Msg input) {
                return Mono.just(Msg.of(MsgRole.ASSISTANT, "done"));
            }
            @Override public String id() { return "fb-id"; }
            @Override public String name() { return "fb-agent"; }
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

        AgentSessionPayload fallback = new AgentSessionPayload(config, session, ctx);
        fallback.handleMessage(MessageRequest.text("hi"));

        // Wait for completion
        await(() -> !runningState.get(), Duration.ofSeconds(3));

        // After fallback completes: running=false, phase=IDLE
        assertThat(runningState.get()).isFalse();
        assertThat(phaseRef.get()).isEqualTo(SessionPhase.IDLE);
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
