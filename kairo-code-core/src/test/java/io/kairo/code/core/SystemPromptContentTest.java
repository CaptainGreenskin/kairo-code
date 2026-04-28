package io.kairo.code.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the system prompt contains the execution discipline constraints
 * borrowed from Claude Code to prevent "plan but not execute" behavior.
 */
class SystemPromptContentTest {

    private static final String SYSTEM_PROMPT = loadSystemPrompt();

    @Test
    void executionDisciplineSection_exists() {
        assertThat(SYSTEM_PROMPT).contains("## Execution Discipline");
    }

    @Test
    void executionDiscipline_immediatelyInvestigate() {
        assertThat(SYSTEM_PROMPT)
                .contains("immediately use tools to investigate");
    }

    @Test
    void executionDiscipline_noColonBeforeToolCalls() {
        assertThat(SYSTEM_PROMPT)
                .contains("Do not use a colon before tool calls");
    }

    @Test
    void executionDiscipline_noNarrateThoughtProcess() {
        assertThat(SYSTEM_PROMPT)
                .contains("Do not narrate your thought process");
    }

    @Test
    void executionDiscipline_todoListIsStartNotEnd() {
        assertThat(SYSTEM_PROMPT)
                .contains("Writing a todo list is the **start** of work, not the end");
    }

    @Test
    void executionDiscipline_markTodosImmediately() {
        assertThat(SYSTEM_PROMPT)
                .contains("Mark each todo as completed as soon as you finish it");
    }

    @Test
    void executionDiscipline_neverEndWithOnlyPlan() {
        assertThat(SYSTEM_PROMPT)
                .contains("Never end a response with only a plan or todo list");
    }

    @Test
    void todoWriteToolDescription_warnsAgainstSubstituteForWork() {
        assertThat(SYSTEM_PROMPT)
                .contains("substitute for doing the actual work");
    }

    private static String loadSystemPrompt() {
        try (InputStream is = CodeAgentFactory.class
                .getClassLoader()
                .getResourceAsStream("system-prompt.md")) {
            assertThat(is).as("system-prompt.md must exist on classpath").isNotNull();
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("Failed to load system-prompt.md", e);
        }
    }
}
