package io.kairo.code.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.model.ModelConfig;
import io.kairo.api.model.ModelProvider;
import io.kairo.api.model.ModelResponse;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Verifies that kairo-code has the tool capabilities required for M5 self-modification tasks.
 */
class SelfModificationCapabilityTest {

    private static final CodeAgentConfig CONFIG =
            new CodeAgentConfig("test-key", "https://api.openai.com", "gpt-4o", 50, "/tmp", null, 0, 0, null);

    @Test
    void bashToolIsRegistered() {
        CodeAgentSession session = CodeAgentFactory.createSession(CONFIG, sessionOptions());
        Set<String> toolNames = registeredToolNames(session);

        assertThat(toolNames).contains("bash");
    }

    @Test
    void readToolIsRegistered() {
        CodeAgentSession session = CodeAgentFactory.createSession(CONFIG, sessionOptions());

        assertThat(registeredToolNames(session)).contains("read");
    }

    @Test
    void editToolIsRegistered() {
        CodeAgentSession session = CodeAgentFactory.createSession(CONFIG, sessionOptions());

        assertThat(registeredToolNames(session)).contains("edit");
    }

    @Test
    void writeToolIsRegistered() {
        CodeAgentSession session = CodeAgentFactory.createSession(CONFIG, sessionOptions());

        assertThat(registeredToolNames(session)).contains("write");
    }

    @Test
    void globToolIsRegistered() {
        CodeAgentSession session = CodeAgentFactory.createSession(CONFIG, sessionOptions());

        assertThat(registeredToolNames(session)).contains("glob");
    }

    @Test
    void grepToolIsRegistered() {
        CodeAgentSession session = CodeAgentFactory.createSession(CONFIG, sessionOptions());

        assertThat(registeredToolNames(session)).contains("grep");
    }

    @Test
    void allSelfModificationToolsPresent() {
        CodeAgentSession session = CodeAgentFactory.createSession(CONFIG, sessionOptions());
        Set<String> toolNames = registeredToolNames(session);

        assertThat(toolNames).containsAll(Set.of("bash", "read", "write", "edit", "glob", "grep"));
    }

    @Test
    void systemPromptContainsSelfModificationGuide() {
        // Verify the system prompt resource is non-empty and includes self-modification guidance
        String systemPrompt = loadSystemPrompt();

        assertThat(systemPrompt).isNotBlank();
        assertThat(systemPrompt).containsIgnoringCase("self-modification")
                .as("system-prompt.md should include kairo-code self-modification guide");
    }

    @Test
    void systemPromptContainsMvnTestCommand() {
        String systemPrompt = loadSystemPrompt();

        assertThat(systemPrompt).contains("mvn test")
                .as("system-prompt.md should include mvn test command for verification");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static CodeAgentFactory.SessionOptions sessionOptions() {
        return CodeAgentFactory.SessionOptions.empty()
                .withModelProvider(new StubModelProvider());
    }

    private static Set<String> registeredToolNames(CodeAgentSession session) {
        return session.toolRegistry().getAll().stream()
                .map(def -> def.name())
                .collect(Collectors.toSet());
    }

    private static String loadSystemPrompt() {
        try (var stream = SelfModificationCapabilityTest.class
                .getClassLoader().getResourceAsStream("system-prompt.md")) {
            if (stream == null) {
                return "";
            }
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return "";
        }
    }

    static class StubModelProvider implements ModelProvider {
        @Override
        public Mono<ModelResponse> call(List<Msg> messages, ModelConfig config) {
            return Mono.just(new ModelResponse(
                    "stub-id", List.of(new Content.TextContent("done")), null, null, "stub"));
        }

        @Override
        public Flux<ModelResponse> stream(List<Msg> messages, ModelConfig config) {
            return Flux.just(new ModelResponse(
                    "stub-id", List.of(new Content.TextContent("done")), null, null, "stub"));
        }

        @Override
        public String name() {
            return "stub";
        }
    }
}
