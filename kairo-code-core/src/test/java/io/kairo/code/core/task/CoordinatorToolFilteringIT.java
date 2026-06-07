package io.kairo.code.core.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.kairo.api.tool.ToolDefinition;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentFactory;
import io.kairo.code.core.CodeAgentFactory.SessionOptions;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * E2E integration test for the COORDINATOR agent type tool filtering.
 * Verifies that the hard tool filtering in {@link CodeAgentFactory} actually
 * removes write tools from the registry when AgentType.COORDINATOR is set.
 *
 * <p>The GLM call test requires KAIRO_CODE_API_KEY to be set.
 */
class CoordinatorToolFilteringIT {

    private static final CodeAgentConfig GLM_CONFIG = new CodeAgentConfig(
            System.getProperty("kairo.test.api-key",
                    System.getenv("KAIRO_CODE_API_KEY") != null
                            ? System.getenv("KAIRO_CODE_API_KEY") : "test-key"),
            System.getProperty("kairo.test.base-url",
                    System.getenv("KAIRO_BASE_URL") != null
                            ? System.getenv("KAIRO_BASE_URL") : "https://open.bigmodel.cn/api/coding/paas/v4"),
            "glm-5.1", 5, null, null, 0, 0, null);

    @Test
    void coordinatorSession_registryOnlyContainsAllowedTools() {
        SessionOptions opts = SessionOptions.empty()
                .withModelProvider(new StubModelProvider())
                .withAgentType(AgentType.COORDINATOR)
                .asChildSession();

        CodeAgentSession session = CodeAgentFactory.createSession(GLM_CONFIG, opts);

        Set<String> registeredTools = session.toolRegistry().getAll().stream()
                .map(ToolDefinition::name)
                .collect(Collectors.toSet());

        Set<String> expectedAllowed = AgentType.COORDINATOR.allowedTools();

        // All registered tools must be in the allowed set
        for (String tool : registeredTools) {
            assertThat(expectedAllowed).as("Tool '%s' should be in COORDINATOR allowed set", tool)
                    .contains(tool);
        }

        // Write tools must NOT be present
        assertThat(registeredTools).doesNotContain("write", "edit", "patch_apply",
                "search_replace", "batch_write");

        // Read tools MUST be present
        assertThat(registeredTools).contains("read", "bash", "grep", "glob");

        System.out.println("[COORDINATOR] Registered tools: " + registeredTools);
        System.out.println("[COORDINATOR] Tool count: " + registeredTools.size()
                + " (filtered from full set)");
    }

    @Test
    void exploreSession_registryOnlyContainsReadTools() {
        SessionOptions opts = SessionOptions.empty()
                .withModelProvider(new StubModelProvider())
                .withAgentType(AgentType.EXPLORE)
                .asChildSession();

        CodeAgentSession session = CodeAgentFactory.createSession(GLM_CONFIG, opts);

        Set<String> registeredTools = session.toolRegistry().getAll().stream()
                .map(ToolDefinition::name)
                .collect(Collectors.toSet());

        assertThat(registeredTools).doesNotContain("write", "edit", "team_create",
                "send_message", "task");
        assertThat(registeredTools).contains("read", "bash", "grep");

        System.out.println("[EXPLORE] Registered tools: " + registeredTools);
    }

    @Test
    void generalPurposeSession_registryContainsAllTools() {
        SessionOptions opts = SessionOptions.empty()
                .withModelProvider(new StubModelProvider())
                .withAgentType(AgentType.GENERAL_PURPOSE)
                .asChildSession();

        CodeAgentSession session = CodeAgentFactory.createSession(GLM_CONFIG, opts);

        Set<String> registeredTools = session.toolRegistry().getAll().stream()
                .map(ToolDefinition::name)
                .collect(Collectors.toSet());

        // GENERAL_PURPOSE has null allowedTools = no AgentType filtering applied.
        // write/edit are still registered (child session only suppresses team/task deps).
        assertThat(registeredTools).contains("read", "bash", "grep");
        // More tools than coordinator or explore (no whitelist filter)
        assertThat(registeredTools.size()).isGreaterThan(
                AgentType.COORDINATOR.allowedTools().size());

        System.out.println("[GENERAL_PURPOSE] Registered tools (" + registeredTools.size()
                + "): " + registeredTools);
    }

    @Test
    void coordinatorWithGlm_canReadButNotWrite() {
        String apiKey = System.getenv("KAIRO_CODE_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(),
                "KAIRO_CODE_API_KEY not set; skipping live GLM test");

        SessionOptions opts = SessionOptions.empty()
                .withAgentType(AgentType.COORDINATOR)
                .asChildSession();

        CodeAgentSession session = CodeAgentFactory.createSession(GLM_CONFIG, opts);

        // Verify tool registry BEFORE calling the model
        Set<String> tools = session.toolRegistry().getAll().stream()
                .map(ToolDefinition::name)
                .collect(Collectors.toSet());
        assertThat(tools).contains("read", "bash")
                .doesNotContain("write", "edit");

        // Send a simple task to the coordinator
        Msg response = session.agent()
                .call(Msg.of(MsgRole.USER,
                        "读取当前目录下的 README.md 文件的前 5 行。如果文件不存在就说不存在。简短回答。"))
                .block();

        assertThat(response).isNotNull();
        System.out.println("[COORDINATOR+GLM] Response: " + response.text());
    }

    /** Stub for tests that don't need a real model. */
    static class StubModelProvider implements io.kairo.api.model.ModelProvider {
        @Override
        public reactor.core.publisher.Mono<io.kairo.api.model.ModelResponse> call(
                List<Msg> messages, io.kairo.api.model.ModelConfig config) {
            return reactor.core.publisher.Mono.just(new io.kairo.api.model.ModelResponse(
                    "stub", List.of(new io.kairo.api.message.Content.TextContent("stub")),
                    null, null, "stub"));
        }

        @Override
        public reactor.core.publisher.Flux<io.kairo.api.model.ModelResponse> stream(
                List<Msg> messages, io.kairo.api.model.ModelConfig config) {
            return reactor.core.publisher.Flux.from(call(messages, config));
        }

        @Override
        public String name() { return "stub"; }
    }
}
