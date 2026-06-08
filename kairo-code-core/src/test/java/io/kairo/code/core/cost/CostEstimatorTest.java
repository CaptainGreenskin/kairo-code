package io.kairo.code.core.cost;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CostEstimatorTest {

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

    @Test
    void format_mediumAmount() {
        assertThat(CostEstimator.format(0.0045)).isEqualTo("$0.0045");
    }

    @Test
    void format_zero() {
        assertThat(CostEstimator.format(0.0)).isEqualTo("$0.00");
    }

    @Test
    void format_negative() {
        assertThat(CostEstimator.format(-1.0)).isEqualTo("$0.00");
    }

    @Test
    void format_veryLargeAmount() {
        assertThat(CostEstimator.format(999.99)).isEqualTo("$999.99");
    }

    @Test
    void format_boundaryAt001() {
        assertThat(CostEstimator.format(0.001)).isEqualTo("$0.0010");
    }
}
