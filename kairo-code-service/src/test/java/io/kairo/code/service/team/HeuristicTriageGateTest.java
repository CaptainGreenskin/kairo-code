package io.kairo.code.service.team;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link HeuristicTriageGate} triage rules.
 */
class HeuristicTriageGateTest {

    // Use default keywords (empty override)
    private final HeuristicTriageGate gate = new HeuristicTriageGate("");

    // ── Short messages (< 20 chars) → always false ──

    @ParameterizedTest
    @ValueSource(strings = {"hi", "ok", "你好", "help", "yes", "no"})
    void shortMessage_alwaysFalse(String msg) {
        assertThat(gate.shouldFanOut(msg)).isFalse();
    }

    @Test
    void shortMessage_withKeyword_stillFalse() {
        // "plan" is a keyword but length < 20
        assertThat(gate.shouldFanOut("plan")).isFalse();
        assertThat(gate.shouldFanOut("refactor")).isFalse();
    }

    // ── Long messages (> 120 chars) → always true ──

    @Test
    void longMessage_alwaysTrue() {
        String longMsg = "a".repeat(121);
        assertThat(gate.shouldFanOut(longMsg)).isTrue();
    }

    @Test
    void longMessage_noKeywords_stillTrue() {
        String longMsg = "what is the weather like today in this particular city because I want to " +
                "know if I need an umbrella when I go to the park this afternoon for exercise";
        assertThat(longMsg.length()).isGreaterThan(120);
        assertThat(gate.shouldFanOut(longMsg)).isTrue();
    }

    // ── Keyword matches (case-insensitive) ──

    @Test
    void keywordMatch_refactor_returnsTrue() {
        assertThat(gate.shouldFanOut("refactor the module code")).isTrue();
    }

    @Test
    void keywordMatch_caseInsensitive() {
        assertThat(gate.shouldFanOut("REFACTOR the service layer")).isTrue();
        assertThat(gate.shouldFanOut("Please REVIEW this file")).isTrue();
    }

    @Test
    void keywordMatch_chinese_returnsTrue() {
        assertThat(gate.shouldFanOut("重构 ReasoningPhase 逻辑")).isTrue();
    }

    @Test
    void keywordMatch_implement() {
        assertThat(gate.shouldFanOut("implement a new login endpoint")).isTrue();
    }

    @Test
    void keywordMatch_design() {
        assertThat(gate.shouldFanOut("design the database schema")).isTrue();
    }

    @Test
    void keywordMatch_testCoverage() {
        assertThat(gate.shouldFanOut("improve test coverage for auth module")).isTrue();
    }

    // ── No match, medium length ──

    @Test
    void noMatch_mediumLength_returnsFalse() {
        String msg = "what is the weather like today in this city";
        assertThat(msg.length()).isGreaterThanOrEqualTo(20);
        assertThat(msg.length()).isLessThanOrEqualTo(120);
        assertThat(gate.shouldFanOut(msg)).isFalse();
    }

    @Test
    void noMatch_general_question() {
        assertThat(gate.shouldFanOut("explain how this class works")).isFalse();
    }

    // ── Edge cases ──

    @Test
    void exactly20chars_noKeyword_returnsFalse() {
        // Exactly 20 chars, no keyword → false (20 is NOT < 20)
        String msg = "12345678901234567890"; // 20 chars
        assertThat(msg.length()).isEqualTo(20);
        assertThat(gate.shouldFanOut(msg)).isFalse();
    }

    @Test
    void exactly120chars_noKeyword_returnsFalse() {
        // Exactly 120 chars, no keyword → false (120 is NOT > 120)
        String msg = "x".repeat(120);
        assertThat(msg.length()).isEqualTo(120);
        assertThat(gate.shouldFanOut(msg)).isFalse();
    }

    @Test
    void nullInput_returnsFalse() {
        assertThat(gate.shouldFanOut(null)).isFalse();
    }

    @Test
    void blankInput_returnsFalse() {
        assertThat(gate.shouldFanOut("")).isFalse();
        assertThat(gate.shouldFanOut("   ")).isFalse();
    }

    // ── Custom keywords override ──

    @Test
    void customKeywords_override() {
        HeuristicTriageGate custom = new HeuristicTriageGate("deploy,migrate");
        // "deploy" is a custom keyword
        assertThat(custom.shouldFanOut("deploy the application now")).isTrue();
        // "refactor" is NOT in the custom set
        assertThat(custom.shouldFanOut("refactor the module code")).isFalse();
    }
}
