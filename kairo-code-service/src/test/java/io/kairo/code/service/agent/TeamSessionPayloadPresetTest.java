package io.kairo.code.service.agent;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentState;
import io.kairo.api.event.KairoEventBus;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.team.TeamConfig;
import io.kairo.api.team.TeamEvent;
import io.kairo.api.team.TeamEventType;
import io.kairo.api.team.TeamResult;
import io.kairo.api.team.TeamStatus;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.api.team.MessageBus;
import io.kairo.code.core.team.SwarmCoordinator;
import io.kairo.api.team.TeamManager;
import io.kairo.code.service.AgentEvent;
import io.kairo.code.service.SessionPhase;
import io.kairo.code.service.concurrency.AgentConcurrencyController;
import io.kairo.code.service.team.TriageGate;
import io.kairo.core.event.DefaultKairoEventBus;
import io.kairo.core.tool.DefaultPermissionGuard;
import io.kairo.core.tool.DefaultToolExecutor;
import io.kairo.core.tool.DefaultToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M-Experts-Upgrade / #61: tests for {@link TeamSessionPayload}'s experts-preset surface and the
 * load-bearing mode-isolation contract.
 *
 * <p>The contract: every preset-only behavior is gated behind {@code preset != null}. Default Team
 * mode (5-arg constructor) MUST keep its M-Team semantics intact — that property is verified by
 * {@code defaultMode_unchanged} (the regression gate) plus the unmodified {@link
 * TeamSessionPayloadTest} cases.
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class TeamSessionPayloadPresetTest {

    private static final String SESSION_ID = "experts-session-1";

    // ── #1 demotion path ────────────────────────────────────────────────────────

    @Test
    void presetMode_triageDemotesShortMessage() {
        TestFixture f = TestFixture.create();
        TriageGate triage = goal -> false;
        FallbackHarness fallback = FallbackHarness.create();
        RecordingSwarmCoordinator coord = RecordingSwarmCoordinator.create();

        TeamSessionPayload payload = newPresetPayload(f, coord, triage, fallback.payload, true);
        try {
            List<AgentEvent> events = new CopyOnWriteArrayList<>();
            payload.handleMessage(MessageRequest.text("hi")).subscribe(events::add);

            // Wait for the fallback's underlying agent to receive the demoted message —
            // this is what proves preset.demotionFallback().handleMessage(...) was invoked.
            await(() -> !fallback.agent.calls.isEmpty(), Duration.ofSeconds(3));

            assertThat(events).anyMatch(e -> e.type() == AgentEvent.EventType.MODE_DEMOTED);
            assertThat(fallback.agent.calls).hasSize(1);
            assertThat(fallback.agent.calls.get(0)).isEqualTo("hi");
            assertThat(coord.planCalls.get()).isZero();
        } finally {
            payload.stop();
        }
    }

    // ── #2 plan-only success ────────────────────────────────────────────────────

    @Test
    void presetMode_planOnlyEmitsPlanReady() {
        TestFixture f = TestFixture.create();
        TriageGate triage = goal -> true;
        FallbackHarness fallback = FallbackHarness.create();
        RecordingSwarmCoordinator coord = RecordingSwarmCoordinator.create();
        // The plan's overview text is carried back via TeamResult.finalOutput().
        coord.planResult = Mono.just(TeamResult.of(
                "req-1", TeamStatus.COMPLETED, List.of(), "plan-overview",
                Duration.ofMillis(10), List.of()));
        coord.lastTeamIdToReturn = "team-42";

        TeamSessionPayload payload = newPresetPayload(f, coord, triage, fallback.payload, true);
        try {
            List<AgentEvent> events = new CopyOnWriteArrayList<>();
            payload.handleMessage(MessageRequest.text(
                    "Refactor the entire auth subsystem for testability and add coverage."))
                    .subscribe(events::add);

            await(() -> events.stream()
                            .anyMatch(e -> e.type() == AgentEvent.EventType.PLAN_READY),
                    Duration.ofSeconds(3));

            assertThat(coord.planCalls.get()).isEqualTo(1);
            assertThat(coord.planOnlyFlag.get()).isTrue();
            assertThat(f.phaseRef.get()).isEqualTo(SessionPhase.PLAN_PENDING);
            assertThat(payload.pendingTeamId()).isEqualTo("team-42");
            assertThat(events).anyMatch(e -> e.type() == AgentEvent.EventType.PLAN_READY
                    && "plan-overview".equals(e.content()));
        } finally {
            payload.stop();
        }
    }

    // ── #3 confirmBuild happy path ──────────────────────────────────────────────

    @Test
    void presetMode_confirmAndExecute_emitsDoneOnSuccess() {
        TestFixture f = TestFixture.create();
        f.phaseRef.set(SessionPhase.PLAN_PENDING);

        RecordingSwarmCoordinator coord = RecordingSwarmCoordinator.create();
        coord.confirmResult = Mono.just(TeamResult.withoutOutput(
                "req-99", TeamStatus.COMPLETED, List.of(),
                Duration.ofMillis(5), List.of()));

        TeamSessionPayload payload = newPresetPayload(
                f, coord, anyTriage(true), FallbackHarness.create().payload, true);
        injectPendingTeamId(payload, "team-99");

        try {
            List<AgentEvent> events = new CopyOnWriteArrayList<>();
            payload.confirmBuild().subscribe(events::add);

            await(() -> events.stream()
                            .anyMatch(e -> e.type() == AgentEvent.EventType.AGENT_DONE),
                    Duration.ofSeconds(3));

            assertThat(f.phaseRef.get()).isEqualTo(SessionPhase.COMPLETED);
            assertThat(coord.confirmCalls.get()).isEqualTo(1);
            assertThat(coord.confirmTeamId).isEqualTo("team-99");
        } finally {
            payload.stop();
        }
    }

    // ── #4 swarm-event bridge projects PEER_MESSAGE ─────────────────────────────

    @Test
    void presetMode_swarmEventsProjectAsPeerMessage() {
        TestFixture f = TestFixture.create();
        RecordingSwarmCoordinator coord = RecordingSwarmCoordinator.create();
        TeamSessionPayload payload = newPresetPayload(
                f, coord, anyTriage(true), FallbackHarness.create().payload, false);
        injectPendingTeamId(payload, "team-77");

        try {
            List<AgentEvent> events = new CopyOnWriteArrayList<>();
            f.sink.asFlux().subscribe(events::add);

            // STEP_COMPLETED is a lifecycle event — must surface as PEER_MESSAGE.
            TeamEvent te = new TeamEvent(
                    TeamEventType.STEP_COMPLETED,
                    "team-77",
                    "req-1",
                    Instant.now(),
                    Map.of("role", "researcher", "summary", "found three callers of auth/login"));
            f.bus.publish(te.toKairoEvent());

            await(() -> events.stream()
                            .anyMatch(e -> e.type() == AgentEvent.EventType.PEER_MESSAGE),
                    Duration.ofSeconds(2));

            AgentEvent peer = events.stream()
                    .filter(e -> e.type() == AgentEvent.EventType.PEER_MESSAGE)
                    .findFirst()
                    .orElseThrow();
            assertThat(peer.content()).isEqualTo("found three callers of auth/login");
            assertThat(peer.resultMetadata())
                    .containsEntry("fromSessionId", "expert:researcher");

            // STEP_THINKING is in HIGH_FREQ filter — must be dropped.
            int peerCountBefore = (int) events.stream()
                    .filter(e -> e.type() == AgentEvent.EventType.PEER_MESSAGE).count();
            TeamEvent thinking = new TeamEvent(
                    TeamEventType.STEP_THINKING,
                    "team-77",
                    "req-1",
                    Instant.now(),
                    Map.of("role", "coder", "summary", "low-value chatter"));
            f.bus.publish(thinking.toKairoEvent());
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            int peerCountAfter = (int) events.stream()
                    .filter(e -> e.type() == AgentEvent.EventType.PEER_MESSAGE).count();
            assertThat(peerCountAfter).isEqualTo(peerCountBefore);
        } finally {
            payload.stop();
        }
    }

    // ── #5 stop() disposes peer-poller + swarm-bridge + narrator ─────────────────

    @Test
    void presetMode_stopDisposesAll() throws InterruptedException {
        TestFixture f = TestFixture.create();
        RecordingSwarmCoordinator coord = RecordingSwarmCoordinator.create();
        TeamSessionPayload payload = newPresetPayload(
                f, coord, anyTriage(true), FallbackHarness.create().payload, true);
        injectPendingTeamId(payload, "team-stop");

        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        f.sink.asFlux().subscribe(events::add);

        payload.stop();
        Thread.sleep(150);

        // Post-stop: neither the peer-poller nor the bridge should project anything.
        f.messageBus.send(SESSION_ID, "peer-X",
                io.kairo.api.message.Msg.of(io.kairo.api.message.MsgRole.USER, "post-stop"));
        TeamEvent te = new TeamEvent(
                TeamEventType.STEP_COMPLETED,
                "team-stop",
                "req-1",
                Instant.now(),
                Map.of("role", "researcher", "summary", "post-stop event"));
        f.bus.publish(te.toKairoEvent());

        Thread.sleep(800);

        assertThat(events.stream()
                .anyMatch(e -> e.type() == AgentEvent.EventType.PEER_MESSAGE))
                .as("Stopped preset must not emit PEER_MESSAGE from bus or bridge")
                .isFalse();
    }

    // ── #5b stop() cancels in-flight planning ───────────────────────────────────

    /**
     * Regression for the deferred ExpertTeamPanel.stop() leak called out in the M-Experts-Upgrade
     * plan: previously {@code startPlanOnly} subscribed without capturing the resulting
     * {@code Disposable}, so calling {@code stop()} mid-planning could not cancel the
     * SwarmCoordinator Mono. Fix wires the subscribe() return into {@code currentRun}; this test
     * proves it by using a never-completing planning Mono — the only way phase becomes
     * {@code FAILED_PLANNING} is via the {@code doOnCancel} side-effect that fires when
     * {@code stop()} disposes the captured Disposable.
     */
    @Test
    void presetMode_stopCancelsInFlightPlanning() throws InterruptedException {
        TestFixture f = TestFixture.create();
        RecordingSwarmCoordinator coord = RecordingSwarmCoordinator.create();
        coord.planResult = Mono.never();

        TeamSessionPayload payload = newPresetPayload(
                f, coord, anyTriage(true), FallbackHarness.create().payload, false);
        try {
            payload.handleMessage(MessageRequest.text(
                    "Refactor the entire auth subsystem for testability and add coverage."))
                    .subscribe();

            await(() -> f.runningState.get(), Duration.ofSeconds(2));
            assertThat(f.phaseRef.get()).isEqualTo(SessionPhase.PLANNING);

            payload.stop();
            Thread.sleep(150);

            assertThat(f.runningState.get())
                    .as("stop() must cancel in-flight planning Mono")
                    .isFalse();
            assertThat(f.phaseRef.get()).isEqualTo(SessionPhase.FAILED_PLANNING);
        } finally {
            payload.stop();
        }
    }

    // ── #5c stop() cancels in-flight execution ──────────────────────────────────

    /**
     * Companion to {@link #presetMode_stopCancelsInFlightPlanning}: same leak applied to
     * {@code confirmBuild}'s execution path. A never-completing execution Mono must transition
     * to {@code FAILED_EXECUTION} + runningState=false after {@code stop()} — proof that
     * {@code doOnCancel} fired on the captured Disposable.
     */
    @Test
    void presetMode_stopCancelsInFlightExecution() throws InterruptedException {
        TestFixture f = TestFixture.create();
        f.phaseRef.set(SessionPhase.PLAN_PENDING);

        RecordingSwarmCoordinator coord = RecordingSwarmCoordinator.create();
        coord.confirmResult = Mono.never();

        TeamSessionPayload payload = newPresetPayload(
                f, coord, anyTriage(true), FallbackHarness.create().payload, false);
        injectPendingTeamId(payload, "team-cancel");

        try {
            payload.confirmBuild().subscribe();

            await(() -> f.runningState.get(), Duration.ofSeconds(2));
            assertThat(f.phaseRef.get()).isEqualTo(SessionPhase.EXECUTING);

            payload.stop();
            Thread.sleep(150);

            assertThat(f.runningState.get())
                    .as("stop() must cancel in-flight execution Mono")
                    .isFalse();
            assertThat(f.phaseRef.get()).isEqualTo(SessionPhase.FAILED_EXECUTION);
        } finally {
            payload.stop();
        }
    }

    // ── #6 REGRESSION GATE: default Team mode unchanged ─────────────────────────

    @Test
    void defaultMode_unchanged() {
        TestFixture f = TestFixture.create();
        Agent stub = stubAgent();
        TeamManager teamManager = io.kairo.code.service.testutil.StubTeamPrimitives.teamManager();
        MessageBus messageBus = io.kairo.code.service.testutil.StubTeamPrimitives.messageBus();
        CodeAgentSession session = newSession(stub);

        // 5-arg constructor — preset == null — same call site Team mode uses.
        TeamSessionPayload payload = new TeamSessionPayload(
                newConfig(), session, f.ctx, teamManager, messageBus);

        try {
            assertThat(payload.preset()).isNull();

            List<AgentEvent> events = new CopyOnWriteArrayList<>();
            payload.handleMessage(MessageRequest.text("plan the rollout"))
                    .subscribe(events::add);
            await(() -> !f.runningState.get(), Duration.ofSeconds(3));

            // No PLAN_READY (experts-only). No MODE_DEMOTED (no triage).
            assertThat(events).noneMatch(e -> e.type() == AgentEvent.EventType.PLAN_READY);
            assertThat(events).noneMatch(e -> e.type() == AgentEvent.EventType.MODE_DEMOTED);
        } finally {
            payload.stop();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Construct a preset-mode payload. {@code withNarrator=false} disables narration so the
     * dispatcher is not built (narrator-specific tests live in {@link NarratorDispatcherTest}).
     */
    private static TeamSessionPayload newPresetPayload(TestFixture f,
                                                       SwarmCoordinator coord,
                                                       TriageGate triage,
                                                       AgentSessionPayload fallback,
                                                       boolean withNarrator) {
        if (coord instanceof RecordingSwarmCoordinator rec) {
            rec.eventBus = f.bus;
        }
        TeamSessionPayload.NarratorSettings narratorSettings = withNarrator
                ? TeamSessionPayload.NarratorSettings.defaults()
                : TeamSessionPayload.NarratorSettings.disabled();
        TeamSessionPayload.ExpertsPresetConfig preset = new TeamSessionPayload.ExpertsPresetConfig(
                coord, TeamConfig.defaults(), triage, fallback, narratorSettings, f.bus);
        CodeAgentSession session = newSession(stubAgent());
        TeamManager teamManager = io.kairo.code.service.testutil.StubTeamPrimitives.teamManager();
        return new TeamSessionPayload(newConfig(), session, f.ctx,
                teamManager, f.messageBus, preset);
    }

    private static TriageGate anyTriage(boolean fanOut) {
        return goal -> fanOut;
    }

    private static CodeAgentConfig newConfig() {
        return new CodeAgentConfig(
                "test-key", "https://api.openai.com", "gpt-4o", 50, null, null, 0, 0, null);
    }

    private static CodeAgentSession newSession(Agent agent) {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        DefaultToolExecutor executor =
                new DefaultToolExecutor(registry, new DefaultPermissionGuard());
        return new CodeAgentSession(agent, executor, registry, Set.of());
    }

    private static Agent stubAgent() {
        return new RecordingAgent();
    }

    /**
     * pendingTeamId is normally populated by {@code startPlanOnly}; for tests that bypass the
     * plan-only flow we reflect it in directly. Keeps the test orthogonal to the plan-only path.
     */
    private static void injectPendingTeamId(TeamSessionPayload payload, String teamId) {
        try {
            var field = TeamSessionPayload.class.getDeclaredField("pendingTeamId");
            field.setAccessible(true);
            field.set(payload, teamId);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to set pendingTeamId via reflection", e);
        }
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

    /** Shared per-test scaffolding. */
    private static final class TestFixture {
        final Sinks.Many<AgentEvent> sink = Sinks.many().replay().all();
        final AtomicBoolean runningState = new AtomicBoolean(false);
        final AtomicReference<SessionPhase> phaseRef = new AtomicReference<>(SessionPhase.IDLE);
        final AgentConcurrencyController concurrency = new AgentConcurrencyController();
        final MessageBus messageBus = io.kairo.code.service.testutil.StubTeamPrimitives.messageBus();
        final KairoEventBus bus = new DefaultKairoEventBus();
        final AgentRuntimeContext ctx;

        TestFixture() {
            this.ctx = new AgentRuntimeContext(
                    SESSION_ID, sink, runningState, phaseRef, p -> {}, concurrency);
        }

        static TestFixture create() {
            return new TestFixture();
        }
    }

    /** Recording SwarmCoordinator — stubs the methods TeamSessionPayload calls. */
    private static final class RecordingSwarmCoordinator extends SwarmCoordinator {

        final AtomicInteger planCalls = new AtomicInteger();
        final AtomicInteger confirmCalls = new AtomicInteger();
        final AtomicBoolean planOnlyFlag = new AtomicBoolean();
        volatile String confirmTeamId;
        volatile Mono<TeamResult> planResult =
                Mono.just(TeamResult.withoutOutput(
                        "stub-req", TeamStatus.COMPLETED, List.of(),
                        Duration.ZERO, List.of()));
        volatile Mono<TeamResult> confirmResult =
                Mono.just(TeamResult.withoutOutput(
                        "stub-req", TeamStatus.COMPLETED, List.of(),
                        Duration.ZERO, List.of()));
        volatile String lastTeamIdToReturn = "stub-team";
        volatile KairoEventBus eventBus;

        private RecordingSwarmCoordinator(io.kairo.multiagent.orchestration.ExpertTeamCoordinator coord,
                                          io.kairo.multiagent.subagent.ExpertRoleRegistry registry,
                                          io.kairo.api.team.MessageBus bus,
                                          List<Agent> agents) {
            super(coord, registry, bus, agents);
        }

        static RecordingSwarmCoordinator create() {
            var registry = new io.kairo.multiagent.subagent.ExpertRoleRegistry();
            var planner = new io.kairo.multiagent.orchestration.internal.DefaultPlanner(registry, null, null);
            var coord = new io.kairo.multiagent.orchestration.ExpertTeamCoordinator(
                    null, new io.kairo.multiagent.orchestration.SimpleEvaluationStrategy(),
                    null, planner, registry);
            return new RecordingSwarmCoordinator(
                    coord, registry, new io.kairo.code.service.testutil.NoopMessageBus(), List.of());
        }

        @Override
        public Mono<TeamResult> startExpertTeam(String goal, TeamConfig cfg,
                                                List<String> roleIds, boolean planOnly) {
            planCalls.incrementAndGet();
            planOnlyFlag.set(planOnly);
            if (planOnly && eventBus != null) {
                TeamEvent planReadyEvent = new TeamEvent(
                        TeamEventType.PLAN_READY, lastTeamIdToReturn, "req-1",
                        java.time.Instant.now(), Map.of());
                eventBus.publish(planReadyEvent.toKairoEvent());
            }
            return planResult;
        }

        @Override
        public Mono<TeamResult> confirmAndExecute(String teamId) {
            confirmCalls.incrementAndGet();
            confirmTeamId = teamId;
            return confirmResult;
        }

        @Override
        public String lastTeamId() {
            return lastTeamIdToReturn;
        }
    }

    /**
     * Recording Agent — captures every {@link Msg} text passed to {@link Agent#call(Msg)}.
     * The fallback assertion path: we can't subclass the final {@link AgentSessionPayload},
     * so we let the real payload run and observe the call landing on the underlying agent.
     */
    private static final class RecordingAgent implements Agent {
        final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public Mono<Msg> call(Msg input) {
            calls.add(input.text());
            return Mono.just(Msg.of(MsgRole.ASSISTANT, "stub-response"));
        }

        @Override public String id() { return "recording-agent"; }
        @Override public String name() { return "recording-agent"; }
        @Override public AgentState state() { return AgentState.IDLE; }
        @Override public void interrupt() {}
    }

    /**
     * Holder pairing a real {@link AgentSessionPayload} with the {@link RecordingAgent} that
     * backs it — so tests can assert what the demoted-message handler actually saw.
     */
    private static final class FallbackHarness {
        final RecordingAgent agent;
        final AgentSessionPayload payload;

        private FallbackHarness(RecordingAgent agent, AgentSessionPayload payload) {
            this.agent = agent;
            this.payload = payload;
        }

        static FallbackHarness create() {
            RecordingAgent agent = new RecordingAgent();
            CodeAgentSession session = newSession(agent);
            AgentRuntimeContext ctx = new AgentRuntimeContext(
                    "fallback-session",
                    Sinks.many().replay().all(),
                    new AtomicBoolean(false),
                    new AtomicReference<>(SessionPhase.IDLE),
                    p -> {},
                    new AgentConcurrencyController());
            return new FallbackHarness(agent, new AgentSessionPayload(newConfig(), session, ctx));
        }
    }
}
