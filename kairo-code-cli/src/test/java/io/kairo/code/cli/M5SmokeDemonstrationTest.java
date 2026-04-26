package io.kairo.code.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentFactory;
import io.kairo.code.core.CodeAgentSession;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * M5 milestone demonstration tests.
 *
 * <p>Verifies (without making real LLM calls) that kairo-code has the infrastructure
 * needed to modify its own source code: correct tools registered, system prompt in
 * place, and session creation functional.
 */
class M5SmokeDemonstrationTest {

    private static final String FAKE_KEY = "sk-test-m5-smoke";

    private static CodeAgentSession session() {
        CodeAgentConfig config =
                new CodeAgentConfig(FAKE_KEY, "https://api.openai.com", "gpt-4o", 10, ".");
        return CodeAgentFactory.createSession(config, CodeAgentFactory.SessionOptions.empty());
    }

    @Test
    void sessionCreatesSuccessfully() {
        CodeAgentSession s = session();
        assertThat(s).isNotNull();
        assertThat(s.agent()).isNotNull();
    }

    @Test
    void selfModificationToolsPresent() {
        CodeAgentSession s = session();
        var toolNames =
                s.toolRegistry().getAll().stream()
                        .map(t -> t.name())
                        .toList();
        assertThat(toolNames).containsAll(java.util.List.of("bash", "read", "write", "edit"));
    }

    @Test
    void systemPromptContainsSelfModificationGuide() throws Exception {
        InputStream in =
                getClass().getClassLoader().getResourceAsStream("system-prompt.md");
        assertThat(in).isNotNull();
        String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        assertThat(content).contains("mvn test");
        assertThat(content).contains("kairo-code");
    }
}
