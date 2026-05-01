package io.kairo.code.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kairo.code.server.config.ServerConfig.ServerProperties;
import io.kairo.code.server.controller.SessionSnapshotController;
import io.kairo.code.server.controller.SessionSnapshotController.SnapshotMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionSnapshotControllerTest {

    @TempDir
    Path tempDir;

    private SessionSnapshotController controller;
    private Path sessionsDir;

    @BeforeEach
    void setUp() {
        ServerProperties props = new ServerProperties(
                "openai", "gpt-4o", tempDir.toString(),
                "https://api.openai.com", "sk-test");
        controller = new SessionSnapshotController(props, new ObjectMapper());
        sessionsDir = tempDir.resolve(".kairo-code").resolve("sessions");
    }

    @Test
    void saveAndLoad_roundtrip() throws IOException {
        String body = "{\"sessionId\":\"s1\",\"name\":\"Test\","
                + "\"messages\":[{\"id\":\"m1\",\"role\":\"user\",\"content\":\"hello\"}]}";

        Map<String, Object> saved = controller.saveSnapshot("s1", body);
        assertThat(saved.get("sessionId")).isEqualTo("s1");
        assertThat(saved.get("savedAt")).isInstanceOf(Long.class);

        var loaded = controller.loadSnapshot("s1");
        assertThat(loaded.getStatusCode().value()).isEqualTo(200);
        assertThat(loaded.getBody()).contains("hello");
        assertThat(loaded.getBody()).contains("\"sessionId\":\"s1\"");
        assertThat(loaded.getBody()).contains("\"savedAt\":");
    }

    @Test
    void saveSnapshot_overwritesSessionIdFromPath() throws IOException {
        String body = "{\"sessionId\":\"impostor\",\"name\":\"Test\",\"messages\":[]}";
        controller.saveSnapshot("real-id", body);

        var loaded = controller.loadSnapshot("real-id");
        assertThat(loaded.getStatusCode().value()).isEqualTo(200);
        assertThat(loaded.getBody()).contains("\"sessionId\":\"real-id\"");
    }

    @Test
    void listSnapshots_returnsAllNewestFirst() throws IOException, InterruptedException {
        controller.saveSnapshot("s1", "{\"sessionId\":\"s1\",\"name\":\"A\",\"messages\":[]}");
        // Ensure savedAt timestamps are distinct (millisecond resolution).
        Thread.sleep(5);
        controller.saveSnapshot("s2",
                "{\"sessionId\":\"s2\",\"name\":\"B\",\"messages\":[{\"id\":\"m1\"}]}");

        List<SnapshotMeta> list = controller.listSnapshots();
        assertThat(list).hasSize(2);
        // s2 saved after s1 → should come first
        assertThat(list.get(0).sessionId()).isEqualTo("s2");
        assertThat(list.get(0).name()).isEqualTo("B");
        assertThat(list.get(0).messageCount()).isEqualTo(1);
        assertThat(list.get(1).sessionId()).isEqualTo("s1");
    }

    @Test
    void listSnapshots_emptyWhenDirMissing() throws IOException {
        assertThat(controller.listSnapshots()).isEmpty();
    }

    @Test
    void deleteSnapshot_removesFile() throws IOException {
        controller.saveSnapshot("s3", "{\"sessionId\":\"s3\",\"name\":\"C\",\"messages\":[]}");
        controller.deleteSnapshot("s3");

        var loaded = controller.loadSnapshot("s3");
        assertThat(loaded.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void deleteSnapshot_isIdempotent() throws IOException {
        var resp = controller.deleteSnapshot("nonexistent");
        assertThat(resp.getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void loadSnapshot_returns404WhenMissing() throws IOException {
        var loaded = controller.loadSnapshot("nope");
        assertThat(loaded.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void invalidSessionId_pathTraversal_throwsBadRequest() {
        assertThatThrownBy(() -> controller.saveSnapshot("../evil", "{}"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(400));
    }

    @Test
    void invalidSessionId_specialChars_throwsBadRequest() {
        assertThatThrownBy(() -> controller.loadSnapshot("a/b"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.deleteSnapshot("a b"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.saveSnapshot("", "{}"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void invalidJsonBody_throwsBadRequest() {
        assertThatThrownBy(() -> controller.saveSnapshot("s1", "not-json"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void nonObjectJsonBody_throwsBadRequest() {
        assertThatThrownBy(() -> controller.saveSnapshot("s1", "[1,2,3]"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void evictOldSnapshots_keepsOnlyMostRecent50() throws IOException {
        Files.createDirectories(sessionsDir);
        // Pre-create 60 files with increasing modified times.
        for (int i = 0; i < 60; i++) {
            Path f = sessionsDir.resolve("old" + i + ".json");
            Files.writeString(f,
                    "{\"sessionId\":\"old" + i + "\",\"name\":\"x\",\"messages\":[]}");
            // Older index → older mtime.
            Files.setLastModifiedTime(f,
                    java.nio.file.attribute.FileTime.fromMillis(1_000_000L + i));
        }

        // Trigger eviction by saving one more.
        controller.saveSnapshot("fresh", "{\"sessionId\":\"fresh\",\"name\":\"new\",\"messages\":[]}");

        long count;
        try (var s = Files.list(sessionsDir)) {
            count = s.filter(p -> p.toString().endsWith(".json")).count();
        }
        assertThat(count).isEqualTo(50L);
        // Newest file ("fresh") survived.
        assertThat(Files.exists(sessionsDir.resolve("fresh.json"))).isTrue();
        // Oldest file (old0) was evicted.
        assertThat(Files.exists(sessionsDir.resolve("old0.json"))).isFalse();
    }
}
