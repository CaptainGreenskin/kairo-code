package io.kairo.code.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.code.cli.TaskExecutionLogger.LogEntry;
import io.kairo.code.cli.TaskExecutionLogger.Status;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskExecutionLoggerTest {

    @TempDir
    Path tempDir;

    @Test
    void disabledWhenLogDirNull() {
        TaskExecutionLogger logger = TaskExecutionLogger.forDirectory(null);
        assertThat(logger.isEnabled()).isFalse();
    }

    @Test
    void enabledWhenLogDirSet() {
        TaskExecutionLogger logger = TaskExecutionLogger.forDirectory(tempDir);
        assertThat(logger.isEnabled()).isTrue();
    }

    @Test
    void writesLogFileWithDateDirectory() throws IOException {
        TaskExecutionLogger logger = TaskExecutionLogger.forDirectory(tempDir);
        LocalDateTime start = LocalDateTime.of(2026, 4, 26, 2, 30, 0);
        LocalDateTime end = LocalDateTime.of(2026, 4, 26, 2, 47, 23);

        logger.write(LogEntry.builder()
                .startTime(start)
                .endTime(end)
                .status(Status.SUCCESS)
                .taskSource("tasks/queue/001-xxx.md")
                .taskDescription("Implement --task-file option")
                .agentOutput("Done. Added option and 4 tests.")
                .toolCallCount(5)
                .toolSuccessCount(5)
                .build());

        Path expectedFile = tempDir.resolve("2026-04-26").resolve("02-30-00-task.md");
        assertThat(expectedFile).exists();

        String content = Files.readString(expectedFile);
        assertThat(content).contains("状态：SUCCESS");
        assertThat(content).contains("tasks/queue/001-xxx.md");
        assertThat(content).contains("Implement --task-file option");
        assertThat(content).contains("总调用次数：5");
        assertThat(content).contains("失败：0");
    }

    @Test
    void createsDirectoryIfNotExists() throws IOException {
        Path nested = tempDir.resolve("deep").resolve("logs");
        TaskExecutionLogger logger = TaskExecutionLogger.forDirectory(nested);

        logger.write(LogEntry.builder()
                .taskDescription("test")
                .status(Status.FAILED)
                .errorMessage("build failed")
                .build());

        assertThat(nested).isDirectory();
        long fileCount = Files.walk(nested).filter(Files::isRegularFile).count();
        assertThat(fileCount).isEqualTo(1);
    }

    @Test
    void truncatesLongAgentOutput() throws IOException {
        String longOutput = "x".repeat(1000);
        TaskExecutionLogger logger = TaskExecutionLogger.forDirectory(tempDir);

        logger.write(LogEntry.builder()
                .taskDescription("test")
                .agentOutput(longOutput)
                .build());

        Path logFile = Files.walk(tempDir)
                .filter(Files::isRegularFile)
                .findFirst()
                .orElseThrow();
        String content = Files.readString(logFile);
        assertThat(content).contains("...");
        assertThat(content.indexOf("x".repeat(501))).isEqualTo(-1);
    }

    @Test
    void silentWhenDisabled() {
        TaskExecutionLogger logger = TaskExecutionLogger.forDirectory(null);
        // Should not throw even with null logDir
        logger.write(LogEntry.builder().taskDescription("test").build());
    }
}
