package io.kairo.code.core.session;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionWriterEndMarkerTest {

    @TempDir
    Path tempDir;

    @Test
    void endMarkerWrittenAndDetected() {
        Path file = tempDir.resolve("test.jsonl");
        SessionWriter writer = new SessionWriter(file);

        writer.appendTurn("user", "hello", 0, Instant.now());
        writer.appendTurn("assistant", "hi", 10, Instant.now());
        assertFalse(writer.hasEndMarker(), "should not have end marker before write");

        writer.writeEndMarker();
        assertTrue(writer.hasEndMarker(), "should have end marker after write");
    }

    @Test
    void noEndMarkerOnInterruptedSession() {
        Path file = tempDir.resolve("interrupted.jsonl");
        SessionWriter writer = new SessionWriter(file);

        writer.appendTurn("user", "hello", 0, Instant.now());
        writer.appendTurn("assistant", "working...", 10, Instant.now());

        assertFalse(writer.hasEndMarker(), "interrupted session should not have end marker");
    }

    @Test
    void emptyFileHasNoEndMarker() {
        Path file = tempDir.resolve("empty.jsonl");
        SessionWriter writer = new SessionWriter(file);
        assertFalse(writer.hasEndMarker());
    }

    @Test
    void endMarkerDoesNotBreakReadSession() {
        Path file = tempDir.resolve("with-marker.jsonl");
        SessionWriter writer = new SessionWriter(file);

        writer.appendTurn("user", "q1", 0, Instant.now());
        writer.appendTurn("assistant", "a1", 5, Instant.now());
        writer.writeEndMarker();

        var turns = writer.readSession();
        assertEquals(2, turns.size(), "readSession should return 2 turns (end marker is not a turn)");
    }
}
