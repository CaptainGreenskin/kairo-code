package io.kairo.code.service.agent;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentState;
import io.kairo.api.event.KairoEventBus;
import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.model.ModelConfig;
import io.kairo.api.model.ModelProvider;
import io.kairo.api.model.ModelResponse;
import io.kairo.api.team.TeamConfig;
import io.kairo.api.team.TeamEvent;
import io.kairo.api.team.TeamEventType;
import io.kairo.api.team.TeamResult;
import io.kairo.api.team.TeamStatus;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.code.core.team.MessageBus;
import io.kairo.code.core.team.SwarmCoordinator;
import io.kairo.code.core.team.TeamManager;
import io.kairo.code.service.AgentEvent;
import io.kairo.code.service.SessionPhase;
import io.kairo.code.service.agent.tools.NoNarrationTool;
import io.kairo.code.service.concurrency.AgentConcurrencyController;
import io.kairo.code.service.team.TriageGate;
import io.kairo.core.event.DefaultKairoEventBus;
import io.kairo.core.tool.DefaultPermissionGuard;
import io.kairo.core.tool.DefaultToolExecutor;
import io.kairo.core.tool.DefaultToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M-Experts-Upgrade / #61: focused tests for the private {@code NarratorDispatcher} inner class.
 *
 * <p>The dispatcher is private; tests drive it through the public seam — push {@link TeamEvent}s
 * to the {@link KairoEventBus} and let the swarm-event bridge enqueue them. The narrator uses a
 * direct {@link ModelProvider#call} with only the no_narration tool definition — tests supply a
 * recording ModelProvider to assert prompt contents and dispatch counts.
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class NarratorDispatcherTest {

    private static final String SESSION_ID = "narrator-session-1";

    // ── #1 debounce: 3 events within window → 1 dispatch carrying all 3 ──────

    @Test
    void debounce_batchesEventsBeforeCalling() {
        Harness h = Harness.builder().withNarratorDebounce(Duration.ofMillis(200)).build();
        try {
            h.publishStepCompleted("researcher", "found three callers");
            h.publishStepCompleted("coder", "drafted patch v1");
            h.publishStepCompleted("reviewer", "approved the patch");

            sleepQuietly(80);
            assertThat(h.model.calls).as("must wait for debounce").isEmpty();

            await(() -> !h.model.calls.isEmpty(), Duration.ofSeconds(2));

            assertThat(h.model.calls).hasSize(1);
            String prompt = h.model.calls.get(0);
            assertThat(prompt).contains("found three callers");
            assertThat(prompt).contains("drafted patch v1");
            assertThat(prompt).contains("approved the patch");
        } finally {
            h.payload.stop();
        }
    }

    // ── #2 happy path: narrator emits TEXT_CHUNK ──────────────────────────────

    @Test
    void narrate_emitsTextChunk() {
        Harness h = Harness.builder()
                .withNarratorDebounce(Duration.ofMillis(150))
                .withModelResponse(msgs -> new ModelResponse(
                        "resp-1",
                        List.of(new Content.TextContent(
                                "Researcher confirmed three callers; recommend Coder focus next.")),
                        null, null, "test-model"))
                .build();
        try {
            h.publishStepCompleted("researcher", "found three callers");

            await(() -> h.events.stream()
                            .anyMatch(e -> e.type() == AgentEvent.EventType.TEXT_CHUNK),
                    Duration.ofSeconds(2));

            AgentEvent narration = h.events.stream()
                    .filter(e -> e.type() == AgentEvent.EventType.TEXT_CHUNK)
                    .findFirst()
                    .orElseThrow();
            assertThat(narration.content())
                    .isEqualTo("Researcher confirmed three callers; recommend Coder focus next.");
        } finally {
            h.payload.stop();
        }
    }

    // ── #3 no_narration tool call suppresses TEXT_CHUNK ────────────────────────

    @Test
    void noNarration_suppressesEmission() {
        Harness h = Harness.builder()
                .withNarratorDebounce(Duration.ofMillis(150))
                .withModelResponse(msgs -> new ModelResponse(
                        "resp-1",
                        List.of(new Content.TextContent(""),
                                new Content.ToolUseContent("tu-1", NoNarrationTool.NAME, Map.of())),
                        null, null, "test-model"))
                .build();
        try {
            h.publishStepCompleted("coder", "low-value chatter");

            await(() -> !h.model.calls.isEmpty(), Duration.ofSeconds(2));
            sleepQuietly(150);

            assertThat(h.model.calls).hasSize(1);
            assertThat(h.events).noneMatch(e -> e.type() == AgentEvent.EventType.TEXT_CHUNK);
        } finally {
            h.payload.stop();
        }
    }

    // ── #4 disabled narrator never invokes the model ──────────────────────────

    @Test
    void disabledNarrator_neverCalls() {
        Harness h = Harness.builder().disableNarrator().build();
        try {
            for (int i = 0; i < 6; i++) {
                h.publishStepCompleted("researcher", "event-" + i);
            }
            sleepQuietly(400);

            assertThat(h.model.calls).as("disabled narrator must not invoke model").isEmpty();
            assertThat(h.events).anyMatch(e -> e.type() == AgentEvent.EventType.PEER_MESSAGE);
        } finally {
            h.payload.stop();
        }
    }

    // ── #71 high-water-mark fires ahead of the debounce window ──────────────────

    @Test
    void highWaterMark_firesEarly() {
        Harness h = Harness.builder()
                .withNarratorDebounce(Duration.ofSeconds(2))
                .withMaxBatchSize(10)
                .withQueueHighWaterMark(3)
                .build();
        try {
            long start = System.currentTimeMillis();
            for (int i = 0; i < 3; i++) {
                h.publishStepCompleted("researcher", "event-" + i);
            }

            await(() -> !h.model.calls.isEmpty(), Duration.ofMillis(800));
            long elapsedMs = System.currentTimeMillis() - start;

            assertThat(h.model.calls).hasSize(1);
            assertThat(elapsedMs)
                    .as("must fire well before the 2s debounce window")
                    .isLessThan(1500);

            String prompt = h.model.calls.get(0);
            assertThat(prompt).contains("event-0");
            assertThat(prompt).contains("event-1");
            assertThat(prompt).contains("event-2");
        } finally {
            h.payload.stop();
        }
    }

    // ── #5 stop() disposes the dispatcher cleanly ──────────────────────────────

    @Test
    void stop_disposesDispatcher() throws InterruptedException {
        Harness h = Harness.builder().withNarratorDebounce(Duration.ofMillis(150)).build();
        h.payload.stop();
        Thread.sleep(80);

        h.publishStepCompleted("researcher", "post-stop event");
        Thread.sleep(400);

        assertThat(h.model.calls).as("stopped dispatcher must not invoke model").isEmpty();
    }

    // ── #6 model call only has no_narration tool (no Bash/Write/etc.) ──────────

    @Test
    void modelCall_onlyHasNoNarrationTool() {
        Harness h = Harness.builder()
                .withNarratorDebounce(Duration.ofMillis(100))
                .build();
        try {
            h.publishStepCompleted("researcher", "found something");

            await(() -> !h.model.configs.isEmpty(), Duration.ofSeconds(2));

            ModelConfig config = h.model.configs.get(0);
            assertThat(config.tools()).hasSize(1);
            assertThat(config.tools().get(0).name()).isEqualTo(NoNarrationTool.NAME);
        } finally {
            h.payload.stop();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void await(java.util.function.BooleanSupplier condition, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Condition not met within " + timeout);
            }
            sleepQuietly(20);
        }
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Per-test wiring: builds a TeamSessionPayload with experts preset + recording model. */
    private static final class Harness {
        final TeamSessionPayload payload;
        final RecordingModelProvider model;
        final KairoEventBus bus;
        final List<AgentEvent> events;
        private final String teamId;

        Harness(TeamSessionPayload payload, RecordingModelProvider model, KairoEventBus bus,
                List<AgentEvent> events, String teamId) {
            this.payload = payload;
            this.model = model;
            this.bus = bus;
            this.events = events;
            this.teamId = teamId;
        }

        void publishStepCompleted(String role, String summary) {
            TeamEvent te = new TeamEvent(
                    TeamEventType.STEP_COMPLETED,
                    teamId,
                    "req-1",
                    Instant.now(),
                    Map.of("role", role, "summary", summary));
            bus.publish(te.toKairoEvent());
        }

        static Builder builder() {
            return new Builder();
        }

        static final class Builder {
            private Duration debounce = Duration.ofMillis(200);
            private int maxBatch = 10;
            private int highWater = 5;
            private boolean narratorEnabled = true;
            private Function<List<Msg>, ModelResponse> modelResponse =
                    msgs -> new ModelResponse("resp-1",
                            List.of(new Content.TextContent("stub-narration")),
                            null, null, "test-model");

            Builder withNarratorDebounce(Duration d) { this.debounce = d; return this; }
            Builder withMaxBatchSize(int n) { this.maxBatch = n; return this; }
            Builder withQueueHighWaterMark(int n) { this.highWater = n; return this; }
            Builder withModelResponse(Function<List<Msg>, ModelResponse> fn) {
                this.modelResponse = fn;
                return this;
            }
            Builder disableNarrator() { this.narratorEnabled = false; return this; }

            Harness build() {
                RecordingModelProvider model = new RecordingModelProvider(modelResponse);
                Agent stubAgent = new StubAgent();
                KairoEventBus bus = new DefaultKairoEventBus();
                List<AgentEvent> events = new CopyOnWriteArrayList<>();

                Sinks.Many<AgentEvent> sink = Sinks.many().replay().all();
                AgentConcurrencyController concurrency = new AgentConcurrencyController();
                AgentRuntimeContext ctx = new AgentRuntimeContext(
                        SESSION_ID, sink, new AtomicBoolean(false),
                        new AtomicReference<>(SessionPhase.IDLE),
                        p -> {}, concurrency);

                TeamSessionPayload.NarratorSettings narratorSettings = narratorEnabled
                        ? new TeamSessionPayload.NarratorSettings(
                                true, "You are the Team Lead. Narrate or call no_narration.",
                                debounce, maxBatch, highWater)
                        : TeamSessionPayload.NarratorSettings.disabled();

                AgentSessionPayload fallback = newFallback();
                TeamSessionPayload.ExpertsPresetConfig preset =
                        new TeamSessionPayload.ExpertsPresetConfig(
                                noopCoordinator(), TeamConfig.defaults(), anyTriage(),
                                fallback, narratorSettings, bus,
                                narratorEnabled ? model : null);

                CodeAgentSession session = newSession(stubAgent);
                TeamSessionPayload payload = new TeamSessionPayload(
                        newConfig(), session, ctx, new TeamManager(), new MessageBus(), preset);

                sink.asFlux().subscribe(events::add);

                String teamId = "team-" + System.nanoTime();
                try {
                    var field = TeamSessionPayload.class.getDeclaredField("pendingTeamId");
                    field.setAccessible(true);
                    field.set(payload, teamId);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError("Failed to inject pendingTeamId", e);
                }
                return new Harness(payload, model, bus, events, teamId);
            }
        }
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

    private static AgentSessionPayload newFallback() {
        Agent stub = new StubAgent();
        CodeAgentSession session = newSession(stub);
        AgentRuntimeContext ctx = new AgentRuntimeContext(
                "fallback",
                Sinks.many().replay().all(),
                new AtomicBoolean(false),
                new AtomicReference<>(SessionPhase.IDLE),
                p -> {},
                new AgentConcurrencyController());
        return new AgentSessionPayload(newConfig(), session, ctx);
    }

    private static TriageGate anyTriage() {
        return goal -> true;
    }

    private static SwarmCoordinator noopCoordinator() {
        var registry = new io.kairo.multiagent.subagent.ExpertRoleRegistry();
        var planner = new io.kairo.multiagent.orchestration.internal.DefaultPlanner(registry, null, null);
        var coord = new io.kairo.multiagent.orchestration.ExpertTeamCoordinator(
                null, new io.kairo.multiagent.orchestration.SimpleEvaluationStrategy(),
                null, planner, registry);
        return new SwarmCoordinator(
                coord, registry, new io.kairo.multiagent.orchestration.tck.NoopMessageBus(), List.of()) {
            @Override
            public Mono<TeamResult> startExpertTeam(String goal, TeamConfig cfg,
                                                    List<String> roleIds, boolean planOnly) {
                return Mono.just(TeamResult.withoutOutput(
                        "req-noop", TeamStatus.COMPLETED, List.of(),
                        Duration.ZERO, List.of()));
            }
        };
    }

    private static final class StubAgent implements Agent {
        @Override public Mono<Msg> call(Msg input) {
            return Mono.just(Msg.of(MsgRole.ASSISTANT, "stub"));
        }
        @Override public String id() { return "stub-agent"; }
        @Override public String name() { return "stub-agent"; }
        @Override public AgentState state() { return AgentState.IDLE; }
        @Override public void interrupt() {}
    }

    /**
     * Recording ModelProvider — captures the user message text of every call and lets the test
     * customize the returned {@link ModelResponse} (text-only, no_narration tool call, etc.).
     */
    private static final class RecordingModelProvider implements ModelProvider {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<ModelConfig> configs = new CopyOnWriteArrayList<>();
        final AtomicInteger callCount = new AtomicInteger();
        private final Function<List<Msg>, ModelResponse> responder;

        RecordingModelProvider(Function<List<Msg>, ModelResponse> responder) {
            this.responder = responder;
        }

        @Override
        public Mono<ModelResponse> call(List<Msg> messages, ModelConfig config) {
            String userText = messages.stream()
                    .filter(m -> m.role() == MsgRole.USER)
                    .map(Msg::text)
                    .findFirst()
                    .orElse("");
            calls.add(userText);
            configs.add(config);
            callCount.incrementAndGet();
            return Mono.just(responder.apply(messages));
        }

        @Override
        public Flux<ModelResponse> stream(List<Msg> messages, ModelConfig config) {
            return Flux.from(call(messages, config));
        }

        @Override
        public String name() { return "recording-stub"; }
    }
}
