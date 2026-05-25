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
import io.kairo.code.core.team.SwarmCoordinator;
import io.kairo.expertteam.ExpertTeamCoordinator;
import io.kairo.expertteam.SimpleEvaluationStrategy;
import io.kairo.expertteam.internal.DefaultPlanner;
import io.kairo.expertteam.role.ExpertRoleRegistry;
import io.kairo.expertteam.tck.NoopMessageBus;
import io.kairo.expertteam.tck.StubAgent;
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
        // Baseline tools that the agent expects to always be present. Writes/edits
        // are exposed under their actual registered names: batch_write covers full-file
        // writes, search_replace / patch_apply cover edits.
        assertThat(names).contains("bash", "read", "batch_write", "search_replace", "patch_apply",
                "grep", "glob", "web_fetch", "git", "ask_user", "todo_read", "todo_write", "tree");
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

    // --- expert_team tool wiring (M-Expert-Wiring) ---
    // The model-facing tool is only registered when a SwarmCoordinator is provided;
    // baseline sessions (no team infra) must not see it. Child sessions never see it
    // either — same rationale as TaskTool (no recursive team spawning).

    private static SwarmCoordinator newSwarmCoordinator() {
        ExpertRoleRegistry roleRegistry = new ExpertRoleRegistry();
        DefaultPlanner planner = new DefaultPlanner(roleRegistry, null, null);
        ExpertTeamCoordinator expertCoordinator = new ExpertTeamCoordinator(
                null, new SimpleEvaluationStrategy(), null, planner, roleRegistry);
        return new SwarmCoordinator(
                expertCoordinator, roleRegistry, new NoopMessageBus(),
                List.of(StubAgent.fixed("worker", "done")));
    }

    @Test
    void expertTeamToolNotRegisteredByDefault() {
        assertThat(toolNames()).doesNotContain("expert_team");
    }

    @Test
    void expertTeamToolRegisteredWhenSwarmCoordinatorWired() {
        var session = CodeAgentFactory.createSession(minimalConfig(),
                CodeAgentFactory.SessionOptions.empty()
                        .withModelProvider(new StubModelProvider())
                        .withSwarmCoordinator(newSwarmCoordinator()));
        List<String> names = session.toolRegistry().getAll().stream()
                .map(io.kairo.api.tool.ToolDefinition::name)
                .toList();
        assertThat(names).contains("expert_team");
    }

    @Test
    void expertTeamToolNotRegisteredInChildSession() {
        // Child sessions must not get expert_team even when a coordinator is present —
        // recursive team spawning is out of scope (mirrors TaskTool's child-session guard).
        var session = CodeAgentFactory.createSession(minimalConfig(),
                CodeAgentFactory.SessionOptions.empty()
                        .withModelProvider(new StubModelProvider())
                        .withSwarmCoordinator(newSwarmCoordinator())
                        .asChildSession());
        List<String> names = session.toolRegistry().getAll().stream()
                .map(io.kairo.api.tool.ToolDefinition::name)
                .toList();
        assertThat(names).doesNotContain("expert_team");
    }
}
