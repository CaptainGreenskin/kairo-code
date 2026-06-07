package io.kairo.code.core.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WorkflowMetaParserTest {

    @Test
    void parsesBasicMeta() {
        String script = """
                export const meta = {
                  name: 'find-bugs',
                  description: 'Find and verify bugs',
                  phases: [
                    { title: 'Find', detail: 'scan source' },
                    { title: 'Verify', detail: 'adversarial check' }
                  ]
                }

                phase('Find')
                const r = await agent('hello')
                return r
                """;
        WorkflowMeta meta = WorkflowMetaParser.parse(script);
        assertThat(meta.name()).isEqualTo("find-bugs");
        assertThat(meta.description()).isEqualTo("Find and verify bugs");
        assertThat(meta.phases()).hasSize(2);
        assertThat(meta.phases().get(0).title()).isEqualTo("Find");
        assertThat(meta.phases().get(1).detail()).isEqualTo("adversarial check");
    }

    @Test
    void parsesDoubleQuotedMeta() {
        String script = """
                export const meta = {
                  "name": "review-code",
                  "description": "Code review workflow"
                }
                return 'done'
                """;
        WorkflowMeta meta = WorkflowMetaParser.parse(script);
        assertThat(meta.name()).isEqualTo("review-code");
    }

    @Test
    void extractsScriptBody() {
        String script = """
                export const meta = { name: 'test', description: 'x' }

                const result = await agent('prompt')
                return result
                """;
        String body = WorkflowMetaParser.extractScriptBody(script);
        assertThat(body).startsWith("const result");
        assertThat(body).contains("return result");
        assertThat(body).doesNotContain("export const meta");
    }

    @Test
    void throwsOnMissingMeta() {
        assertThatThrownBy(() -> WorkflowMetaParser.parse("const x = 1;"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("export const meta");
    }

    @Test
    void handlesNestedBraces() {
        String script = """
                export const meta = {
                  name: 'nested',
                  description: 'has { braces } inside'
                }
                return {}
                """;
        WorkflowMeta meta = WorkflowMetaParser.parse(script);
        assertThat(meta.name()).isEqualTo("nested");
    }

    @Test
    void normalizeHandlesTrailingCommas() {
        String json = "{ name: 'test', phases: [{ title: 'A', },], }";
        String normalized = WorkflowMetaParser.normalizeToJson(json);
        assertThat(normalized).doesNotContain(",]");
        assertThat(normalized).doesNotContain(",}");
    }
}
