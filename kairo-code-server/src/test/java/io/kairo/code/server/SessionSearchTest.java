package io.kairo.code.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kairo.code.server.controller.SessionSnapshotController;
import io.kairo.code.server.controller.SessionSnapshotController.SearchHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SessionSearchTest {

    @TempDir Path tempDir;
    SessionSnapshotController controller;

    @BeforeEach
    void setUp() throws IOException {
        Path sessionsDir = tempDir.resolve(".kairo-code").resolve("sessions");
        controller = new SessionSnapshotController(sessionsDir, new ObjectMapper());

        // Create a snapshot with known content
        Files.createDirectories(sessionsDir);
        String snap = """
            {"sessionId":"sess1","name":"Test Session","savedAt":1000,"messages":[
              {"id":"m1","role":"user","content":"Fix the NullPointerException in LoginService"},
              {"id":"m2","role":"assistant","content":"I found the bug on line 42"}
            ]}""";
        Files.writeString(sessionsDir.resolve("sess1.json"), snap, StandardCharsets.UTF_8);
    }

    @Test
    void search_findsMatchInUserMessage() throws IOException {
        List<SearchHit> hits = controller.searchMessages("NullPointerException", 10);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).sessionId()).isEqualTo("sess1");
        assertThat(hits.get(0).role()).isEqualTo("user");
        assertThat(hits.get(0).snippet()).contains("NullPointerException");
    }

    @Test
    void search_caseInsensitive() throws IOException {
        List<SearchHit> hits = controller.searchMessages("nullpointerexception", 10);
        assertThat(hits).hasSize(1);
    }

    @Test
    void search_shortQuery_returnsEmpty() throws IOException {
        List<SearchHit> hits = controller.searchMessages("a", 10);
        assertThat(hits).isEmpty();
    }

    @Test
    void search_noMatch_returnsEmpty() throws IOException {
        List<SearchHit> hits = controller.searchMessages("StackOverflow", 10);
        assertThat(hits).isEmpty();
    }

    @Test
    void search_multipleHits_inSameSession() throws IOException {
        List<SearchHit> hits = controller.searchMessages("bug", 10);
        // "bug" is in message m2 ("I found the bug...")
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).role()).isEqualTo("assistant");
    }
}
