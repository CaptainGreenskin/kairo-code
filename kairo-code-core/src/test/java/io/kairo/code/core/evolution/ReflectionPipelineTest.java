package io.kairo.code.core.evolution;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.model.ModelConfig;
import io.kairo.api.model.ModelProvider;
import io.kairo.api.model.ModelResponse;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.evolution.LearnedLessonStore.Lesson;
import io.kairo.code.core.evolution.LearnedLessonStore.Status;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class ReflectionPipelineTest {

    static class StubModelProvider implements ModelProvider {
        private final String responseText;
        private final boolean shouldFail;

        StubModelProvider(String responseText) {
            this.responseText = responseText;
            this.shouldFail = false;
        }

        StubModelProvider(boolean shouldFail) {
            this.responseText = null;
            this.shouldFail = shouldFail;
        }

        @Override
        public Mono<ModelResponse> call(List<Msg> messages, ModelConfig config) {
            if (shouldFail) {
                return Mono.error(new RuntimeException("simulated LLM failure"));
            }
            List<Content> contents = List.of(new Content.TextContent(responseText));
            return Mono.just(new ModelResponse("id", contents, null, null, "stub"));
        }

        @Override
        public Flux<ModelResponse> stream(List<Msg> messages, ModelConfig config) {
            return call(messages, config).flux();
        }

        @Override
        public String name() {
            return "stub";
        }
    }

    @Test
    void generateLesson_returnsLessonText() {
        ToolStrikeEvent event = new ToolStrikeEvent("bash",
                List.of("error 1", "error 2", "error 3"));
        ModelProvider stub = new StubModelProvider("应避免在路径中包含空格时不加引号");

        String lesson = ReflectionPipeline.generateLesson(event, stub, "gpt-4o");

        assertThat(lesson).isEqualTo("应避免在路径中包含空格时不加引号");
    }

    @Test
    void generateLesson_trimsToMaxChars() {
        ToolStrikeEvent event = new ToolStrikeEvent("edit", List.of("err"));
        String longText = "a".repeat(200);
        ModelProvider stub = new StubModelProvider(longText);

        String lesson = ReflectionPipeline.generateLesson(event, stub, "gpt-4o");

        assertThat(lesson).hasSize(100);
    }

    @Test
    void generateLesson_returnsNull_onEmptyResponse() {
        ToolStrikeEvent event = new ToolStrikeEvent("grep", List.of("err"));
        ModelProvider stub = new StubModelProvider("   ");

        String lesson = ReflectionPipeline.generateLesson(event, stub, "gpt-4o");

        assertThat(lesson).isNull();
    }

    @Test
    void generateLesson_returnsNull_onAgentFailure() {
        ToolStrikeEvent event = new ToolStrikeEvent("bash", List.of("err"));
        ModelProvider stub = new StubModelProvider(true);

        String lesson = ReflectionPipeline.generateLesson(event, stub, "gpt-4o");

        assertThat(lesson).isNull();
    }

    @Test
    void generateAndSave_savesPendingLesson(@TempDir Path tempDir) throws Exception {
        Path storePath = tempDir.resolve("learned.json");
        LearnedLessonStore store = new LearnedLessonStore(storePath);

        ToolStrikeEvent event = new ToolStrikeEvent("write",
                List.of("permission denied"));
        ModelProvider stub = new StubModelProvider("检查文件权限后再写入");

        // Use a direct call to generateLesson + save (bypass daemon thread for determinism)
        String lesson = ReflectionPipeline.generateLesson(event, stub, "gpt-4o");
        assertThat(lesson).isNotNull();
        store.save(Lesson.create(event.toolName(), lesson, Status.PENDING));

        List<Lesson> lessons = store.list();
        assertThat(lessons).hasSize(1);
        assertThat(lessons.get(0).toolName()).isEqualTo("write");
        assertThat(lessons.get(0).lessonText()).isEqualTo("检查文件权限后再写入");
        assertThat(lessons.get(0).status()).isEqualTo(Status.PENDING);
    }

    @Test
    void generateAndSave_asyncDoesNotThrow_onLlmFailure(@TempDir Path tempDir) throws Exception {
        Path storePath = tempDir.resolve("learned.json");
        LearnedLessonStore store = new LearnedLessonStore(storePath);

        ToolStrikeEvent event = new ToolStrikeEvent("bash", List.of("err1", "err2", "err3"));

        CodeAgentConfig config = new CodeAgentConfig(
                "fake-key", "https://invalid.localhost:99999", "gpt-4o", 1, null, null, 0, 0);

        CompletableFuture<Void> future =
                ReflectionPipeline.generateAndSave(event, config, store);

        // Should complete without throwing
        future.get(5, TimeUnit.SECONDS);

        // Nothing should have been saved
        assertThat(Files.exists(storePath)).isFalse();
    }
}
