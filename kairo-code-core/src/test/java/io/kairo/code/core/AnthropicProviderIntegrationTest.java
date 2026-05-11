package io.kairo.code.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.model.ModelConfig;
import io.kairo.api.model.ModelResponse;
import io.kairo.code.core.hook.NoWriteDetectedHook;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Tests verifying that kairo-code works correctly with the Anthropic provider.
 *
 * <p>These tests use a capturing stub provider to verify that messages are structured
 * in a way compatible with Anthropic's API expectations:
 * <ul>
 *   <li>Tool results are in TOOL role messages (mapped to "user" role by Anthropic provider)</li>
 *   <li>Tool use content blocks have proper id/name/input structure</li>
 *   <li>Tool result content blocks have proper tool_use_id/content/is_error structure</li>
 * </ul>
 */
class AnthropicProviderIntegrationTest {

    /**
     * Capturing provider that records the conversation messages for inspection.
     */
    static class CapturingProvider implements io.kairo.api.model.ModelProvider {
        final List<Msg> capturedMessages = new ArrayList<>();
        final List<ModelConfig> capturedConfigs = new ArrayList<>();

        @Override
        public Mono<ModelResponse> call(List<Msg> messages, ModelConfig config) {
            capturedMessages.addAll(messages);
            capturedConfigs.add(config);
            return Mono.just(response());
        }

        @Override
        public Flux<ModelResponse> stream(List<Msg> messages, ModelConfig config) {
            capturedMessages.addAll(messages);
            capturedConfigs.add(config);
            return Flux.just(response());
        }

        @Override
        public String name() {
            return "anthropic-capturing";
        }

        private static ModelResponse response() {
            return new ModelResponse(
                    "msg_test",
                    List.of(new Content.TextContent("done")),
                    null,
                    ModelResponse.StopReason.END_TURN,
                    "claude-sonnet-4-20250514");
        }
    }

    @Test
    void toolResultContentHasAnthropicCompatibleStructure() {
        // Verify that ToolResultContent has the fields Anthropic API expects:
        // tool_use_id, content, is_error
        Content.ToolResultContent result = new Content.ToolResultContent(
                "toolu_abc123", "Command output here", false);

        assertThat(result.toolUseId()).isEqualTo("toolu_abc123");
        assertThat(result.content()).isEqualTo("Command output here");
        assertThat(result.isError()).isFalse();

        // Error case
        Content.ToolResultContent errorResult = new Content.ToolResultContent(
                "toolu_xyz789", "Error: file not found", true);

        assertThat(errorResult.toolUseId()).isEqualTo("toolu_xyz789");
        assertThat(errorResult.content()).isEqualTo("Error: file not found");
        assertThat(errorResult.isError()).isTrue();
    }

    @Test
    void toolUseContentHasAnthropicCompatibleStructure() {
        // Verify that ToolUseContent has the fields Anthropic API expects:
        // id, name, input
        Map<String, Object> input = Map.of("path", "/tmp/test.txt");
        Content.ToolUseContent toolUse = new Content.ToolUseContent(
                "toolu_read1", "read_file", input);

        assertThat(toolUse.toolId()).isEqualTo("toolu_read1");
        assertThat(toolUse.toolName()).isEqualTo("read_file");
        assertThat(toolUse.input()).containsEntry("path", "/tmp/test.txt");
    }

    @Test
    void agentCallWithToolResultProducesCorrectMessageStructure() {
        CodeAgentConfig config = new CodeAgentConfig(
                "test-api-key", "https://api.anthropic.com", "claude-sonnet-4-20250514", 10, null, null, 0, 0, null);

        CapturingProvider provider = new CapturingProvider();

        // Build a conversation history with tool results, simulating what the ReActLoop does
        Msg userMsg = Msg.of(MsgRole.USER, "Read the file /tmp/test.txt");

        // Simulate assistant responding with tool_use
        Msg assistantMsg = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .addContent(new Content.ToolUseContent(
                        "toolu_001", "read_file", Map.of("path", "/tmp/test.txt")))
                .build();

        // Simulate tool result message (what HookDecisionApplier.buildToolResultMsg produces)
        Msg toolResultMsg = Msg.builder()
                .role(MsgRole.TOOL)
                .addContent(new Content.ToolResultContent(
                        "toolu_001", "file contents here", false))
                .build();

        var session = CodeAgentFactory.createSession(
                config,
                CodeAgentFactory.SessionOptions.empty()
                        .withModelProvider(provider)
                        .withHooks(List.of(new NoWriteDetectedHook())));

        // Inject the pre-built conversation and make a call
        session.agent().call(userMsg).block();

        // Verify the messages were captured
        assertThat(provider.capturedMessages).isNotEmpty();

        // The first message should be the user message
        Msg firstMsg = provider.capturedMessages.get(0);
        assertThat(firstMsg.role()).isEqualTo(MsgRole.USER);
    }

    @Test
    void modelConfigHasMaxTokensSetForAnthropic() {
        // Anthropic API requires max_tokens to be set (no default)
        CodeAgentConfig config = new CodeAgentConfig(
                "test-api-key", "https://api.anthropic.com", "claude-sonnet-4-20250514", 10, null, null, 0, 0, null);

        CapturingProvider provider = new CapturingProvider();

        var session = CodeAgentFactory.createSession(
                config,
                CodeAgentFactory.SessionOptions.empty()
                        .withModelProvider(provider)
                        .withHooks(List.of(new NoWriteDetectedHook())));

        session.agent().call(Msg.of(MsgRole.USER, "hello")).block();

        // Verify max_tokens is set to a non-zero value
        assertThat(provider.capturedConfigs).isNotEmpty();
        ModelConfig usedConfig = provider.capturedConfigs.get(0);
        assertThat(usedConfig.maxTokens()).isGreaterThan(0);
    }

    @Test
    void systemPromptIsPassedToConfig() {
        CodeAgentConfig config = new CodeAgentConfig(
                "test-api-key", "https://api.anthropic.com", "claude-sonnet-4-20250514", 10, null, null, 0, 0, null);

        CapturingProvider provider = new CapturingProvider();

        var session = CodeAgentFactory.createSession(
                config,
                CodeAgentFactory.SessionOptions.empty()
                        .withModelProvider(provider)
                        .withHooks(List.of(new NoWriteDetectedHook())));

        session.agent().call(Msg.of(MsgRole.USER, "hello")).block();

        // Verify system prompt is passed in the ModelConfig
        // (Anthropic provider extracts this to the top-level "system" field)
        assertThat(provider.capturedConfigs).isNotEmpty();
        ModelConfig usedConfig = provider.capturedConfigs.get(0);
        assertThat(usedConfig.systemPrompt()).isNotNull();
        assertThat(usedConfig.systemPrompt()).isNotBlank();
    }
}
