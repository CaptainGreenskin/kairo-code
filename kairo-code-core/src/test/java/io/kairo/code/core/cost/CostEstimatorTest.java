package io.kairo.code.core.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class CostEstimatorTest {

    @Test
    void gpt4o_1M_input_0_5M_output() {
        // $2.50/M input + $10.00/M output × 0.5M = $2.50 + $5.00 = $7.50
        var result = CostEstimator.estimate("gpt-4o", 1_000_000, 500_000);
        assertThat(result.isPresent()).isTrue();
        assertThat(result.getAsDouble()).isCloseTo(7.50, within(0.001));
    }

    @Test
    void gpt4oMini_distinctFromGpt4o() {
        var mini = CostEstimator.estimate("gpt-4o-mini", 1_000_000, 1_000_000);
        var full = CostEstimator.estimate("gpt-4o", 1_000_000, 1_000_000);
        assertThat(mini.getAsDouble()).isLessThan(full.getAsDouble());
    }

    @Test
    void unknownModel_returnsEmpty() {
        assertThat(CostEstimator.estimate("llama-8b", 1000, 500).isPresent()).isFalse();
    }

    @Test
    void estimateFromTotalTokens_2to1ratio() {
        // 900 total → 600 input, 300 output (2:1 ratio)
        // gpt-4o: (600/1M)*2.50 + (300/1M)*10.00 = $0.0015 + $0.003 = $0.0045
        var result = CostEstimator.estimate("gpt-4o", 900L);
        assertThat(result.isPresent()).isTrue();
        assertThat(result.getAsDouble()).isCloseTo(0.0045, within(0.0001));
    }

    @Test
    void format_smallAmount() {
        assertThat(CostEstimator.format(0.031)).isEqualTo("$0.031");
    }

    @Test
    void format_verySmallAmount() {
        assertThat(CostEstimator.format(0.00005)).isEqualTo("<$0.001");
    }

    @Test
    void format_largeAmount() {
        assertThat(CostEstimator.format(1.50)).isEqualTo("$1.50");
    }
}
