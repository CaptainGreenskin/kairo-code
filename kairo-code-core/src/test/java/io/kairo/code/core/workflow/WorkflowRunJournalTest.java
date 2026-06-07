package io.kairo.code.core.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowRunJournalTest {

    @TempDir
    Path tempDir;

    @Test
    void createAndSaveAndResume() throws IOException {
        WorkflowRunJournal journal = WorkflowRunJournal.create(tempDir);
        assertThat(journal.runId()).startsWith("wf_");

        journal.cache("key1", "result1");
        journal.cache("key2", Map.of("a", 1));
        journal.save();

        // Verify file exists
        Path file = tempDir.resolve(".kairo/workflow-runs/" + journal.runId() + ".json");
        assertThat(Files.isRegularFile(file)).isTrue();

        // Resume
        WorkflowRunJournal resumed = WorkflowRunJournal.resume(journal.runId(), tempDir);
        assertThat(resumed.runId()).isEqualTo(journal.runId());
        assertThat(resumed.getCached("key1")).isPresent().contains("result1");
        assertThat(resumed.getCached("key2")).isPresent();
        assertThat(resumed.getCached("nonexistent")).isEmpty();
    }

    @Test
    void computeCacheKeyIsDeterministic() {
        String key1 = WorkflowRunJournal.computeCacheKey("hello", Map.of("x", 1));
        String key2 = WorkflowRunJournal.computeCacheKey("hello", Map.of("x", 1));
        assertThat(key1).isEqualTo(key2);
        assertThat(key1).hasSize(16);
    }

    @Test
    void computeCacheKeyDiffersForDifferentInput() {
        String key1 = WorkflowRunJournal.computeCacheKey("hello", Map.of("x", 1));
        String key2 = WorkflowRunJournal.computeCacheKey("world", Map.of("x", 1));
        String key3 = WorkflowRunJournal.computeCacheKey("hello", Map.of("x", 2));
        assertThat(key1).isNotEqualTo(key2);
        assertThat(key1).isNotEqualTo(key3);
    }

    @Test
    void resumeThrowsForMissingJournal() {
        assertThatThrownBy(() -> WorkflowRunJournal.resume("wf_nonexist", tempDir))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not found");
    }
}
