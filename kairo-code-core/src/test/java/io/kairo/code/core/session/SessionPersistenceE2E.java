package io.kairo.code.core.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * E2E tests for JSONL session persistence.
 *
 * <p>These tests exercise {@link SessionWriter} end-to-end: write turns, read them back,
 * and verify format, cross-instance resumability, and edge cases.
 *
 * <p>No API key required — pure file I/O tests.
 */
class SessionPersistenceE2E {

    @TempDir Path tempDir;

    // ──────────────────────────────────────────────────────────────────────
    // Test 1: Write 2 turns, read back, verify JSONL format
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void sessionWriterCreatesValidJsonl() {
        Path file = tempDir.resolve("test.jsonl");
        SessionWriter writer = new SessionWriter(file);

        Instant now = Instant.now();
        writer.appendTurn("user", "hello", 0, now);
        writer.appendTurn("assistant", "hi there", 50, now.plusSeconds(1));

        List<SessionTurn> turns = writer.readSession();
        assertThat(turns).hasSize(2);
        assertThat(turns.get(0).role()).isEqualTo("user");
        assertThat(turns.get(0).content()).isEqualTo("hello");
        assertThat(turns.get(0).tokens()).isZero();
        assertThat(turns.get(1).role()).isEqualTo("assistant");
        assertThat(turns.get(1).content()).isEqualTo("hi there");
        assertThat(turns.get(1).tokens()).isEqualTo(50);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Test 2: Session resumable across writer instances
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void sessionResumableAcrossInstances() {
        Path file = tempDir.resolve("session.jsonl");

        // Write with one instance
        new SessionWriter(file).appendTurn("user", "remember this", 0, Instant.now());

        // Read with a new instance
        List<SessionTurn> turns = new SessionWriter(file).readSession();
        assertThat(turns).hasSize(1);
        assertThat(turns.get(0).content()).contains("remember this");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Test 3: JSONL file has one JSON object per line
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void jsonlFileHasOneObjectPerLine() throws Exception {
        Path file = tempDir.resolve("lines.jsonl");
        SessionWriter writer = new SessionWriter(file);

        writer.appendTurn("user", "first", 0, Instant.now());
        writer.appendTurn("assistant", "second", 10, Instant.now());
        writer.appendTurn("user", "third", 0, Instant.now());

        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(3);
        // Each line should be valid JSON (starts with { and ends with })
        for (String line : lines) {
            assertThat(line.trim()).startsWith("{").endsWith("}");
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Test 4: Empty file returns empty list
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void emptyFileReturnsEmptyList() throws Exception {
        Path file = tempDir.resolve("empty.jsonl");
        Files.createFile(file);

        List<SessionTurn> turns = new SessionWriter(file).readSession();
        assertThat(turns).isEmpty();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Test 5: Non-existent file returns empty list (no exception)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void nonExistentFileReturnsEmptyList() {
        Path file = tempDir.resolve("no-such-file.jsonl");

        List<SessionTurn> turns = new SessionWriter(file).readSession();
        assertThat(turns).isEmpty();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Test 6: Parent directories created automatically
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void parentDirectoriesCreatedAutomatically() {
        Path file = tempDir.resolve("sub/dir/session.jsonl");
        SessionWriter writer = new SessionWriter(file);

        writer.appendTurn("user", "deep dir test", 0, Instant.now());

        assertThat(Files.exists(file)).isTrue();
        List<SessionTurn> turns = writer.readSession();
        assertThat(turns).hasSize(1);
        assertThat(turns.get(0).content()).isEqualTo("deep dir test");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Test 7: Timestamp round-trips correctly
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void timestampRoundTripsCorrectly() {
        Path file = tempDir.resolve("ts.jsonl");
        Instant ts = Instant.parse("2026-04-30T12:00:00Z");

        new SessionWriter(file).appendTurn("user", "ts test", 0, ts);

        List<SessionTurn> turns = new SessionWriter(file).readSession();
        assertThat(turns).hasSize(1);
        assertThat(turns.get(0).ts()).isEqualTo(ts);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Test 8: Content with special characters survives round-trip
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void contentWithSpecialCharactersSurvivesRoundTrip() {
        Path file = tempDir.resolve("special.jsonl");
        String specialContent = "line1\nline2\ttab \"quoted\" {json}";

        new SessionWriter(file).appendTurn("user", specialContent, 0, Instant.now());

        List<SessionTurn> turns = new SessionWriter(file).readSession();
        assertThat(turns).hasSize(1);
        assertThat(turns.get(0).content()).isEqualTo(specialContent);
    }
}
