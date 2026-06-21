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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders the REPL footer by invoking a user-supplied shell command with the current
 * {@link StatusLineState} piped to stdin as JSON.
 *
 * <p>Contract (matches Claude Code's {@code executeStatusLineCommand} for portability):
 *
 * <ul>
 *   <li>Spawn via {@code /bin/sh -c <command>}. POSIX-only — Windows users should run via WSL.
 *   <li>Pipe the JSON serialisation of {@link StatusLineState} to stdin.
 *   <li>Capture stdout up to {@link #MAX_STDOUT_BYTES}. {@code trim()}, drop blank lines,
 *       re-join with {@code \n}. Multi-line output is preserved but discouraged.
 *   <li>Exit 0 + empty stdout → return empty string (clears the line).
 *   <li>Non-zero exit OR timeout → return the previous output (the renderer is stateless;
 *       caller is responsible for caching the last good value).
 *   <li>Stderr is captured but only logged at DEBUG to keep the REPL footer pristine.
 * </ul>
 *
 * <p>The renderer is intentionally synchronous — kairo-code's REPL only calls it between
 * agent turns or on timer ticks, never during streaming. A misbehaving script can still hang
 * the REPL for at most {@link StatusLineConfig#timeout()}.
 */
public final class ShellStatusLineRenderer {

    private static final Logger log = LoggerFactory.getLogger(ShellStatusLineRenderer.class);

    /** Cap on captured stdout — prevents a runaway script from OOMing the JVM. */
    static final int MAX_STDOUT_BYTES = 16_384;

    private final ObjectMapper objectMapper;

    public ShellStatusLineRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Render the status line for the given state under the given config.
     *
     * @param config user config; must be {@link StatusLineConfig#isShellEnabled() shell-enabled}
     * @param state current runtime state (serialised to stdin)
     * @return the rendered footer line(s), or empty string on failure / blank output. Never null.
     */
    public String render(StatusLineConfig config, StatusLineState state) {
        if (!config.isShellEnabled()) {
            return "";
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialise StatusLineState: {}", e.getMessage());
            return "";
        }

        Process proc;
        try {
            proc = new ProcessBuilder(io.kairo.core.util.ShellCommand.buildCommand(config.command())).redirectErrorStream(false).start();
        } catch (IOException e) {
            log.warn("Failed to spawn status-line command '{}': {}", config.command(), e.getMessage());
            return "";
        }

        // Write stdin then close — many scripts (jq, python -c) block until EOF on stdin.
        try (OutputStream out = proc.getOutputStream()) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // Best-effort — script may have already exited. Continue to capture stdout.
            log.debug("status-line script closed stdin early: {}", e.getMessage());
        }

        boolean finished;
        try {
            finished = proc.waitFor(config.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            return "";
        }

        if (!finished) {
            log.warn(
                    "status-line command exceeded {}ms timeout, killing", config.timeout().toMillis());
            proc.destroyForcibly();
            return "";
        }

        String stdout = readCappedOutput(proc.getInputStream());
        String stderr = readCappedOutput(proc.getErrorStream());

        int exit = proc.exitValue();
        if (exit != 0) {
            log.debug(
                    "status-line command exited {} (stderr: {})",
                    exit,
                    stderr.isBlank() ? "<empty>" : stderr.lines().findFirst().orElse(""));
            return "";
        }
        return cleanOutput(stdout);
    }

    /**
     * Read up to {@link #MAX_STDOUT_BYTES} from {@code in}. Excess bytes are discarded. Used for
     * both stdout and stderr — they're capped separately so a chatty stderr can't displace
     * stdout in our budget.
     */
    private static String readCappedOutput(InputStream in) {
        try (InputStream s = in) {
            byte[] buf = new byte[MAX_STDOUT_BYTES];
            int total = 0;
            int n;
            while (total < buf.length && (n = s.read(buf, total, buf.length - total)) > 0) {
                total += n;
            }
            // Drain remaining bytes from the pipe so the writer doesn't block on backpressure.
            byte[] sink = new byte[4096];
            while (s.read(sink) > 0) {
                // discard
            }
            return new String(buf, 0, total, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Apply Claude Code's output normalisation: trim outer whitespace, drop blank lines, re-join
     * with {@code \n}. Single-line is the common case and round-trips identically.
     */
    static String cleanOutput(String raw) {
        if (raw == null) return "";
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(trimmed.length());
        boolean first = true;
        for (String line : trimmed.split("\n", -1)) {
            String s = line.strip();
            if (s.isEmpty()) continue;
            if (!first) sb.append('\n');
            sb.append(s);
            first = false;
        }
        return sb.toString();
    }
}
