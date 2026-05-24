package io.kairo.code.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.agent.Agent;
import io.kairo.api.guardrail.GuardrailContext;
import io.kairo.api.guardrail.GuardrailDecision;
import io.kairo.api.guardrail.GuardrailPayload;
import io.kairo.api.guardrail.GuardrailPhase;
import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.model.ModelConfig;
import io.kairo.api.model.ModelProvider;
import io.kairo.api.model.ModelResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Wiring tests for the LLM bash-classifier path through {@link CodeAgentFactory}.
 *
 * <p>These do <strong>not</strong> spin up a full agent loop — they call the factory's
 * package-private {@code buildDangerousCommandPolicy} seam and drive the resulting policy with
 * synthetic {@link GuardrailContext}s. The intent is to pin the wiring contract:
 *
 * <ul>
 *   <li>{@code LlmClassifierConfig.disabled()} (the default) → policy never touches the
 *       {@link ModelProvider}, even for heuristically-{@code UNKNOWN} commands. Catches a future
 *       regression where someone changes the default to {@code enabled()} and silently starts
 *       making model calls per shell command.
 *   <li>{@code LlmClassifierConfig.enabledDefault()} → policy consults the provider for
 *       {@code UNKNOWN} commands and routes the verdict through the same {@code decide()} ladder
 *       the heuristic path uses.
 * </ul>
 *
 * <p>The end-to-end {@link CodeAgentFactory#create} smoke test guards that flipping the knob does
 * not break factory bootstrap (no exceptions in the guardrail-chain try/catch).
 */
class CodeAgentFactoryLlmClassifierTest {

    @Test
    void disabledByDefault_obscureCommandNeverHitsLlm() {
        CountingStubProvider provider = new CountingStubProvider("{\"category\":\"UNKNOWN\"}");
        CodeAgentConfig config =
                new CodeAgentConfig(
                        "test-key", "https://api.openai.com", "gpt-4o", 50, null, null, 0, 0, null);

        var classifier =
                CodeAgentFactory.buildLlmBashClassifierIfEnabled(
                        config, CodeAgentFactory.SessionOptions.empty(), provider);
        assertThat(classifier).isNull();

        var policy = CodeAgentFactory.buildDangerousCommandPolicy(classifier);
        var decision = policy.evaluate(preBash("./obscure.sh")).block();

        assertThat(decision.action()).isEqualTo(GuardrailDecision.Action.ALLOW);
        assertThat(provider.calls.get()).isZero();
    }

    @Test
    void enabled_unknownCommandIsClassifiedByLlm_andUpgradesToDeny() {
        CountingStubProvider provider =
                new CountingStubProvider("{\"category\":\"DESTRUCTIVE\",\"reason\":\"x\"}");

        CodeAgentConfig config =
                new CodeAgentConfig(
                        "test-key",
                        "https://api.openai.com",
                        "gpt-4o",
                        50,
                        null,
                        null,
                        0,
                        0,
                        null,
                        LlmClassifierConfig.enabledDefault());

        var classifier =
                CodeAgentFactory.buildLlmBashClassifierIfEnabled(
                        config, CodeAgentFactory.SessionOptions.empty(), provider);
        assertThat(classifier).isNotNull();
        var policy = CodeAgentFactory.buildDangerousCommandPolicy(classifier);
        var decision = policy.evaluate(preBash("./obscure.sh")).block();

        assertThat(decision.action()).isEqualTo(GuardrailDecision.Action.DENY);
        assertThat(decision.reason()).contains("[DESTRUCTIVE]");
        assertThat(provider.calls.get()).isEqualTo(1);
    }

    @Test
    void enabled_knownDestructiveCommand_doesNotConsultLlm() {
        // Regression guard for the happy path: even with LLM fallback wired, a heuristic-known
        // DESTRUCTIVE command (rm -rf /) must be blocked synchronously. Burning an LLM call here
        // would tax every shell tool invocation and break offline / outage behaviour.
        CountingStubProvider provider = new CountingStubProvider("{\"category\":\"UNKNOWN\"}");
        CodeAgentConfig config =
                new CodeAgentConfig(
                        "test-key",
                        "https://api.openai.com",
                        "gpt-4o",
                        50,
                        null,
                        null,
                        0,
                        0,
                        null,
                        LlmClassifierConfig.enabledDefault());

        var classifier =
                CodeAgentFactory.buildLlmBashClassifierIfEnabled(
                        config, CodeAgentFactory.SessionOptions.empty(), provider);
        var policy = CodeAgentFactory.buildDangerousCommandPolicy(classifier);
        var decision = policy.evaluate(preBash("rm -rf /")).block();

        assertThat(decision.action()).isEqualTo(GuardrailDecision.Action.DENY);
        assertThat(provider.calls.get()).isZero();
    }

    @Test
    void enabled_modelOverride_usesConfiguredModelNotAgentModel() {
        // If the user pinned llmClassifier.model() to a cheaper / faster model than the agent's
        // primary, the factory should respect it. We observe it via the ModelConfig captured on
        // the provider call.
        CountingStubProvider provider =
                new CountingStubProvider("{\"category\":\"EXEC\",\"reason\":\"x\"}");
        CodeAgentConfig config =
                new CodeAgentConfig(
                        "test-key",
                        "https://api.openai.com",
                        "gpt-4o",
                        50,
                        null,
                        null,
                        0,
                        0,
                        null,
                        new LlmClassifierConfig(true, "haiku-cheapo", 64, 1_500L));

        var classifier =
                CodeAgentFactory.buildLlmBashClassifierIfEnabled(
                        config, CodeAgentFactory.SessionOptions.empty(), provider);
        var policy = CodeAgentFactory.buildDangerousCommandPolicy(classifier);
        var decision = policy.evaluate(preBash("./mystery.sh")).block();

        assertThat(decision.action()).isEqualTo(GuardrailDecision.Action.WARN);
        assertThat(provider.calls.get()).isEqualTo(1);
        assertThat(provider.lastConfig.get()).isNotNull();
        assertThat(provider.lastConfig.get().model()).isEqualTo("haiku-cheapo");
    }

    @Test
    void factoryCreate_withLlmClassifierEnabled_bootstrapsWithoutException() {
        // End-to-end smoke: confirms the new wiring doesn't trip the guardrail-chain try/catch
        // and the agent comes out of the factory intact. Doesn't drive the agent — the policy
        // contract is covered by the focused tests above.
        CountingStubProvider provider = new CountingStubProvider("stub");
        CodeAgentConfig config =
                new CodeAgentConfig(
                        "test-key",
                        "https://api.openai.com",
                        "gpt-4o",
                        50,
                        null,
                        null,
                        0,
                        0,
                        null,
                        LlmClassifierConfig.enabledDefault());

        Agent agent = CodeAgentFactory.create(config, provider);

        assertThat(agent).isNotNull();
        assertThat(agent.name()).isEqualTo("kairo-code");
    }

    private static GuardrailContext preBash(String command) {
        return new GuardrailContext(
                GuardrailPhase.PRE_TOOL,
                "test-agent",
                "bash",
                new GuardrailPayload.ToolInput("bash", Map.of("command", command)),
                Map.of());
    }

    /**
     * Stub provider that records call count + the {@link ModelConfig} of the most recent call so
     * tests can assert wiring decisions (which model was used, how many times the LLM fired)
     * without pulling in Mockito as a test dependency.
     */
    private static final class CountingStubProvider implements ModelProvider {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<ModelConfig> lastConfig = new AtomicReference<>();
        private final String responseJson;

        CountingStubProvider(String responseJson) {
            this.responseJson = responseJson;
        }

        @Override
        public Mono<ModelResponse> call(List<Msg> messages, ModelConfig config) {
            calls.incrementAndGet();
            lastConfig.set(config);
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
            return "counting-stub";
        }
    }
}
