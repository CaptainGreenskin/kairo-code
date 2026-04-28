package io.kairo.code.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentSnapshot;
import io.kairo.api.agent.AgentState;
import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.model.ModelConfig;
import io.kairo.api.model.ModelProvider;
import io.kairo.api.model.ModelResponse;
import io.kairo.code.cli.CommandRegistry;
import io.kairo.code.cli.ReplContext;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.core.tool.DefaultPermissionGuard;
import io.kairo.core.tool.DefaultToolExecutor;
import io.kairo.core.tool.DefaultToolRegistry;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class CompactCommandTest {

    private StringWriter outputCapture;
    private PrintWriter writer;
    private CodeAgentConfig config;
    private CommandRegistry registry;

    @BeforeEach
    void setUp() {
        outputCapture = new StringWriter();
        writer = new PrintWriter(outputCapture, true);
        config = new CodeAgentConfig("test-key", "https://api.test.com", "gpt-4o", 50, "/tmp", null);
        registry = new CommandRegistry();
    }

    @Test
    void shortHistoryPrintsTooShort() {
        // 4 messages = 2 turns, below MIN_TURNS of 6
        List<Msg> shortHistory = List.of(
                Msg.of(MsgRole.USER, "hello"),
                Msg.of(MsgRole.ASSISTANT, "hi"),
                Msg.of(MsgRole.USER, "bye"),
                Msg.of(MsgRole.ASSISTANT, "see ya"));

        ReplContext context = createContextWithHistory(shortHistory);

        new CompactCommand().execute("", context);

        assertThat(outputCapture.toString()).contains("Conversation too short to compact.");
    }

    @Test
    void normalHistoryCallsCompactorAndPrintsSuccess() {
        List<Msg> history = buildHistory(12); // 6 turns

        CompactCommand cmd = new CompactCommand();
        cmd.testProvider = new StubSummaryProvider();
        ReplContext context = createContextWithHistoryAndStubCompactor(history);

        cmd.execute("", context);

        String output = outputCapture.toString();
        assertThat(output).contains("Conversation compacted.");
        assertThat(output).contains("6 turns");
    }

    @Test
    void compactorFailurePrintsErrorNotCrash() {
        List<Msg> history = buildHistory(12);

        CompactCommand cmd = new CompactCommand();
        cmd.testProvider = new FailingProvider();
        ReplContext context = createContextWithHistoryAndStubCompactor(history);

        cmd.execute("", context);

        String output = outputCapture.toString();
        assertThat(output).contains("Failed to compact conversation");
    }

    @Test
    void emptyHistoryPrintsNoHistory() {
        ReplContext context = createContextWithEmptyHistory();

        new CompactCommand().execute("", context);

        assertThat(outputCapture.toString()).contains("Conversation too short to compact.");
    }

    @Test
    void agentWithoutSnapshotPrintsNoHistory() {
        ReplContext context = createContextWithAgentNoSnapshot();

        new CompactCommand().execute("", context);

        assertThat(outputCapture.toString()).contains("No conversation history available to compact.");
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private static List<Msg> buildHistory(int n) {
        List<Msg> messages = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            MsgRole role = i % 2 == 0 ? MsgRole.USER : MsgRole.ASSISTANT;
            messages.add(Msg.of(role, "message " + i));
        }
        return messages;
    }

    private ReplContext createContextWithHistory(List<Msg> history) {
        Agent agent = new StubAgentWithSnapshot(history, 0);
        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        DefaultToolExecutor toolExecutor = new DefaultToolExecutor(toolRegistry, new DefaultPermissionGuard());
        CodeAgentSession session = new CodeAgentSession(agent, toolExecutor, toolRegistry, Set.of());
        return new ReplContext(session, config, null, registry, writer, null, null, null, null);
    }

    private ReplContext createContextWithHistoryAndStubCompactor(List<Msg> history) {
        // Use a stub agent that has the history in its snapshot and accepts summary injection
        Agent agent = new StubAgentWithSnapshot(history, 0);
        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        DefaultToolExecutor toolExecutor = new DefaultToolExecutor(toolRegistry, new DefaultPermissionGuard());
        CodeAgentSession session = new CodeAgentSession(agent, toolExecutor, toolRegistry, Set.of());

        // Use the stub provider config so the compactor call goes to our stub
        return new ReplContext(session, config, null, registry, writer, null, null, null, null);
    }

    private ReplContext createContextWithEmptyHistory() {
        Agent agent = new StubAgentWithSnapshot(List.of(), 0);
        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        DefaultToolExecutor toolExecutor = new DefaultToolExecutor(toolRegistry, new DefaultPermissionGuard());
        CodeAgentSession session = new CodeAgentSession(agent, toolExecutor, toolRegistry, Set.of());
        return new ReplContext(session, config, null, registry, writer, null, null, null, null);
    }

    private ReplContext createContextWithAgentNoSnapshot() {
        Agent agent = stubAgentNoSnapshot();
        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        DefaultToolExecutor toolExecutor = new DefaultToolExecutor(toolRegistry, new DefaultPermissionGuard());
        CodeAgentSession session = new CodeAgentSession(agent, toolExecutor, toolRegistry, Set.of());
        return new ReplContext(session, config, null, registry, writer, null, null, null, null);
    }

    private static Agent stubAgentNoSnapshot() {
        return new Agent() {
            @Override
            public Mono<Msg> call(Msg input) {
                return Mono.empty();
            }

            @Override
            public String id() {
                return "stub-id";
            }

            @Override
            public String name() {
                return "stub-agent";
            }

            @Override
            public AgentState state() {
                return AgentState.IDLE;
            }

            @Override
            public void interrupt() {}

            @Override
            public AgentSnapshot snapshot() {
                throw new UnsupportedOperationException("no snapshot");
            }
        };
    }

    /** Provider that returns a summary for compaction. */
    static class StubSummaryProvider implements ModelProvider {
        @Override
        public Mono<ModelResponse> call(List<Msg> messages, ModelConfig config) {
            List<Content> contents = List.of(new Content.TextContent("Previous discussion about refactoring."));
            return Mono.just(new ModelResponse("stub-id", contents, null, null, "stub-model"));
        }

        @Override
        public Flux<ModelResponse> stream(List<Msg> messages, ModelConfig config) {
            List<Content> contents = List.of(new Content.TextContent("Previous discussion about refactoring."));
            return Flux.just(new ModelResponse("stub-id", contents, null, null, "stub-model"));
        }

        @Override
        public String name() {
            return "stub";
        }
    }

    /** Provider that always fails. */
    static class FailingProvider implements ModelProvider {
        @Override
        public Mono<ModelResponse> call(List<Msg> messages, ModelConfig config) {
            return Mono.error(new RuntimeException("model provider failure"));
        }

        @Override
        public Flux<ModelResponse> stream(List<Msg> messages, ModelConfig config) {
            return Flux.error(new RuntimeException("model provider failure"));
        }

        @Override
        public String name() {
            return "failing";
        }
    }

    /** Agent stub that supports snapshot with conversation history. */
    private static class StubAgentWithSnapshot implements Agent {
        private final List<Msg> history;
        private final long tokens;

        StubAgentWithSnapshot(List<Msg> history, long tokens) {
            this.history = history;
            this.tokens = tokens;
        }

        @Override
        public Mono<Msg> call(Msg input) {
            // Accept the summary message without actually calling LLM
            return Mono.just(Msg.of(MsgRole.ASSISTANT, "acknowledged"));
        }

        @Override
        public String id() {
            return "stub-id";
        }

        @Override
        public String name() {
            return "stub-agent";
        }

        @Override
        public AgentState state() {
            return AgentState.IDLE;
        }

        @Override
        public void interrupt() {}

        @Override
        public AgentSnapshot snapshot() {
            return new AgentSnapshot(
                    "stub-id", "stub-agent", AgentState.IDLE,
                    0, tokens, history, Map.of(), Instant.now());
        }
    }
}
