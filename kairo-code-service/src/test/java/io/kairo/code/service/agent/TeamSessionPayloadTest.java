package io.kairo.code.service.agent;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentState;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.api.team.MessageBus;
import io.kairo.api.team.TeamManager;
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
 * M-Team / #60: tests for {@link TeamSessionPayload} — the live multi-agent payload.
 *
 * <p>Covers the two seams that distinguish this class from {@link AgentSessionPayload}:
 * <ol>
 *   <li>The peer-message poller drains {@code MessageBus} on a 500ms interval and projects
 *       {@code TeamMessage} → {@code PEER_MESSAGE} events onto the shared sink.</li>
 *   <li>{@link TeamSessionPayload#stop()} disposes the poller so a stopped session no longer
 *       leaks bus messages into a dead sink.</li>
 * </ol>
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class TeamSessionPayloadTest {

    private static final String SESSION_ID = "team-session-1";

    @Test
    void peerMessage_pollerEmitsPeerMessageEvent() {
        Sinks.Many<AgentEvent> sink = Sinks.many().replay().all();
        AgentRuntimeContext ctx = newCtx(sink);
        TeamManager teamManager = io.kairo.code.service.testutil.StubTeamPrimitives.teamManager();
        MessageBus messageBus = new io.kairo.code.service.testutil.InMemoryMessageBus();

        TeamSessionPayload payload = newPayload(ctx, teamManager, messageBus);

        try {
            // Subscribe before seeding so we capture the emission.
            List<AgentEvent> events = new ArrayList<>();
            sink.asFlux().subscribe(events::add);

            // Seed a peer message addressed to this session (from peer-session-2 → SESSION_ID).
            Msg peerMsg = Msg.builder()
                    .role(MsgRole.USER)
                    .addContent(new io.kairo.api.message.Content.TextContent("hello from peer"))
                    .metadata("from", "peer-session-2")
                    .build();
            messageBus.send("peer-session-2", SESSION_ID, peerMsg).block();

            // Wait for the poller to fire (max 2s, well past the 500ms interval).
            await(() -> events.stream()
                    .anyMatch(e -> e.type() == AgentEvent.EventType.PEER_MESSAGE),
                    Duration.ofSeconds(2));

            AgentEvent peer = events.stream()
                    .filter(e -> e.type() == AgentEvent.EventType.PEER_MESSAGE)
                    .findFirst()
                    .orElseThrow();
            assertThat(peer.sessionId()).isEqualTo(SESSION_ID);
            assertThat(peer.content()).isEqualTo("hello from peer");
            assertThat(peer.resultMetadata())
                    .containsEntry("fromSessionId", "peer-session-2");
        } finally {
            payload.stop();
        }
    }

    @Test
    void stop_disposesPoller() throws InterruptedException {
        Sinks.Many<AgentEvent> sink = Sinks.many().replay().all();
        AgentRuntimeContext ctx = newCtx(sink);
        TeamManager teamManager = io.kairo.code.service.testutil.StubTeamPrimitives.teamManager();
        MessageBus messageBus = io.kairo.code.service.testutil.StubTeamPrimitives.messageBus();

        TeamSessionPayload payload = newPayload(ctx, teamManager, messageBus);

        List<AgentEvent> events = new ArrayList<>();
        sink.asFlux().subscribe(events::add);

        // Stop immediately, then seed; a disposed poller must not project anything.
        payload.stop();
        Thread.sleep(100); // let dispose propagate
        messageBus.send(SESSION_ID, "peer-X",
                io.kairo.api.message.Msg.of(io.kairo.api.message.MsgRole.USER, "post-stop message")).block();

        // Wait two full poll intervals + buffer to give a non-disposed poller a chance to fire.
        Thread.sleep(1_200);

        assertThat(events.stream()
                .anyMatch(e -> e.type() == AgentEvent.EventType.PEER_MESSAGE))
                .as("Stopped payload must not emit PEER_MESSAGE")
                .isFalse();
    }

    @Test
    void handleMessage_passesThroughToAgent() {
        Sinks.Many<AgentEvent> sink = Sinks.many().replay().all();
        AgentRuntimeContext ctx = newCtx(sink);
        TeamManager teamManager = io.kairo.code.service.testutil.StubTeamPrimitives.teamManager();
        MessageBus messageBus = io.kairo.code.service.testutil.StubTeamPrimitives.messageBus();

        List<String> agentCalls = new ArrayList<>();
        Agent agent = new Agent() {
            @Override public Mono<Msg> call(Msg input) {
                agentCalls.add(input.text());
                return Mono.just(Msg.of(MsgRole.ASSISTANT, "ok"));
            }
            @Override public String id() { return "team-agent-id"; }
            @Override public String name() { return "team-agent"; }
            @Override public AgentState state() { return AgentState.IDLE; }
            @Override public void interrupt() {}
        };
        TeamSessionPayload payload = newPayload(ctx, teamManager, messageBus, agent);

        try {
            payload.handleMessage(MessageRequest.text("plan the team rollout"));
            await(() -> !ctx.runningState().get() && !agentCalls.isEmpty(),
                    Duration.ofSeconds(3));

            assertThat(agentCalls).hasSize(1);
            assertThat(agentCalls.get(0)).isEqualTo("plan the team rollout");
            assertThat(ctx.phaseRef().get()).isEqualTo(SessionPhase.IDLE);
        } finally {
            payload.stop();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static AgentRuntimeContext newCtx(Sinks.Many<AgentEvent> sink) {
        return new AgentRuntimeContext(
                SESSION_ID, sink,
                new AtomicBoolean(false),
                new AtomicReference<>(SessionPhase.IDLE),
                phase -> {},
                new AgentConcurrencyController());
    }

    private static TeamSessionPayload newPayload(AgentRuntimeContext ctx,
                                                 TeamManager teamManager,
                                                 MessageBus messageBus) {
        return newPayload(ctx, teamManager, messageBus, stubAgent());
    }

    private static TeamSessionPayload newPayload(AgentRuntimeContext ctx,
                                                 TeamManager teamManager,
                                                 MessageBus messageBus,
                                                 Agent agent) {
        CodeAgentConfig config = new CodeAgentConfig(
                "test-key", "https://api.openai.com", "gpt-4o", 50, null, null, 0, 0, null);
        DefaultToolRegistry registry = new DefaultToolRegistry();
        DefaultToolExecutor executor = new DefaultToolExecutor(registry, new DefaultPermissionGuard());
        CodeAgentSession session = new CodeAgentSession(agent, executor, registry, Set.of());
        return new TeamSessionPayload(config, session, ctx, teamManager, messageBus);
    }

    private static Agent stubAgent() {
        return new Agent() {
            @Override public Mono<Msg> call(Msg input) {
                return Mono.just(Msg.of(MsgRole.ASSISTANT, "stub-response"));
            }
            @Override public String id() { return "stub-id"; }
            @Override public String name() { return "stub"; }
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
            try { Thread.sleep(20); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted", e);
            }
        }
    }
}
