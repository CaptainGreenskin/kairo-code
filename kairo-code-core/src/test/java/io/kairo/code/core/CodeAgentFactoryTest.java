package io.kairo.code.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.kairo.api.agent.Agent;
import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.model.ModelConfig;
import io.kairo.api.model.ModelProvider;
import io.kairo.api.model.ModelResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class CodeAgentFactoryTest {

    /**
     * A stub ModelProvider that returns a fixed response — used for unit tests
     * that verify wiring without requiring a real LLM endpoint.
     */
    static class StubModelProvider implements ModelProvider {
        @Override
        public Mono<ModelResponse> call(List<Msg> messages, ModelConfig config) {
            List<Content> contents = List.of(new Content.TextContent("stub response"));
            return Mono.just(new ModelResponse("stub-id", contents, null, null, "stub-model"));
        }

        @Override
        public Flux<ModelResponse> stream(List<Msg> messages, ModelConfig config) {
            List<Content> contents = List.of(new Content.TextContent("stub response"));
            return Flux.just(new ModelResponse("stub-id", contents, null, null, "stub-model"));
        }

        @Override
        public String name() {
            return "stub";
        }
    }

    @Test
    void createReturnsNonNullAgent() {
        CodeAgentConfig config = new CodeAgentConfig(
                "test-api-key", "https://api.openai.com", "gpt-4o", 50, null);

        Agent agent = CodeAgentFactory.create(config, new StubModelProvider());

        assertThat(agent).isNotNull();
    }

    @Test
    void agentHasCorrectName() {
        CodeAgentConfig config = new CodeAgentConfig(
                "test-api-key", "https://api.openai.com", "gpt-4o", 50, null);

        Agent agent = CodeAgentFactory.create(config, new StubModelProvider());

        assertThat(agent.name()).isEqualTo("kairo-code");
    }

    @Test
    void configDefaultsAreApplied() {
        CodeAgentConfig config = new CodeAgentConfig("test-key", null, null, 0, null);

        assertThat(config.baseUrl()).isEqualTo("https://api.openai.com");
        assertThat(config.modelName()).isEqualTo("gpt-4o");
        assertThat(config.maxIterations()).isEqualTo(50);
    }

    @Test
    void configRejectsBlankApiKey() {
        assertThatThrownBy(() -> new CodeAgentConfig("", null, null, 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("apiKey");
    }
}
