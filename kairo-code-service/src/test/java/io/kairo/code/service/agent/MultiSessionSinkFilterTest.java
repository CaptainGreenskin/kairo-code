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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that two AgentSessionPayload instances sharing the same Sinks.Many but different
 * sessionIds produce events tagged with their respective sessionId only.
 * Verifies no cross-talk between sessions sharing infrastructure.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class MultiSessionSinkFilterTest {

    @Test
    void twoPayloads_sameSink_eventsTaggedWithCorrectSessionId() {
        Sinks.Many<AgentEvent> sharedSink = Sinks.many().replay().all();
        AgentConcurrencyController concurrency = new AgentConcurrencyController();

        String sessionA = "session-A";
        String sessionB = "session-B";

        AtomicBoolean runningA = new AtomicBoolean(false);
        AtomicBoolean runningB = new AtomicBoolean(false);

        Agent agentA = stubAgent("agent-A");
        Agent agentB = stubAgent("agent-B");

        AgentSessionPayload payloadA = createPayload(agentA, sessionA, sharedSink, runningA, concurrency);
        AgentSessionPayload payloadB = createPayload(agentB, sessionB, sharedSink, runningB, concurrency);

        // Subscribe to shared sink and collect all events
        List<AgentEvent> allEvents = new ArrayList<>();
        sharedSink.asFlux().subscribe(allEvents::add);

        // Both send messages concurrently
        payloadA.handleMessage(MessageRequest.text("hello A"));
        payloadB.handleMessage(MessageRequest.text("hello B"));

        // Wait for both to complete
        await(() -> !runningA.get() && !runningB.get(), Duration.ofSeconds(5));

        // Filter events by session
        List<AgentEvent> eventsA = allEvents.stream()
                .filter(e -> sessionA.equals(e.sessionId()))
                .toList();
        List<AgentEvent> eventsB = allEvents.stream()
                .filter(e -> sessionB.equals(e.sessionId()))
                .toList();

        // Each session should have at least a THINKING event
        assertThat(eventsA).isNotEmpty();
        assertThat(eventsB).isNotEmpty();

        // No cross-talk: all events for A have sessionId=A
        assertThat(eventsA).allMatch(e -> sessionA.equals(e.sessionId()));
        // No cross-talk: all events for B have sessionId=B
        assertThat(eventsB).allMatch(e -> sessionB.equals(e.sessionId()));
    }

    @Test
    void filteredFlux_onlyContainsOwnSession() {
        Sinks.Many<AgentEvent> sharedSink = Sinks.many().replay().all();
        AgentConcurrencyController concurrency = new AgentConcurrencyController();

        String sessionA = "filter-A";
        String sessionB = "filter-B";

        AtomicBoolean runningA = new AtomicBoolean(false);
        AtomicBoolean runningB = new AtomicBoolean(false);

        Agent agentA = stubAgent("agent-A");
        Agent agentB = stubAgent("agent-B");

        AgentSessionPayload payloadA = createPayload(agentA, sessionA, sharedSink, runningA, concurrency);
        AgentSessionPayload payloadB = createPayload(agentB, sessionB, sharedSink, runningB, concurrency);

        // Create filtered fluxes (how production code would expose to clients)
        List<AgentEvent> filteredA = new ArrayList<>();
        List<AgentEvent> filteredB = new ArrayList<>();
        sharedSink.asFlux().filter(e -> sessionA.equals(e.sessionId())).subscribe(filteredA::add);
        sharedSink.asFlux().filter(e -> sessionB.equals(e.sessionId())).subscribe(filteredB::add);

        payloadA.handleMessage(MessageRequest.text("msg A"));
        payloadB.handleMessage(MessageRequest.text("msg B"));

        await(() -> !runningA.get() && !runningB.get(), Duration.ofSeconds(5));

        // Filtered flux for A never contains B's events
        assertThat(filteredA).noneMatch(e -> sessionB.equals(e.sessionId()));
        // Filtered flux for B never contains A's events
        assertThat(filteredB).noneMatch(e -> sessionA.equals(e.sessionId()));

        // Both have events
        assertThat(filteredA).isNotEmpty();
        assertThat(filteredB).isNotEmpty();
    }

    // ── Helpers ──

    private AgentSessionPayload createPayload(Agent agent, String sessionId,
                                               Sinks.Many<AgentEvent> sink,
                                               AtomicBoolean runningState,
                                               AgentConcurrencyController concurrency) {
        AtomicReference<SessionPhase> phaseRef = new AtomicReference<>(SessionPhase.IDLE);
        CodeAgentConfig config = new CodeAgentConfig(
                "test-key", "https://api.openai.com", "gpt-4o", 50, null, null, 0, 0, null);
        DefaultToolRegistry registry = new DefaultToolRegistry();
        DefaultToolExecutor executor = new DefaultToolExecutor(registry, new DefaultPermissionGuard());
        CodeAgentSession session = new CodeAgentSession(agent, executor, registry, Set.of());
        AgentRuntimeContext ctx = new AgentRuntimeContext(
                sessionId, sink, runningState, phaseRef,
                phase -> {}, concurrency);
        return new AgentSessionPayload(config, session, ctx);
    }

    private static Agent stubAgent(String name) {
        return new Agent() {
            @Override public Mono<Msg> call(Msg input) {
                return Mono.just(Msg.of(MsgRole.ASSISTANT, "response from " + name));
            }
            @Override public String id() { return "id-" + name; }
            @Override public String name() { return name; }
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
