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
import io.kairo.code.core.LlmClassifierConfig;
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

class ClassifierCommandTest {

    private CommandRegistry registry;
    private StringWriter outputCapture;
    private PrintWriter writer;

    @BeforeEach
    void setUp() {
        registry = new CommandRegistry();
        registry.register(new ClassifierCommand());
        outputCapture = new StringWriter();
        writer = new PrintWriter(outputCapture, true);
    }

    @Test
    void commandIsRegistered() {
        assertThat(registry.resolve(":classifier")).isPresent();
    }

    @Test
    void disabled_printsEnableRecipe() {
        // The whole reason this command exists separate from :stats — a user with the fallback
        // off needs a discoverable path to turn it on. If the recipe text drifts, the prose docs
        // and CLI surface will fall out of sync.
        CodeAgentConfig config = configDisabled();
        ReplContext context = createContext(config, null);

        new ClassifierCommand().execute("", context);

        String output = outputCapture.toString();
        assertThat(output).contains("LLM Bash Classifier");
        assertThat(output).contains("Status            : disabled (heuristic-only)");
        assertThat(output).contains("--llm-classifier");
        assertThat(output).contains("KAIRO_CODE_LLM_CLASSIFIER=true");
        assertThat(output).contains("llm-classifier=true");
        // Without a wired classifier we should NOT print the runtime counters section — that
        // would just be a wall of zeros and confuse the "is it on?" question.
        assertThat(output).doesNotContain("LLM calls");
    }

    @Test
    void enabledButIdle_printsConfigAndZeroCounters() {
        // Regression guard: when classifier is wired but hasn't fired yet (fresh session) the
        // command must still render counters so the user can tell the difference between
        // "wired but cold" and "not wired".
        LlmBashClassifier classifier =
                new LlmBashClassifier(stubProvider("{\"category\":\"UNKNOWN\"}"), "test-model");
        CodeAgentConfig config = configEnabled(null);
        ReplContext context = createContext(config, classifier);

        new ClassifierCommand().execute("", context);

        String output = outputCapture.toString();
        assertThat(output).contains("Status            : enabled");
        assertThat(output).contains("LLM calls         : 0");
        assertThat(output).contains("Cache hits/misses : 0 / 0");
        assertThat(output).contains("Verdict breakdown : (no LLM-resolved verdicts yet)");
        // No "how to enable" recipe when already enabled — keeps the surface compact.
        assertThat(output).doesNotContain("--llm-classifier");
    }

    @Test
    void enabledWithExplicitModel_showsConfiguredModel() {
        // Pin the model-precedence display: when LlmClassifierConfig.model is set it must show
        // the override, not the agent's primary. Without this an operator can't tell whether
        // their --llm-classifier-model flag actually took effect.
        LlmBashClassifier classifier =
                new LlmBashClassifier(stubProvider("{\"category\":\"UNKNOWN\"}"), "haiku-cheapo");
        CodeAgentConfig config = configEnabled("haiku-cheapo");
        ReplContext context = createContext(config, classifier);

        new ClassifierCommand().execute("", context);

        String output = outputCapture.toString();
        assertThat(output).contains("Model             : haiku-cheapo");
        assertThat(output).doesNotContain("inherited from agent");
    }

    @Test
    void enabledWithoutExplicitModel_showsInheritedHint() {
        // Inverse of the above — when model() is null the renderer must surface the agent's
        // primary plus the "(inherited from agent)" tag so the user understands the indirection.
        LlmBashClassifier classifier =
                new LlmBashClassifier(stubProvider("{\"category\":\"UNKNOWN\"}"), "gpt-4o");
        CodeAgentConfig config = configEnabled(null);
        ReplContext context = createContext(config, classifier);

        new ClassifierCommand().execute("", context);

        assertThat(outputCapture.toString())
                .contains("Model             : gpt-4o (inherited from agent)");
    }

    @Test
    void verdictBreakdownRendersAfterRealLlmCall() {
        // Drive an UNKNOWN command through the classifier so cache-miss + llm-call + verdict
        // counters all tick. Asserts the inspector surfaces it the same way :stats does.
        LlmBashClassifier classifier =
                new LlmBashClassifier(
                        stubProvider("{\"category\":\"DESTRUCTIVE\",\"reason\":\"x\"}"),
                        "test-model");
        classifier.classify("./obscure-script.sh").block();

        CodeAgentConfig config = configEnabled(null);
        ReplContext context = createContext(config, classifier);
        new ClassifierCommand().execute("", context);

        String output = outputCapture.toString();
        assertThat(output).contains("LLM calls         : 1");
        assertThat(output).contains("Cache hits/misses : 0 / 1");
        assertThat(output).contains("Verdict breakdown :");
        assertThat(output).contains("DESTRUCTIVE");
        assertThat(output).doesNotContain("(no LLM-resolved verdicts yet)");
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private static CodeAgentConfig configDisabled() {
        return new CodeAgentConfig(
                "test-key", "https://api.test.com", "gpt-4o", 50, "/tmp", null, 0, 0, null);
    }

    private static CodeAgentConfig configEnabled(String overrideModel) {
        return new CodeAgentConfig(
                "test-key", "https://api.test.com", "gpt-4o", 50, "/tmp", null, 0, 0, null,
                new LlmClassifierConfig(true, overrideModel, 512, 5_000L));
    }

    private ReplContext createContext(CodeAgentConfig config, LlmBashClassifier classifier) {
        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        DefaultToolExecutor toolExecutor =
                new DefaultToolExecutor(toolRegistry, new DefaultPermissionGuard());
        CodeAgentSession session =
                new CodeAgentSession(
                        stubAgent(), toolExecutor, toolRegistry, Set.of(), null,
                        new ToolUsageTracker(), null, null, classifier);
        return new ReplContext(
                session, config, null, registry, writer, null, null, null, null);
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
}
