package io.kairo.code.server;

import io.kairo.code.core.evolution.LearnedLessonStore;
import io.kairo.code.server.config.ServerConfig.ServerProperties;
import io.kairo.code.server.controller.EvolutionController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvolutionControllerTest {

    @TempDir Path tempDir;
    EvolutionController controller;

    @BeforeEach
    void setUp() {
        ServerProperties props = new ServerProperties(
                "openai", "gpt-4o", tempDir.toString(),
                "https://api.openai.com", "sk-test");
        controller = new EvolutionController(props);
    }

    @Test
    void listLessons_returnsEmpty_initially() {
        assertThat(controller.listLessons()).isEmpty();
    }

    @Test
    void updateStatus_approvesLesson() {
        var store = new LearnedLessonStore(tempDir.resolve(".kairo-code").resolve("learned.json"));
        var lesson = LearnedLessonStore.Lesson.create("EditTool", "Always check file exists first", LearnedLessonStore.Status.PENDING);
        store.save(lesson);

        var result = controller.updateStatus(lesson.id(), Map.of("status", "APPROVED"));
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(controller.listLessons().stream()
            .filter(l -> l.id().equals(lesson.id()))
            .findFirst().get().status())
            .isEqualTo(LearnedLessonStore.Status.APPROVED);
    }

    @Test
    void deleteLesson_removesIt() {
        var store = new LearnedLessonStore(tempDir.resolve(".kairo-code").resolve("learned.json"));
        var lesson = LearnedLessonStore.Lesson.create("ReadTool", "Use offset+limit for large files", LearnedLessonStore.Status.PENDING);
        store.save(lesson);

        controller.deleteLesson(lesson.id());
        assertThat(controller.listLessons()).isEmpty();
    }

    @Test
    void updateStatus_invalidStatus_returns400() {
        assertThat(controller.updateStatus("nonexistent", Map.of("status", "BOGUS")).getStatusCode().value())
            .isEqualTo(400);
    }
}
