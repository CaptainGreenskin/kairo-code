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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link AgentSessionPayload#rebuildAgent(Agent)} guard logic:
 * - Throws IllegalStateException when session is running
 * - Succeeds and updates agent reference when not running
 */
@Timeout(value = 5, unit = TimeUnit.SECONDS)
class AgentSessionPayloadRebuildGuardTest {

    private static final String SESSION_ID = "rebuild-session";

    @Test
    void rebuildAgent_whileRunning_throwsIllegalState() {
        AtomicBoolean runningState = new AtomicBoolean(true); // running = true
        AgentSessionPayload payload = createPayload(runningState);

        Agent freshAgent = stubAgent("fresh-agent");

        assertThatThrownBy(() -> payload.rebuildAgent(freshAgent))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot rebuild agent while session is running");
    }

    @Test
    void rebuildAgent_whenIdle_succeeds_updatesReference() {
        AtomicBoolean runningState = new AtomicBoolean(false); // not running
        AgentSessionPayload payload = createPayload(runningState);

        Agent freshAgent = stubAgent("new-agent");
        AgentSessionPayload.AgentSnapshot snapshot = payload.rebuildAgent(freshAgent);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.agentName()).isEqualTo("new-agent");
        assertThat(snapshot.model()).isEqualTo("gpt-4o");
    }

    @Test
    void rebuildAgent_transitionFromRunningToIdle_thenSucceeds() {
        AtomicBoolean runningState = new AtomicBoolean(true);
        AgentSessionPayload payload = createPayload(runningState);

        // First attempt fails
        Agent fresh1 = stubAgent("attempt-1");
        assertThatThrownBy(() -> payload.rebuildAgent(fresh1))
                .isInstanceOf(IllegalStateException.class);

        // Simulate running state cleared (agent completed)
        runningState.set(false);

        // Second attempt succeeds
        Agent fresh2 = stubAgent("attempt-2");
        AgentSessionPayload.AgentSnapshot snapshot = payload.rebuildAgent(fresh2);
        assertThat(snapshot.agentName()).isEqualTo("attempt-2");
    }

    // ── Helpers ──

    private AgentSessionPayload createPayload(AtomicBoolean runningState) {
        Sinks.Many<AgentEvent> sink = Sinks.many().multicast().onBackpressureBuffer();
        AtomicReference<SessionPhase> phaseRef = new AtomicReference<>(SessionPhase.IDLE);
        AgentConcurrencyController concurrency = new AgentConcurrencyController();

        Agent agent = stubAgent("original-agent");
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

    private static Agent stubAgent(String name) {
        return new Agent() {
            @Override public Mono<Msg> call(Msg input) { return Mono.just(Msg.of(MsgRole.ASSISTANT, "ok")); }
            @Override public String id() { return "stub-" + name; }
            @Override public String name() { return name; }
            @Override public AgentState state() { return AgentState.IDLE; }
            @Override public void interrupt() {}
        };
    }
}
