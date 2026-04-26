package io.kairo.code.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.code.cli.TaskExecutionLogger.LogEntry;
import io.kairo.code.cli.TaskExecutionLogger.Status;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskExecutionLoggerCrResultTest {

    @TempDir
    Path tempDir;

    @Test
    void crResultPassIsWrittenToLog() throws IOException {
        TaskExecutionLogger logger = TaskExecutionLogger.forDirectory(tempDir);

        logger.write(LogEntry.builder()
                .taskDescription("test task")
                .status(Status.SUCCESS)
                .crResult("PASS")
                .build());

        String content = readSingleLogFile();
        assertThat(content).contains("- CR结果：PASS");
    }

    @Test
    void crResultWarnIsWrittenToLog() throws IOException {
        TaskExecutionLogger logger = TaskExecutionLogger.forDirectory(tempDir);

        logger.write(LogEntry.builder()
                .taskDescription("test task")
                .status(Status.SUCCESS)
                .crResult("WARN")
                .build());

        assertThat(readSingleLogFile()).contains("- CR结果：WARN");
    }

    @Test
    void crResultFailIsWrittenToLog() throws IOException {
        TaskExecutionLogger logger = TaskExecutionLogger.forDirectory(tempDir);

        logger.write(LogEntry.builder()
                .taskDescription("test task")
                .status(Status.FAILED)
                .crResult("FAIL")
                .build());

        assertThat(readSingleLogFile()).contains("- CR结果：FAIL");
    }

    @Test
    void crResultNullProducesNoLine() throws IOException {
        TaskExecutionLogger logger = TaskExecutionLogger.forDirectory(tempDir);

        logger.write(LogEntry.builder()
                .taskDescription("test task")
                .status(Status.SUCCESS)
                .build()); // crResult not set → null

        assertThat(readSingleLogFile()).doesNotContain("CR结果");
    }

    private String readSingleLogFile() throws IOException {
        return Files.walk(tempDir)
                .filter(Files::isRegularFile)
                .findFirst()
                .map(p -> {
                    try { return Files.readString(p); }
                    catch (IOException e) { throw new RuntimeException(e); }
                })
                .orElseThrow(() -> new AssertionError("No log file found"));
    }
}
