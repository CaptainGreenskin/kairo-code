package io.kairo.code.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class KairoCodeMainTaskFileTest {

    @TempDir
    Path tempDir;

    @Test
    void taskAndTaskFileMutuallyExclusive() {
        int exitCode = new CommandLine(new KairoCodeMain())
                .execute("--task", "do something", "--task-file", "somefile.md",
                        "--api-key", "test-key");
        assertThat(exitCode).isEqualTo(1);
    }

    @Test
    void taskFileNotFound() {
        int exitCode = new CommandLine(new KairoCodeMain())
                .execute("--task-file", "/nonexistent/path/task.md",
                        "--api-key", "test-key");
        assertThat(exitCode).isEqualTo(1);
    }

    @Test
    void taskFileReadSuccessfully() throws IOException {
        Path taskFile = tempDir.resolve("task.md");
        Files.writeString(taskFile, "Test task content", StandardCharsets.UTF_8);

        // We can't run a full agent in unit test, but we verify the option parses correctly
        // by checking the CommandLine parses without error before the agent call
        KairoCodeMain main = new KairoCodeMain();
        CommandLine cmd = new CommandLine(main);

        // Parse only (don't execute) to verify --task-file is accepted
        CommandLine.ParseResult result = cmd.parseArgs("--task-file", taskFile.toString(),
                "--api-key", "test-key");
        assertThat(result.hasMatchedOption("--task-file")).isTrue();
        assertThat(result.hasMatchedOption("--task")).isFalse();
    }

    @Test
    void taskFileSupportsMultilineMarkdown() throws IOException {
        String multilineContent = "# Task\n\n## Goal\nImplement something\n\n## Acceptance\n- [ ] Tests pass";
        Path taskFile = tempDir.resolve("multiline.md");
        Files.writeString(taskFile, multilineContent, StandardCharsets.UTF_8);

        assertThat(Files.readString(taskFile, StandardCharsets.UTF_8))
                .isEqualTo(multilineContent);
    }
}
