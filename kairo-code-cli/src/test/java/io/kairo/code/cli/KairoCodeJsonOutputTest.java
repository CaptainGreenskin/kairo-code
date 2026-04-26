package io.kairo.code.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests for JSON output format produced by {@link KairoCodeMain#buildJsonOutput}. */
class KairoCodeJsonOutputTest {

    @Test
    void outputIsValidJsonWithAllFields() {
        String json = KairoCodeMain.buildJsonOutput("Hello world", 3, 1500, 0);

        assertThat(json).contains("\"response\"");
        assertThat(json).contains("\"iterations\"");
        assertThat(json).contains("\"total_tokens\"");
        assertThat(json).contains("\"exit_code\"");
    }

    @Test
    void fieldValuesAreCorrect() {
        String json = KairoCodeMain.buildJsonOutput("Task complete", 7, 24000, 0);

        assertThat(json).contains("\"Task complete\"");
        assertThat(json).contains("\"iterations\": 7");
        assertThat(json).contains("\"total_tokens\": 24000");
        assertThat(json).contains("\"exit_code\": 0");
    }

    @Test
    void specialCharactersInResponseAreEscaped() {
        String json = KairoCodeMain.buildJsonOutput("Say \"hello\"\nNew line\tTab", 1, 100, 0);

        assertThat(json).contains("\\\"hello\\\"");
        assertThat(json).contains("\\n");
        assertThat(json).contains("\\t");
    }

    @Test
    void backslashInResponseIsEscaped() {
        String json = KairoCodeMain.buildJsonOutput("path\\to\\file", 1, 50, 0);

        assertThat(json).contains("path\\\\to\\\\file");
    }

    @Test
    void outputStructureStartsAndEndsWithBraces() {
        String json = KairoCodeMain.buildJsonOutput("ok", 0, 0, 0);

        assertThat(json.trim()).startsWith("{");
        assertThat(json.trim()).endsWith("}");
    }
}
