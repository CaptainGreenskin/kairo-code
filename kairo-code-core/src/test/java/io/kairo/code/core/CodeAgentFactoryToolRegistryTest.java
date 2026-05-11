package io.kairo.code.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.kairo.api.agent.Agent;
import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.model.ModelConfig;
import io.kairo.api.model.ModelProvider;
import io.kairo.api.model.ModelResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Verifies that the M57 extended tool set is properly registered
 * in the {@link CodeAgentFactory} tool registry.
 */
class CodeAgentFactoryToolRegistryTest {

    /** Minimal stub ModelProvider for wiring tests. */
    static class StubModelProvider implements ModelProvider {
        @Override
        public Mono<ModelResponse> call(List<Msg> messages, ModelConfig config) {
            return Mono.just(new ModelResponse("stub-id",
                    List.of(new Content.TextContent("ok")), null, null, "stub-model"));
        }

        @Override
        public Flux<ModelResponse> stream(List<Msg> messages, ModelConfig config) {
            return Flux.just(new ModelResponse("stub-id",
                    List.of(new Content.TextContent("ok")), null, null, "stub-model"));
        }

        @Override
        public String name() {
            return "stub";
        }
    }

    private static CodeAgentConfig minimalConfig() {
        return new CodeAgentConfig(
                "test-key", "https://api.openai.com", "gpt-4o", 10, null, null, 0, 0, null);
    }

    private static List<String> toolNames() {
        var session = CodeAgentFactory.createSession(minimalConfig(),
                CodeAgentFactory.SessionOptions.empty().withModelProvider(new StubModelProvider()));
        return session.toolRegistry().getAll().stream()
                .map(io.kairo.api.tool.ToolDefinition::name)
                .toList();
    }

    @Test
    void standardToolsAreRegistered() {
        List<String> names = toolNames();
        // Baseline tools that were already registered before M57
        assertThat(names).contains("bash", "read", "write", "edit", "grep", "glob",
                "web_fetch", "git", "ask_user", "todo_read", "todo_write", "tree");
    }

    @Test
    void diffToolRegistered() {
        assertThat(toolNames()).contains("diff");
    }

    @Test
    void jsonQueryToolRegistered() {
        assertThat(toolNames()).contains("json_query");
    }

    @Test
    void httpToolRegistered() {
        assertThat(toolNames()).contains("http_request");
    }

    @Test
    void batchReadToolRegistered() {
        assertThat(toolNames()).contains("batch_read");
    }

    @Test
    void batchWriteToolRegistered() {
        assertThat(toolNames()).contains("batch_write");
    }

    @Test
    void patchApplyToolRegistered() {
        assertThat(toolNames()).contains("patch_apply");
    }

    @Test
    void searchReplaceToolRegistered() {
        assertThat(toolNames()).contains("search_replace");
    }

    @Test
    void mvnToolRegistered() {
        assertThat(toolNames()).contains("mvn");
    }

    @Test
    void githubToolRegistered() {
        assertThat(toolNames()).contains("github");
    }

    @Test
    void webSearchToolRegisteredWhenEnvSet() {
        // TAVILY_API_KEY is not set in this test env, so WebSearchTool should NOT be registered
        String env = System.getenv("TAVILY_API_KEY");
        List<String> names = toolNames();
        if (env != null && !env.isBlank()) {
            assertThat(names).contains("web_search");
        } else {
            assertThat(names).doesNotContain("web_search");
        }
    }

    @Test
    void factoryCreationWithAllToolsDoesNotThrow() {
        CodeAgentConfig config = minimalConfig();
        assertThatCode(() -> CodeAgentFactory.create(config, new StubModelProvider()))
                .doesNotThrowAnyException();
    }
}
