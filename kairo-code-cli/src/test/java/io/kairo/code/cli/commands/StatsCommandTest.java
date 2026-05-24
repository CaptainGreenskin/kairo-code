package io.kairo.code.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentState;
import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.model.ModelConfig;
import io.kairo.api.model.ModelProvider;
import io.kairo.api.model.ModelResponse;
import io.kairo.code.cli.CommandRegistry;
import io.kairo.code.cli.ReplContext;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.code.core.stats.ToolUsageTracker;
import io.kairo.core.guardrail.policy.LlmBashClassifier;
import io.kairo.core.tool.DefaultPermissionGuard;
import io.kairo.core.tool.DefaultToolExecutor;
import io.kairo.core.tool.DefaultToolRegistry;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class StatsCommandTest {

    private CommandRegistry registry;
    private StringWriter outputCapture;
    private PrintWriter writer;
    private CodeAgentConfig config;

    @BeforeEach
    void setUp() {
        registry = new CommandRegistry();
        registry.register(new StatsCommand());
        outputCapture = new StringWriter();
        writer = new PrintWriter(outputCapture, true);
        config = new CodeAgentConfig("test-key", "https://api.test.com", "gpt-4o", 50, "/tmp", null, 0, 0, null);
    }

    @Test
    void statsCommandIsRegistered() {
        assertThat(registry.resolve(":stats")).isPresent();
    }

    @Test
    void noData_showsFriendlyMessage() {
        ReplContext context = createContext(new ToolUsageTracker());

        new StatsCommand().execute("", context);

        assertThat(outputCapture.toString()).contains("No tool usage recorded yet.");
    }

    @Test
    void withData_showsTableWithColumns() {
        ToolUsageTracker tracker = new ToolUsageTracker();
        // Simulate some tool usage
        tracker.onToolResult(event("bash", true, 200));
        tracker.onToolResult(event("bash", true, 300));
        tracker.onToolResult(event("bash", false, 100));
        tracker.onToolResult(event("read", true, 15));
        tracker.onToolResult(event("edit", true, 40));

        ReplContext context = createContext(tracker);

        new StatsCommand().execute("", context);

        String output = outputCapture.toString();
        assertThat(output).contains("Tool Usage Statistics");
        assertThat(output).contains("Tool");
        assertThat(output).contains("Calls");
        assertThat(output).contains("Success%");
        assertThat(output).contains("Avg(ms)");
        assertThat(output).contains("bash");
        assertThat(output).contains("read");
        assertThat(output).contains("edit");
    }

    @Test
    void withData_showsCorrectStats() {
        ToolUsageTracker tracker = new ToolUsageTracker();
        tracker.onToolResult(event("bash", true, 200));
        tracker.onToolResult(event("bash", false, 100));
        tracker.onToolResult(event("read", true, 10));

        ReplContext context = createContext(tracker);

        new StatsCommand().execute("", context);

        String output = outputCapture.toString();
        // bash: 2 calls, 50.0% success, 150 avg ms
        assertThat(output).contains("bash");
        assertThat(output).contains("50.0%");
        // read: 1 call, 100.0% success
        assertThat(output).contains("100.0%");
    }

    @Test
    void noToolUsage_butClassifierWired_stillShowsClassifierSection() {
        // Regression guard for an empty-but-enabled session: previously :stats short-circuited on
        // "no tool usage" and the user couldn't tell whether the LLM fallback was actually wired.
        LlmBashClassifier classifier = new LlmBashClassifier(stubProvider(), "test-model");
        ReplContext context = createContext(new ToolUsageTracker(), classifier);

        new StatsCommand().execute("", context);

        String output = outputCapture.toString();
        assertThat(output).contains("LLM Bash Classifier");
        assertThat(output).contains("LLM calls         : 0");
        assertThat(output).doesNotContain("No tool usage recorded yet.");
    }

    @Test
    void classifierCounters_renderAfterAFiredLlmCall() {
        // Drive an UNKNOWN command through the classifier so cache-miss + llm-call + verdict
        // counters all tick. Asserts that the surface actually reflects classifier state — without
        // this the user has no way to confirm the fallback is firing.
        LlmBashClassifier classifier =
                new LlmBashClassifier(
                        stubProvider("{\"category\":\"DESTRUCTIVE\",\"reason\":\"x\"}"),
                        "test-model");
        classifier.classify("./obscure-script.sh").block();

        ReplContext context = createContext(new ToolUsageTracker(), classifier);
        new StatsCommand().execute("", context);

        String output = outputCapture.toString();
        assertThat(output).contains("LLM Bash Classifier");
        assertThat(output).contains("LLM calls         : 1");
        assertThat(output).contains("Cache hits/misses : 0 / 1");
        assertThat(output).contains("Verdict breakdown");
        assertThat(output).contains("DESTRUCTIVE");
    }

    @Test
    void classifierNotWired_existingBehaviourUnchanged() {
        // Pin the disabled-by-default case so users without the fallback don't see surprising
        // empty headers.
        ReplContext context = createContext(new ToolUsageTracker());
        new StatsCommand().execute("", context);

        String output = outputCapture.toString();
        assertThat(output).contains("No tool usage recorded yet.");
        assertThat(output).doesNotContain("LLM Bash Classifier");
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private ReplContext createContext(ToolUsageTracker tracker) {
        return createContext(tracker, null);
    }

    private ReplContext createContext(ToolUsageTracker tracker, LlmBashClassifier classifier) {
        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        DefaultToolExecutor toolExecutor =
                new DefaultToolExecutor(toolRegistry, new DefaultPermissionGuard());
        CodeAgentSession session =
                new CodeAgentSession(
                        stubAgent(), toolExecutor, toolRegistry, Set.of(), null,
                        tracker, null, null, classifier);
        return new ReplContext(
                session, config, null, registry, writer, null, null, null, null);
    }

    private static ModelProvider stubProvider() {
        return stubProvider("{\"category\":\"UNKNOWN\"}");
    }

    private static ModelProvider stubProvider(String responseJson) {
        return new ModelProvider() {
            @Override
            public Mono<ModelResponse> call(List<Msg> messages, ModelConfig config) {
                return Mono.just(
                        new ModelResponse(
                                "stub-id",
                                List.of(new Content.TextContent(responseJson)),
                                new ModelResponse.Usage(0, 0, 0, 0),
                                ModelResponse.StopReason.END_TURN,
                                "stub-model"));
            }

            @Override
            public Flux<ModelResponse> stream(List<Msg> messages, ModelConfig config) {
                return Flux.from(call(messages, config));
            }

            @Override
            public String name() {
                return "stub-provider";
            }
        };
    }

    private static Agent stubAgent() {
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
        };
    }

    private static io.kairo.api.hook.ToolResultEvent event(
            String tool, boolean success, long millis) {
        io.kairo.api.tool.ToolResult result =
                success ? io.kairo.api.tool.ToolResult.success("id1", "output")
                        : io.kairo.api.tool.ToolResult.error("id1", "output");
        return new io.kairo.api.hook.ToolResultEvent(
                tool, result, java.time.Duration.ofMillis(millis), success);
    }
}
