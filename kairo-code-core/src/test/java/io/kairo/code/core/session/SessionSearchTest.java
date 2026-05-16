package io.kairo.code.core.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionSearchTest {

    @TempDir
    Path tempDir;

    // ── InMemorySessionIndex ──

    @Test
    void indexAndSearchSingleTurn() {
        var index = new InMemorySessionIndex();
        index.index(0, turn("user", "Fix the NullPointerException in UserService"));

        var results = index.search("NullPointerException", 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).turnIndex()).isEqualTo(0);
        assertThat(results.get(0).turn().content()).contains("NullPointerException");
    }

    @Test
    void searchIsCaseInsensitive() {
        var index = new InMemorySessionIndex();
        index.index(0, turn("assistant", "The database connection pool is exhausted."));

        var results = index.search("DATABASE CONNECTION", 10);

        assertThat(results).hasSize(1);
    }

    @Test
    void searchMultipleTermsRanksHigher() {
        var index = new InMemorySessionIndex();
        index.index(0, turn("user", "Fix the bug"));
        index.index(1, turn("user", "Fix the database bug"));
        index.index(2, turn("assistant", "database database fix fix the database connection"));

        var results = index.search("database fix", 10);

        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        assertThat(results.get(0).turnIndex()).isEqualTo(2);
    }

    @Test
    void searchReturnsEmptyForNoMatch() {
        var index = new InMemorySessionIndex();
        index.index(0, turn("user", "Hello world"));

        var results = index.search("kubernetes deployment", 10);

        assertThat(results).isEmpty();
    }

    @Test
    void searchRespectsLimit() {
        var index = new InMemorySessionIndex();
        for (int i = 0; i < 20; i++) {
            index.index(i, turn("user", "Error occurred in module " + i));
        }

        var results = index.search("error module", 5);

        assertThat(results).hasSize(5);
    }

    @Test
    void searchHandlesNullAndBlankQuery() {
        var index = new InMemorySessionIndex();
        index.index(0, turn("user", "Some content"));

        assertThat(index.search(null, 10)).isEmpty();
        assertThat(index.search("", 10)).isEmpty();
        assertThat(index.search("   ", 10)).isEmpty();
    }

    @Test
    void indexSkipsNullAndBlankContent() {
        var index = new InMemorySessionIndex();
        index.index(0, turn("user", null));
        index.index(1, turn("user", "  "));
        index.index(2, turn("user", "Real content"));

        var results = index.search("real content", 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).turnIndex()).isEqualTo(2);
    }

    @Test
    void termFrequencyBoostsRelevance() {
        var index = new InMemorySessionIndex();
        index.index(0, turn("user", "error"));
        index.index(1, turn("user", "error error error in the error handler"));

        var results = index.search("error", 10);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).turnIndex()).isEqualTo(1);
        assertThat(results.get(0).score()).isGreaterThan(results.get(1).score());
    }

    // ── SessionWriter search integration ──

    @Test
    void writerSearchAfterAppend() throws IOException {
        Path file = tempDir.resolve("session.jsonl");
        var writer = new SessionWriter(file);

        writer.appendTurn("user", "Deploy to production", 0, Instant.now());
        writer.appendTurn("assistant", "Running kubectl apply for production deployment", 50, Instant.now());
        writer.appendTurn("user", "Check the staging logs", 0, Instant.now());

        var results = writer.search("production deploy", 10);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).turn().content()).contains("production");
    }

    @Test
    void writerSearchReturnsEmptyWhenNoTurns() throws IOException {
        Path file = tempDir.resolve("empty.jsonl");
        var writer = new SessionWriter(file);

        assertThat(writer.search("anything", 10)).isEmpty();
    }

    @Test
    void rebuildIndexFromDisk() throws IOException {
        Path file = tempDir.resolve("session.jsonl");

        var writer1 = new SessionWriter(file);
        writer1.appendTurn("user", "Refactor the authentication module", 0, Instant.now());
        writer1.appendTurn("assistant", "I'll update the JWT token validation", 100, Instant.now());

        var writer2 = new SessionWriter(file);
        assertThat(writer2.search("authentication", 10)).isEmpty();

        writer2.rebuildIndex();

        var results = writer2.search("authentication", 10);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).turn().content()).contains("authentication");
    }

    @Test
    void rebuildIndexPreservesIncrementalUpdates() throws IOException {
        Path file = tempDir.resolve("session.jsonl");
        var writer = new SessionWriter(file);
        writer.appendTurn("user", "First message", 0, Instant.now());

        writer.rebuildIndex();
        writer.appendTurn("user", "Second message after rebuild", 0, Instant.now());

        var results = writer.search("message", 10);
        assertThat(results).hasSize(2);
    }

    private static SessionTurn turn(String role, String content) {
        return new SessionTurn(role, content, 0, Instant.now());
    }
}
