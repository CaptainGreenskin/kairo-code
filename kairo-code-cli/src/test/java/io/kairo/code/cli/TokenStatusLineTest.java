package io.kairo.code.cli;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TokenStatusLine}: color thresholds, formatK rendering,
 * compact-phase display, and zero-token startup behaviour.
 */
class TokenStatusLineTest {

    // ── 1. Zero tokens → empty string (no status at startup) ──

    @Test
    void zeroTokens_returnsEmpty() {
        assertEquals("", TokenStatusLine.format(0, 200_000, null));
    }

    // ── 2. Tokens < 80% of limit → gray color ──

    @Test
    void belowEightyPercent_grayColor() {
        // 50k / 200k = 25% → gray
        String result = TokenStatusLine.format(50_000, 200_000, null);
        assertTrue(result.startsWith(TokenStatusLine.GRAY), "Expected gray for <80%: " + result);
        assertTrue(result.contains("[tokens: 50k/200k]"), "Expected token counts: " + result);
    }

    // ── 3. Tokens 80–89% → yellow color ──

    @Test
    void eightyToEightyNinePercent_yellowColor() {
        // 160k / 200k = 80% → yellow
        String result = TokenStatusLine.format(160_000, 200_000, null);
        assertTrue(result.startsWith(TokenStatusLine.YELLOW), "Expected yellow for 80%: " + result);
    }

    // ── 4. Tokens ≥ 90% → red color ──

    @Test
    void ninetyPercentOrAbove_redColor() {
        // 180k / 200k = 90% → red
        String result = TokenStatusLine.format(180_000, 200_000, null);
        assertTrue(result.startsWith(TokenStatusLine.RED), "Expected red for ≥90%: " + result);
    }

    // ── 5. formatK: 1500 → "1k", 200000 → "200k" ──

    @Test
    void formatK_aboveThousand() {
        assertEquals("1k", TokenStatusLine.formatK(1_500));
        assertEquals("200k", TokenStatusLine.formatK(200_000));
        assertEquals("128k", TokenStatusLine.formatK(128_000));
    }

    // ── 6. formatK: 500 → "500" (below 1k, no suffix) ──

    @Test
    void formatK_belowThousand() {
        assertEquals("500", TokenStatusLine.formatK(500));
        assertEquals("0", TokenStatusLine.formatK(0));
        assertEquals("999", TokenStatusLine.formatK(999));
    }

    // ── 7. With compact phase → includes "| compact: Snip" ──

    @Test
    void withCompactPhase_includesLabel() {
        String result = TokenStatusLine.format(50_000, 200_000, "Snip");
        assertTrue(result.contains("| compact: Snip"), "Expected compact label: " + result);
    }

    // ── 8. Without compact phase (null) → no compact section ──

    @Test
    void nullCompactPhase_noCompactSection() {
        String result = TokenStatusLine.format(50_000, 200_000, null);
        assertFalse(result.contains("compact"), "Should not contain compact: " + result);
    }

    // ── 9. Exact boundary: 80% → yellow, 79.9% → gray ──

    @Test
    void exactBoundary_eightyPercent_isYellow() {
        // Exactly 80%: 80_000 / 100_000
        String result = TokenStatusLine.format(80_000, 100_000, null);
        assertTrue(result.startsWith(TokenStatusLine.YELLOW), "80% should be yellow: " + result);
    }

    @Test
    void justBelowEightyPercent_isGray() {
        // 79_999 / 100_000 = 79.999% → gray
        String result = TokenStatusLine.format(79_999, 100_000, null);
        assertTrue(result.startsWith(TokenStatusLine.GRAY), "79.999% should be gray: " + result);
    }

    // ── 10. Exact boundary: 90% → red, 89.9% → yellow ──

    @Test
    void exactBoundary_ninetyPercent_isRed() {
        // Exactly 90%: 90_000 / 100_000
        String result = TokenStatusLine.format(90_000, 100_000, null);
        assertTrue(result.startsWith(TokenStatusLine.RED), "90% should be red: " + result);
    }

    @Test
    void justBelowNinetyPercent_isYellow() {
        // 89_999 / 100_000 = 89.999% → yellow
        String result = TokenStatusLine.format(89_999, 100_000, null);
        assertTrue(result.startsWith(TokenStatusLine.YELLOW), "89.999% should be yellow: " + result);
    }

    // ── 11. Result ends with RESET code ──

    @Test
    void resultEndsWithReset() {
        String result = TokenStatusLine.format(50_000, 200_000, null);
        assertTrue(result.endsWith(TokenStatusLine.RESET), "Should end with RESET: " + result);
    }

    // ── 12. contextLimitForModel: model name mapping ──

    @Test
    void contextLimit_claude_200k() {
        assertEquals(200_000, TokenStatusLine.contextLimitForModel("claude-sonnet-4-20250514"));
        assertEquals(200_000, TokenStatusLine.contextLimitForModel("claude-3-haiku"));
    }

    @Test
    void contextLimit_gpt4o_128k() {
        assertEquals(128_000, TokenStatusLine.contextLimitForModel("gpt-4o"));
        assertEquals(128_000, TokenStatusLine.contextLimitForModel("gpt-4-turbo"));
    }

    @Test
    void contextLimit_null_fallback() {
        assertEquals(128_000, TokenStatusLine.contextLimitForModel(null));
    }

    @Test
    void contextLimit_unknown_fallback() {
        assertEquals(128_000, TokenStatusLine.contextLimitForModel("some-unknown-model"));
    }
}
