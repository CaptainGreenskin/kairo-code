package io.kairo.code.core;

import static org.assertj.core.api.Assertions.assertThatCode;

import io.kairo.api.model.ModelConfig;
import io.kairo.api.model.ModelProvider;
import io.kairo.api.model.ModelResponse;
import io.kairo.api.skill.SkillCategory;
import io.kairo.api.skill.SkillDefinition;
import io.kairo.api.skill.SkillRegistry;
import io.kairo.code.core.CodeAgentFactory.SessionOptions;
import io.kairo.skill.DefaultSkillRegistry;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class SkillPlanToolRegistrationTest {

    /**
     * A stub ModelProvider that returns a fixed response — used for unit tests
     * that verify wiring without requiring a real LLM endpoint.
     */
    static class StubModelProvider implements ModelProvider {
        @Override
        public Mono<ModelResponse> call(List<io.kairo.api.message.Msg> messages, ModelConfig config) {
            List<io.kairo.api.message.Content> contents = List.of(new io.kairo.api.message.Content.TextContent("stub response"));
            return Mono.just(new ModelResponse("stub-id", contents, null, null, "stub-model"));
        }

        @Override
        public Flux<ModelResponse> stream(List<io.kairo.api.message.Msg> messages, ModelConfig config) {
            List<io.kairo.api.message.Content> contents = List.of(new io.kairo.api.message.Content.TextContent("stub response"));
            return Flux.just(new ModelResponse("stub-id", contents, null, null, "stub-model"));
        }

        @Override
        public String name() {
            return "stub";
        }
    }

    @Test
    void skillToolsRegisteredWhenSkillRegistryPresent() {
        SkillRegistry skillRegistry = new DefaultSkillRegistry();
        // Register a dummy skill
        skillRegistry.register(new SkillDefinition(
                "test-skill", "1.0.0", "do something", "do something instructions",
                List.of(), SkillCategory.GENERAL));

        CodeAgentConfig config = new CodeAgentConfig(
                "k", "https://api.openai.com", "gpt-4o", 10, "/tmp", null, 0, 0, null);
        SessionOptions opts = SessionOptions.empty()
                .withModelProvider(new StubModelProvider())
                .withSkills(skillRegistry, Set.of("test-skill"));

        assertThatCode(() -> CodeAgentFactory.createSession(config, opts))
                .doesNotThrowAnyException();
    }

    @Test
    void planModeToolsAlwaysRegistered() {
        CodeAgentConfig config = new CodeAgentConfig(
                "k", "https://api.openai.com", "gpt-4o", 10, null, null, 0, 0, null);
        assertThatCode(() -> CodeAgentFactory.create(config, new StubModelProvider()))
                .doesNotThrowAnyException();
    }

    @Test
    void skillAndPlanToolsAppearInRegistry() {
        SkillRegistry skillRegistry = new DefaultSkillRegistry();
        skillRegistry.register(new SkillDefinition(
                "test-skill", "1.0.0", "do something", "do something instructions",
                List.of(), SkillCategory.GENERAL));

        CodeAgentConfig config = new CodeAgentConfig(
                "k", "https://api.openai.com", "gpt-4o", 10, "/tmp", null, 0, 0, null);
        SessionOptions opts = SessionOptions.empty()
                .withModelProvider(new StubModelProvider())
                .withSkills(skillRegistry, Set.of("test-skill"));

        var session = CodeAgentFactory.createSession(config, opts);
        var toolNames = session.toolRegistry().getAll().stream()
                .map(io.kairo.api.tool.ToolDefinition::name)
                .toList();

        assertThatCode(() -> {
            org.assertj.core.api.Assertions.assertThat(toolNames).contains("skill_list");
            org.assertj.core.api.Assertions.assertThat(toolNames).contains("skill_load");
            org.assertj.core.api.Assertions.assertThat(toolNames).contains("skill_manage");
            org.assertj.core.api.Assertions.assertThat(toolNames).contains("enter_plan_mode");
            org.assertj.core.api.Assertions.assertThat(toolNames).contains("exit_plan_mode");
            org.assertj.core.api.Assertions.assertThat(toolNames).contains("list_plans");
        }).doesNotThrowAnyException();
    }
}
