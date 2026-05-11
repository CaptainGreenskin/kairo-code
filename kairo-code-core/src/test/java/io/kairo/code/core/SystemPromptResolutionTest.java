package io.kairo.code.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.model.ModelConfig;
import io.kairo.api.model.ModelProvider;
import io.kairo.api.model.ModelResponse;
import io.kairo.api.message.Msg;
import io.kairo.core.model.anthropic.AnthropicProvider;
import io.kairo.core.model.openai.OpenAIProvider;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Tests for {@link CodeAgentFactory#selectSystemPromptResource(ModelProvider, CodeAgentConfig)}.
 *
 * <p>Verifies the resolution order: env var override > Anthropic provider type >
 * model name match (claude/glm) > generic default.
 */
class SystemPromptResolutionTest {

    private static CodeAgentConfig configWithModel(String model) {
        return new CodeAgentConfig("dummy-key", "https://example.invalid", model, 5, null, null, 0, 0, null);
    }

    /** Stand-in {@link ModelProvider} that does not pattern-match Anthropic or OpenAI. */
    private static final class GenericStubProvider implements ModelProvider {
        @Override
        public Mono<ModelResponse> call(List<Msg> messages, ModelConfig config) {
            return Mono.empty();
        }

        @Override
        public Flux<ModelResponse> stream(List<Msg> messages, ModelConfig config) {
            return Flux.empty();
        }

        @Override
        public String name() {
            return "stub";
        }
    }

    @Test
    void anthropicProvider_resolvesClaudePrompt() {
        ModelProvider provider = new AnthropicProvider("dummy-key", "https://api.anthropic.com");
        String resource = CodeAgentFactory.selectSystemPromptResource(
                provider, configWithModel("claude-sonnet-4-6"));
        assertThat(resource).isEqualTo(CodeAgentFactory.SYSTEM_PROMPT_CLAUDE_RESOURCE);
    }

    @Test
    void openAiProviderWithGlmModel_resolvesGlmPrompt() {
        ModelProvider provider = new OpenAIProvider("dummy-key", "https://api.example.com");
        String resource = CodeAgentFactory.selectSystemPromptResource(
                provider, configWithModel("glm-5.1"));
        assertThat(resource).isEqualTo(CodeAgentFactory.SYSTEM_PROMPT_GLM_RESOURCE);
    }

    @Test
    void openAiProviderWithClaudeModel_resolvesClaudePrompt() {
        ModelProvider provider = new OpenAIProvider("dummy-key", "https://api.example.com");
        String resource = CodeAgentFactory.selectSystemPromptResource(
                provider, configWithModel("claude-sonnet-4"));
        assertThat(resource).isEqualTo(CodeAgentFactory.SYSTEM_PROMPT_CLAUDE_RESOURCE);
    }

    @Test
    void unknownProviderAndModel_resolvesDefault() {
        ModelProvider provider = new GenericStubProvider();
        String resource = CodeAgentFactory.selectSystemPromptResource(
                provider, configWithModel("gpt-4o"));
        assertThat(resource).isEqualTo("system-prompt.md");
    }

    @Test
    void nullProvider_fallsBackToModelName() {
        String claude = CodeAgentFactory.selectSystemPromptResource(
                null, configWithModel("claude-opus-4-6"));
        assertThat(claude).isEqualTo(CodeAgentFactory.SYSTEM_PROMPT_CLAUDE_RESOURCE);

        String glm = CodeAgentFactory.selectSystemPromptResource(
                null, configWithModel("glm-5.1"));
        assertThat(glm).isEqualTo(CodeAgentFactory.SYSTEM_PROMPT_GLM_RESOURCE);

        String gpt = CodeAgentFactory.selectSystemPromptResource(
                null, configWithModel("gpt-4o"));
        assertThat(gpt).isEqualTo("system-prompt.md");
    }
}
