package io.kairo.code.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlanStepParserTest {

    @Test
    void parsesCheckboxFormat() {
        String text = "## Plan\n- [ ] Step one\n- [ ] Step two\n- [x] Step three";
        var steps = PlanStepParser.parse(text);
        assertThat(steps).hasSize(3);
        assertThat(steps.get(0)).isEqualTo("Step one");
    }

    @Test
    void parsesNumberedList() {
        String text = "I'll proceed with:\n1. Install dependencies\n2. Run tests\n3. Deploy";
        var steps = PlanStepParser.parse(text);
        assertThat(steps).hasSize(3);
        assertThat(steps.get(1)).isEqualTo("Run tests");
    }

    @Test
    void returnsEmptyForSingleItem() {
        assertThat(PlanStepParser.parse("1. Only one step")).isEmpty();
    }

    @Test
    void returnsEmptyForPlainText() {
        assertThat(PlanStepParser.parse("This is just normal text.")).isEmpty();
    }

    @Test
    void looksLikeDone_detectsKeywords() {
        assertThat(PlanStepParser.looksLikeDone("Build completed ✅")).isTrue();
        assertThat(PlanStepParser.looksLikeDone("Tests done")).isTrue();
        assertThat(PlanStepParser.looksLikeDone("Starting...")).isFalse();
    }

    @Test
    void returnsEmptyForNull() {
        assertThat(PlanStepParser.parse(null)).isEmpty();
    }

    @Test
    void returnsEmptyForBlank() {
        assertThat(PlanStepParser.parse("   ")).isEmpty();
    }

    @Test
    void capsAtMaxSteps() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 25; i++) {
            sb.append(i).append(". Step ").append(i).append("\n");
        }
        var steps = PlanStepParser.parse(sb.toString());
        assertThat(steps).hasSize(20);
    }
}
