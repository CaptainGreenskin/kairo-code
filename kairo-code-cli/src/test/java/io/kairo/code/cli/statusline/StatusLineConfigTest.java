/*
 * Copyright 2025-2026 the Kairo authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.kairo.code.cli.statusline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class StatusLineConfigTest {

    @Test
    void disabled_hasBlankCommand_andIsNotShellEnabled() {
        StatusLineConfig cfg = StatusLineConfig.disabled();
        assertThat(cfg.isShellEnabled()).isFalse();
        assertThat(cfg.command()).isEmpty();
    }

    @Test
    void blankCommand_isNotShellEnabled() {
        StatusLineConfig cfg =
                new StatusLineConfig("command", "   ", Duration.ZERO, 0, Duration.ofSeconds(5));
        assertThat(cfg.isShellEnabled()).isFalse();
    }

    @Test
    void nonBlankCommand_isShellEnabled() {
        StatusLineConfig cfg =
                new StatusLineConfig("command", "echo hi", Duration.ZERO, 0, Duration.ofSeconds(5));
        assertThat(cfg.isShellEnabled()).isTrue();
    }

    @Test
    void canonical_constructor_appliesDefaults() {
        // null type/command/refresh/timeout + negative padding → all normalised.
        StatusLineConfig cfg = new StatusLineConfig(null, null, null, -7, null);
        assertThat(cfg.type()).isEqualTo(StatusLineConfig.TYPE_COMMAND);
        assertThat(cfg.command()).isEmpty();
        assertThat(cfg.refreshInterval()).isEqualTo(Duration.ZERO);
        assertThat(cfg.padding()).isZero();
        assertThat(cfg.timeout()).isEqualTo(StatusLineConfig.DEFAULT_TIMEOUT);
    }

    @Test
    void zeroOrNegativeTimeout_fallsBackToDefault() {
        // Operators can't accidentally set timeout=0 and hang the REPL.
        assertThat(
                        new StatusLineConfig(
                                        "command", "x", Duration.ZERO, 0, Duration.ZERO)
                                .timeout())
                .isEqualTo(StatusLineConfig.DEFAULT_TIMEOUT);
        assertThat(
                        new StatusLineConfig(
                                        "command", "x", Duration.ZERO, 0, Duration.ofSeconds(-1))
                                .timeout())
                .isEqualTo(StatusLineConfig.DEFAULT_TIMEOUT);
    }

    @Test
    void hasTimedRefresh_onlyTrueForPositiveDuration() {
        assertThat(
                        new StatusLineConfig(
                                        "command", "x", Duration.ZERO, 0, Duration.ofSeconds(5))
                                .hasTimedRefresh())
                .isFalse();
        assertThat(
                        new StatusLineConfig(
                                        "command", "x", Duration.ofSeconds(2), 0, Duration.ofSeconds(5))
                                .hasTimedRefresh())
                .isTrue();
    }
}
