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

import java.time.Duration;

/**
 * User-configurable status-line settings (mirrors the Claude Code schema for easy migration).
 *
 * <p>When {@link #command()} is non-blank, the REPL spawns the shell command with the current
 * {@link StatusLineState} piped to stdin (as JSON) and renders the first non-blank line of stdout
 * as the footer. When {@code command} is blank or the config file is absent, the REPL falls back
 * to the built-in {@code TokenStatusLine}.
 *
 * @param type currently only {@code "command"}; reserved for future shapes.
 * @param command shell command line; main process invokes via {@code /bin/sh -c}.
 * @param refreshInterval how often to re-render on a timer (independent of agent turns).
 *     {@link Duration#ZERO} or negative means "only render on agent events".
 * @param padding cells of left/right padding when the footer is composed (informational; consumer
 *     may ignore).
 * @param timeout per-invocation ceiling for the shell command. Beyond this the previous output is
 *     kept and the run is logged.
 */
public record StatusLineConfig(
        String type, String command, Duration refreshInterval, int padding, Duration timeout) {

    /** Schema-discriminator constant — only "command" is supported today. */
    public static final String TYPE_COMMAND = "command";

    /** Sensible safety ceiling for shell execution; matches Claude Code's 5000ms. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(5000);

    public StatusLineConfig {
        type = type == null || type.isBlank() ? TYPE_COMMAND : type;
        command = command == null ? "" : command;
        refreshInterval = refreshInterval == null ? Duration.ZERO : refreshInterval;
        padding = Math.max(0, padding);
        timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? DEFAULT_TIMEOUT : timeout;
    }

    /** Disabled config — built-in {@code TokenStatusLine} is used. */
    public static StatusLineConfig disabled() {
        return new StatusLineConfig(TYPE_COMMAND, "", Duration.ZERO, 0, DEFAULT_TIMEOUT);
    }

    /** Whether the shell-based renderer is active. */
    public boolean isShellEnabled() {
        return command != null && !command.isBlank();
    }

    /** Whether the timer-driven refresh path is active. */
    public boolean hasTimedRefresh() {
        return refreshInterval != null && !refreshInterval.isZero() && !refreshInterval.isNegative();
    }
}
