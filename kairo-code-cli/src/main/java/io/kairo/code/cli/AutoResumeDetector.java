/*
 * Copyright 2025-2026 the Kairo authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.kairo.code.cli;

import io.kairo.code.cli.commands.ResumeCommand;
import io.kairo.code.core.session.SessionWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.jline.reader.LineReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects interrupted (crash) sessions on REPL startup and offers to resume them.
 *
 * <p>Scans the sessions directory for JSONL files modified within the last 24 hours
 * that do NOT end with a {@code session_end} marker (written by {@link SessionWriter#writeEndMarker()}).
 * If an interrupted session is found, prompts the user to resume it.
 */
public final class AutoResumeDetector {

    private static final Logger log = LoggerFactory.getLogger(AutoResumeDetector.class);
    private static final Duration STALE_THRESHOLD = Duration.ofHours(24);
    private static final String KAIRO_CODE_DIR = ".kairo-code";

    private AutoResumeDetector() {}

    /**
     * Check for interrupted sessions and prompt the user to resume.
     *
     * @param workingDir the project working directory (sessions stored under ~/.kairo-code/sessions/)
     * @param context    the REPL context (used for restoring)
     * @param reader     line reader for user prompt
     * @param writer     output writer
     * @return the session ID that was resumed, or empty if none
     */
    public static Optional<String> checkAndPrompt(
            String workingDir, ReplContext context, LineReader reader, PrintWriter writer) {
        Path sessionsDir = resolveSessionsDir(workingDir);
        if (!Files.isDirectory(sessionsDir)) {
            return Optional.empty();
        }

        Instant cutoff = Instant.now().minus(STALE_THRESHOLD);

        record Candidate(String id, Path file, Instant lastModified, long turnCount) {}

        Candidate best = null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sessionsDir, "*.jsonl")) {
            for (Path file : stream) {
                Instant lastMod;
                try {
                    lastMod = Files.getLastModifiedTime(file).toInstant();
                } catch (IOException e) {
                    continue;
                }
                if (lastMod.isBefore(cutoff)) continue;

                SessionWriter sw = new SessionWriter(file);
                if (sw.hasEndMarker()) continue;

                long turns;
                try {
                    turns = Files.lines(file).filter(l -> !l.isBlank()).count();
                } catch (IOException e) {
                    turns = 0;
                }
                if (turns < 2) continue;

                String fileName = file.getFileName().toString();
                String id = fileName.substring(0, fileName.length() - ".jsonl".length());

                if (best == null || lastMod.isAfter(best.lastModified())) {
                    best = new Candidate(id, file, lastMod, turns);
                }
            }
        } catch (IOException e) {
            log.debug("Failed to scan sessions directory: {}", e.getMessage());
            return Optional.empty();
        }

        if (best == null) {
            return Optional.empty();
        }

        writer.println();
        writer.printf("  Found interrupted session '%s' (%d turns, %s)%n",
                best.id(), best.turnCount(),
                formatTimeAgo(best.lastModified()));
        writer.flush();

        String answer;
        try {
            answer = reader.readLine("  Resume? [y/N] ");
        } catch (Exception e) {
            return Optional.empty();
        }

        if (answer != null && (answer.strip().equalsIgnoreCase("y")
                || answer.strip().equalsIgnoreCase("yes"))) {
            new ResumeCommand().execute(best.id(), context);
            return Optional.of(best.id());
        }

        return Optional.empty();
    }

    private static Path resolveSessionsDir(String workingDir) {
        if (workingDir != null && !workingDir.isBlank()) {
            return Path.of(workingDir, KAIRO_CODE_DIR, "sessions");
        }
        return Path.of(System.getProperty("user.home"), KAIRO_CODE_DIR, "sessions");
    }

    private static String formatTimeAgo(Instant when) {
        Duration ago = Duration.between(when, Instant.now());
        if (ago.toMinutes() < 1) return "just now";
        if (ago.toMinutes() < 60) return ago.toMinutes() + "m ago";
        return ago.toHours() + "h ago";
    }
}
