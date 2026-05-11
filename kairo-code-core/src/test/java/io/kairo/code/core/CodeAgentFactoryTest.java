package io.kairo.code.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.kairo.api.agent.Agent;
import io.kairo.api.hook.ToolResultEvent;
import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.model.ModelConfig;
import io.kairo.api.model.ModelProvider;
import io.kairo.api.model.ModelResponse;
import io.kairo.api.tool.ToolResult;
import io.kairo.code.core.stats.ToolUsageTracker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
                "test-api-key", "https://api.openai.com", "gpt-4o", 50, null, null, 0, 0, null);

        Agent agent = CodeAgentFactory.create(config, new StubModelProvider());

        assertThat(agent).isNotNull();
    }

    @Test
    void agentHasCorrectName() {
        CodeAgentConfig config = new CodeAgentConfig(
                "test-api-key", "https://api.openai.com", "gpt-4o", 50, null, null, 0, 0, null);

        Agent agent = CodeAgentFactory.create(config, new StubModelProvider());

        assertThat(agent.name()).isEqualTo("kairo-code");
    }

    @Test
    void configDefaultsAreApplied() {
        CodeAgentConfig config = new CodeAgentConfig("test-key", null, null, 0, null, null, 0, 0, null);

        assertThat(config.baseUrl()).isEqualTo("https://api.openai.com");
        assertThat(config.modelName()).isEqualTo("gpt-4o");
        assertThat(config.maxIterations()).isEqualTo(50);
    }

    @Test
    void configRejectsBlankApiKey() {
        assertThatThrownBy(() -> new CodeAgentConfig("", null, null, 0, null, null, 0, 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("apiKey");
    }

    @Test
    void shouldRegisterExpandedToolSet() {
        CodeAgentConfig config = new CodeAgentConfig(
                "test-api-key", "https://api.openai.com", "gpt-4o", 50, null, null, 0, 0, null);

        var session = CodeAgentFactory.createSession(config,
                CodeAgentFactory.SessionOptions.empty().withModelProvider(new StubModelProvider()));

        var toolNames = session.toolRegistry().getAll().stream()
                .map(io.kairo.api.tool.ToolDefinition::name)
                .toList();

        assertThat(toolNames).contains("web_fetch");
        assertThat(toolNames).contains("git");
        assertThat(toolNames).contains("ask_user");
        assertThat(toolNames).contains("todo_read");
        assertThat(toolNames).contains("todo_write");
        assertThat(toolNames).contains("tree");
    }

    /**
     * Captures the system prompt the factory built by intercepting {@link ModelConfig#systemPrompt()}
     * on the first model call.
     */
    static class CapturingModelProvider implements ModelProvider {
        final AtomicReference<String> capturedSystemPrompt = new AtomicReference<>();

        @Override
        public Mono<ModelResponse> call(List<Msg> messages, ModelConfig config) {
            capture(config);
            return Mono.just(stubResponse());
        }

        @Override
        public Flux<ModelResponse> stream(List<Msg> messages, ModelConfig config) {
            capture(config);
            return Flux.just(stubResponse());
        }

        @Override
        public String name() {
            return "capturing";
        }

        private void capture(ModelConfig config) {
            if (config == null) return;
            capturedSystemPrompt.compareAndSet(null, config.systemPrompt());
        }

        private static ModelResponse stubResponse() {
            return new ModelResponse(
                    "stub-id",
                    List.of(new Content.TextContent("ok")),
                    null,
                    ModelResponse.StopReason.END_TURN,
                    "stub-model");
        }
    }

    @Test
    void withToolUsageTracker_injectsToolInsightsIntoSystemPrompt() {
        CodeAgentConfig config = new CodeAgentConfig(
                "test-api-key", "https://api.openai.com", "gpt-4o", 50, null, null, 0, 0, null);

        ToolUsageTracker tracker = new ToolUsageTracker();
        ToolResult ok = new ToolResult("id", "out", false, Map.of());
        tracker.onToolResult(new ToolResultEvent("bash", ok, Duration.ofMillis(120), true));
        tracker.onToolResult(new ToolResultEvent("bash", ok, Duration.ofMillis(120), true));

        CapturingModelProvider model = new CapturingModelProvider();
        var session = CodeAgentFactory.createSession(
                config,
                CodeAgentFactory.SessionOptions.empty()
                        .withModelProvider(model)
                        .withToolUsageTracker(tracker));

        session.agent().call(Msg.of(MsgRole.USER, "ping")).block();

        String prompt = model.capturedSystemPrompt.get();
        assertThat(prompt).isNotNull();
        assertThat(prompt).contains("## Tool Insights (this session)");
        assertThat(prompt).contains("- bash:");
        assertThat(prompt).contains("100.0% success");
    }

    @Test
    void withoutToolUsageTracker_systemPromptHasNoToolInsightsSection() {
        CodeAgentConfig config = new CodeAgentConfig(
                "test-api-key", "https://api.openai.com", "gpt-4o", 50, null, null, 0, 0, null);

        CapturingModelProvider model = new CapturingModelProvider();
        var session = CodeAgentFactory.createSession(
                config,
                CodeAgentFactory.SessionOptions.empty().withModelProvider(model));

        session.agent().call(Msg.of(MsgRole.USER, "ping")).block();

        String prompt = model.capturedSystemPrompt.get();
        assertThat(prompt).isNotNull();
        assertThat(prompt).doesNotContain("## Tool Insights");
    }

    @Test
    void nullMcpConfigCreatesSessionWithNullMcpRegistry() {
        CodeAgentConfig config = new CodeAgentConfig(
                "test-api-key", "https://api.openai.com", "gpt-4o", 50, null, null, 0, 0, null);

        var session = CodeAgentFactory.createSession(config,
                CodeAgentFactory.SessionOptions.empty().withModelProvider(new StubModelProvider()));

        assertThat(session).isNotNull();
        assertThat(session.mcpRegistry()).isNull();
    }

    @Test
    void systemPromptContainsSessionContextWithDate() {
        CodeAgentConfig config = new CodeAgentConfig(
                "test-api-key", "https://api.openai.com", "gpt-4o", 50,
                "/tmp", null, 0, 0, null);

        CapturingModelProvider model = new CapturingModelProvider();
        var session = CodeAgentFactory.createSession(
                config,
                CodeAgentFactory.SessionOptions.empty().withModelProvider(model));

        session.agent().call(Msg.of(MsgRole.USER, "ping")).block();

        String prompt = model.capturedSystemPrompt.get();
        assertThat(prompt).isNotNull();
        assertThat(prompt).contains("## Session Context");
        assertThat(prompt).contains("Date:");
    }

    @Test
    void systemPromptContainsGitStatusInGitRepo(@TempDir Path tempDir) throws IOException, InterruptedException {
        // Initialize a git repo and create an untracked file.
        Files.createFile(tempDir.resolve("README.md"));
        runGit(tempDir, "init");
        runGit(tempDir, "config", "user.email", "test@test.com");
        runGit(tempDir, "config", "user.name", "Test");

        CodeAgentConfig config = new CodeAgentConfig(
                "test-api-key", "https://api.openai.com", "gpt-4o", 50,
                tempDir.toString(), null, 0, 0, null);

        CapturingModelProvider model = new CapturingModelProvider();
        var session = CodeAgentFactory.createSession(
                config,
                CodeAgentFactory.SessionOptions.empty().withModelProvider(model));

        session.agent().call(Msg.of(MsgRole.USER, "ping")).block();

        String prompt = model.capturedSystemPrompt.get();
        assertThat(prompt).isNotNull();
        assertThat(prompt).contains("## Session Context");
        assertThat(prompt).contains("Working Tree Status");
        assertThat(prompt).contains("README.md");
    }

    private static void runGit(Path dir, String... args) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("git");
        for (String arg : args) pb.command().add(arg);
        pb.directory(dir.toFile());
        Process p = pb.start();
        p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
    }
}
