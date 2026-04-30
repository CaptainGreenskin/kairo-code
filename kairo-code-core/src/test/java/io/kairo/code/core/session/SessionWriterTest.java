package io.kairo.code.core.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link SessionWriter} and {@link SessionTurn}.
 */
class SessionWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void appendTurn_createsFileAndParentDirs() {
        Path deepFile = tempDir.resolve("a/b/c/session.jsonl");
        SessionWriter writer = new SessionWriter(deepFile);

        writer.appendTurn("user", "hello", 0, Instant.parse("2026-04-30T10:00:00Z"));

        assertThat(deepFile).exists();
        assertThat(deepFile.getParent()).isDirectory();
    }

    @Test
    void appendTurn_appendsNotOverwrites() {
        Path file = tempDir.resolve("session.jsonl");
        SessionWriter writer = new SessionWriter(file);

        writer.appendTurn("user", "first", 0, Instant.parse("2026-04-30T10:00:00Z"));
        writer.appendTurn("assistant", "second", 42, Instant.parse("2026-04-30T10:00:01Z"));

        List<SessionTurn> turns = writer.readSession();
        assertThat(turns).hasSize(2);
        assertThat(turns.get(0).role()).isEqualTo("user");
        assertThat(turns.get(0).content()).isEqualTo("first");
        assertThat(turns.get(1).role()).isEqualTo("assistant");
        assertThat(turns.get(1).content()).isEqualTo("second");
    }

    @Test
    void writtenJsonl_isValidJsonPerLine() throws IOException {
        Path file = tempDir.resolve("session.jsonl");
        SessionWriter writer = new SessionWriter(file);

        writer.appendTurn("user", "test message", 0, Instant.parse("2026-04-30T10:00:00Z"));

        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(1);
        // Each line must be valid JSON (parseable)
        String line = lines.get(0);
        assertThat(line).startsWith("{");
        assertThat(line).endsWith("}");
        assertThat(line).contains("\"role\"");
        assertThat(line).contains("\"content\"");
        assertThat(line).contains("\"tokens\"");
        assertThat(line).contains("\"ts\"");
    }

    @Test
    void readSession_returnsAllTurnsInOrder() {
        Path file = tempDir.resolve("session.jsonl");
        SessionWriter writer = new SessionWriter(file);

        Instant t1 = Instant.parse("2026-04-30T10:00:00Z");
        Instant t2 = Instant.parse("2026-04-30T10:00:01Z");
        Instant t3 = Instant.parse("2026-04-30T10:00:02Z");

        writer.appendTurn("user", "Q1", 0, t1);
        writer.appendTurn("assistant", "A1", 100, t2);
        writer.appendTurn("user", "Q2", 0, t3);

        List<SessionTurn> turns = writer.readSession();
        assertThat(turns).hasSize(3);
        assertThat(turns.get(0)).isEqualTo(new SessionTurn("user", "Q1", 0, t1));
        assertThat(turns.get(1)).isEqualTo(new SessionTurn("assistant", "A1", 100, t2));
        assertThat(turns.get(2)).isEqualTo(new SessionTurn("user", "Q2", 0, t3));
    }

    @Test
    void readSession_emptyFileReturnsEmptyList() throws IOException {
        Path file = tempDir.resolve("empty.jsonl");
        Files.createFile(file);
        SessionWriter writer = new SessionWriter(file);

        List<SessionTurn> turns = writer.readSession();
        assertThat(turns).isEmpty();
    }

    @Test
    void readSession_nonExistentFileReturnsEmptyList() {
        Path file = tempDir.resolve("does-not-exist.jsonl");
        SessionWriter writer = new SessionWriter(file);

        List<SessionTurn> turns = writer.readSession();
        assertThat(turns).isEmpty();
    }

    @Test
    void sessionTurnRecord_fieldsCorrect() {
        Instant ts = Instant.parse("2026-04-30T12:34:56Z");
        SessionTurn turn = new SessionTurn("user", "hello world", 42, ts);

        assertThat(turn.role()).isEqualTo("user");
        assertThat(turn.content()).isEqualTo("hello world");
        assertThat(turn.tokens()).isEqualTo(42);
        assertThat(turn.ts()).isEqualTo(ts);
    }

    @Test
    void timestampSerialization_roundtrip() {
        Path file = tempDir.resolve("session.jsonl");
        SessionWriter writer = new SessionWriter(file);

        Instant original = Instant.parse("2026-04-30T12:34:56.789Z");
        writer.appendTurn("user", "timestamp test", 0, original);

        List<SessionTurn> turns = writer.readSession();
        assertThat(turns).hasSize(1);
        assertThat(turns.get(0).ts()).isEqualTo(original);
    }

    @Test
    void specialCharacters_serializedCorrectly() {
        Path file = tempDir.resolve("session.jsonl");
        SessionWriter writer = new SessionWriter(file);

        String content = "He said \"hello\" and she\nsaid 'goodbye'\twith\\backslash";
        writer.appendTurn("user", content, 0, Instant.parse("2026-04-30T10:00:00Z"));

        List<SessionTurn> turns = writer.readSession();
        assertThat(turns).hasSize(1);
        assertThat(turns.get(0).content()).isEqualTo(content);
    }

    @Test
    void largeContent_handledCorrectly() {
        Path file = tempDir.resolve("session.jsonl");
        SessionWriter writer = new SessionWriter(file);

        // Build ~10KB content
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            sb.append("Line ").append(i).append(": This is a test line with some content.\n");
        }
        String largeContent = sb.toString();
        assertThat(largeContent.length()).isGreaterThan(10_000);

        writer.appendTurn("assistant", largeContent, 5000, Instant.parse("2026-04-30T10:00:00Z"));

        List<SessionTurn> turns = writer.readSession();
        assertThat(turns).hasSize(1);
        assertThat(turns.get(0).content()).isEqualTo(largeContent);
        assertThat(turns.get(0).tokens()).isEqualTo(5000);
    }

    @Test
    void sessionFile_returnsCorrectPath() {
        Path file = tempDir.resolve("my-session.jsonl");
        SessionWriter writer = new SessionWriter(file);

        assertThat(writer.sessionFile()).isEqualTo(file);
    }

    @Test
    void readSession_skipsMalformedLines() throws IOException {
        Path file = tempDir.resolve("session.jsonl");
        SessionWriter writer = new SessionWriter(file);

        // Write a valid turn, then manually inject a bad line, then another valid turn
        writer.appendTurn("user", "valid1", 0, Instant.parse("2026-04-30T10:00:00Z"));
        Files.writeString(file, "this is not json\n",
                java.nio.file.StandardOpenOption.APPEND);
        writer.appendTurn("user", "valid2", 0, Instant.parse("2026-04-30T10:00:02Z"));

        List<SessionTurn> turns = writer.readSession();
        assertThat(turns).hasSize(2);
        assertThat(turns.get(0).content()).isEqualTo("valid1");
        assertThat(turns.get(1).content()).isEqualTo("valid2");
    }

    @Test
    void nullContent_treatedAsEmpty() {
        Path file = tempDir.resolve("session.jsonl");
        SessionWriter writer = new SessionWriter(file);

        writer.appendTurn("user", null, 0, Instant.parse("2026-04-30T10:00:00Z"));

        List<SessionTurn> turns = writer.readSession();
        assertThat(turns).hasSize(1);
        assertThat(turns.get(0).content()).isEqualTo("");
    }
}
