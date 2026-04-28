package com.example;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StringUtilsTest {

    @Test
    void nullIsBlank() {
        assertThat(StringUtils.isBlank(null)).isTrue();
    }

    @Test
    void emptyStringIsBlank() {
        assertThat(StringUtils.isBlank("")).isTrue();
    }

    @Test
    void whitespaceIsBlank() {
        assertThat(StringUtils.isBlank("   ")).isTrue();
    }

    @Test
    void nonBlankString() {
        assertThat(StringUtils.isBlank("hello")).isFalse();
    }

    @Test
    void capitalizeNormal() {
        assertThat(StringUtils.capitalize("hello")).isEqualTo("Hello");
    }

    @Test
    void capitalizeNull() {
        assertThat(StringUtils.capitalize(null)).isNull();
    }

    @Test
    void repeatNormal() {
        assertThat(StringUtils.repeat("ab", 3)).isEqualTo("ababab");
    }

    @Test
    void repeatNull() {
        assertThat(StringUtils.repeat(null, 3)).isNull();
    }

    @Test
    void reverseNormal() {
        assertThat(StringUtils.reverse("hello")).isEqualTo("olleh");
    }

    @Test
    void reverseNull() {
        assertThat(StringUtils.reverse(null)).isNull();
    }
}
