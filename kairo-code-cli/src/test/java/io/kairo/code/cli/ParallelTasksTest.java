package io.kairo.code.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class ParallelTasksTest {

    @TempDir
    Path tempDir;

    private final PrintStream originalErr = System.err;
    private final PrintStream originalOut = System.out;
    private ByteArrayOutputStream errCapture;
    private ByteArrayOutputStream outCapture;

    @BeforeEach
    void captureStreams() {
        errCapture = new ByteArrayOutputStream();
        outCapture = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errCapture));
        System.setOut(new PrintStream(outCapture));
    }

    @AfterEach
    void restoreStreams() {
        System.setErr(originalErr);
        System.setOut(originalOut);
    }

    private int run(String... args) {
        return new CommandLine(new KairoCodeMain()).execute(args);
    }

    @Test
    void taskListAndTaskAreMutuallyExclusive() {
        Path taskList = tempDir.resolve("tasks.jsonl");
        int code = run("--api-key", "fake",
                "--task", "hello",
                "--task-list", taskList.toString());
        assertThat(code).isEqualTo(1);
        assertThat(errCapture.toString()).contains("mutually exclusive");
    }

    @Test
    void taskListAndTaskFileAreMutuallyExclusive() throws Exception {
        Path taskFile = tempDir.resolve("task.md");
        Files.writeString(taskFile, "do something");
        Path taskList = tempDir.resolve("tasks.jsonl");

        int code = run("--api-key", "fake",
                "--task-file", taskFile.toString(),
                "--task-list", taskList.toString());
        assertThat(code).isEqualTo(1);
        assertThat(errCapture.toString()).contains("mutually exclusive");
    }

    @Test
    void nonExistentTaskListFileReturnsExitCode1() {
        int code = run("--api-key", "fake",
                "--task-list", "/nonexistent/tasks.jsonl");
        assertThat(code).isEqualTo(1);
        assertThat(errCapture.toString()).contains("task-list file not found");
    }

    @Test
    void emptyTaskListFileCompletesSuccessfully() throws Exception {
        Path taskList = tempDir.resolve("empty.jsonl");
        Files.writeString(taskList, "");

        int code = run("--api-key", "fake", "--task-list", taskList.toString());
        assertThat(code).isEqualTo(0);
        assertThat(errCapture.toString()).contains("empty");
    }

    @Test
    void taskListOptionAcceptedByParser() {
        Path taskList = tempDir.resolve("tasks.jsonl");
        // Non-existent file → exits 1 with file-not-found, not 2 (parse error)
        int code = run("--api-key", "fake", "--task-list", taskList.toString());
        assertThat(code).isNotEqualTo(2);
        assertThat(errCapture.toString()).doesNotContain("Unknown option");
    }
}
