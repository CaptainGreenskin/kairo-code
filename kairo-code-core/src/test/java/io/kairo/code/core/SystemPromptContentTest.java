package io.kairo.code.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the system prompt contains the execution discipline constraints
 * borrowed from Claude Code to prevent "plan but not execute" behavior.
 *
 * <p>Also covers the provider-specific variants {@code system-prompt-claude.md}
 * and {@code system-prompt-glm.md}.
 */
class SystemPromptContentTest {

    private static final String SYSTEM_PROMPT = loadResource("system-prompt.md");
    private static final String CLAUDE_PROMPT = loadResource("system-prompt-claude.md");
    private static final String GLM_PROMPT = loadResource("system-prompt-glm.md");

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

    @Test
    void executionDiscipline_preferDedicatedToolsOverBash() {
        assertThat(SYSTEM_PROMPT)
                .contains("Prefer dedicated tools over bash");
    }

    @Test
    void executionDiscipline_mustUseWriteEditTools() {
        assertThat(SYSTEM_PROMPT)
                .contains("You MUST use `write` or `edit` tools to create and modify files");
    }

    @Test
    void executionDiscipline_noBashForFileWrites() {
        assertThat(SYSTEM_PROMPT)
                .contains("Never use `bash` with `echo >`, `cat >`, `tee`");
    }

    @Test
    void executionDiscipline_parallelToolCalls() {
        assertThat(SYSTEM_PROMPT)
                .contains("Parallel tool calls");
    }

    @Test
    void executionDiscipline_terseOutput() {
        assertThat(SYSTEM_PROMPT)
                .contains("Terse output");
        assertThat(SYSTEM_PROMPT)
                .contains("≤ 25 words");
    }

    @Test
    void executionDiscipline_matchResponseToTask() {
        assertThat(SYSTEM_PROMPT)
                .contains("Match response to task");
    }

    @Test
    void readEfficiency_sectionExists() {
        assertThat(SYSTEM_PROMPT)
                .contains("## Read Efficiency");
    }

    @Test
    void readEfficiency_over200LinesConstraint() {
        assertThat(SYSTEM_PROMPT)
                .contains("over 200 lines");
    }

    @Test
    void readEfficiency_preferGrepOverReading() {
        assertThat(SYSTEM_PROMPT)
                .contains("Prefer `grep` over reading");
    }

    @Test
    void editToolDiscipline_sectionExists() {
        assertThat(SYSTEM_PROMPT)
                .contains("## Edit Tool Discipline");
    }

    @Test
    void editToolDiscipline_alwaysReadBeforeEditing() {
        assertThat(SYSTEM_PROMPT)
                .contains("Always read before editing");
    }

    @Test
    void editToolDiscipline_exactMatchRequired() {
        assertThat(SYSTEM_PROMPT)
                .contains("Exact match required");
    }

    @Test
    void claudePrompt_existsAndIsNonEmpty() {
        assertThat(CLAUDE_PROMPT).isNotBlank();
    }

    @Test
    void claudePrompt_emphasizesParallelToolCalls() {
        assertThat(CLAUDE_PROMPT)
                .contains("parallel")
                .contains("tool_use");
    }

    @Test
    void claudePrompt_hasExplorationSection() {
        assertThat(CLAUDE_PROMPT).contains("## Exploration");
    }

    @Test
    void claudePrompt_hasImplementationSection() {
        assertThat(CLAUDE_PROMPT).contains("## Implementation");
    }

    @Test
    void claudePrompt_hasVerificationSection() {
        assertThat(CLAUDE_PROMPT).contains("## Verification");
    }

    @Test
    void claudePrompt_recommendsMvnTestForIteration() {
        assertThat(CLAUDE_PROMPT).contains("mvn test");
    }

    @Test
    void claudePrompt_dropsBashWriteWarning() {
        // GLM-specific corrective rule that should not appear in the Claude variant.
        assertThat(CLAUDE_PROMPT)
                .doesNotContain("You MUST use `write` or `edit` tools to create and modify files");
        assertThat(CLAUDE_PROMPT)
                .doesNotContain("Never use `bash` with `echo >`");
    }

    @Test
    void glmPrompt_existsAndKeepsBashWriteWarning() {
        assertThat(GLM_PROMPT).isNotBlank();
        assertThat(GLM_PROMPT)
                .contains("You MUST use `write` or `edit` tools to create and modify files");
        assertThat(GLM_PROMPT).contains("Never use `bash` with `echo >`");
    }

    @Test
    void glmPrompt_hasExecutionDisciplineSection() {
        assertThat(GLM_PROMPT).contains("Execution Discipline");
    }

    private static String loadResource(String name) {
        try (InputStream is = CodeAgentFactory.class
                .getClassLoader()
                .getResourceAsStream(name)) {
            assertThat(is).as("%s must exist on classpath", name).isNotNull();
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("Failed to load " + name, e);
        }
    }
}
