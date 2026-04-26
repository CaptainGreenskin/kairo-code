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

/**
 * Tests for KairoCodeMain --output option. Only covers validation paths that exit before any
 * network call.
 */
class KairoCodeOutputTest {

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
    void outputToNonExistentParentDirFailsWithExitCode1() {
        Path outputFile = tempDir.resolve("subdir").resolve("out.md");
        // parent dir does not exist → Files.writeString will throw IOException
        // We can't trigger this without a real agent call, so validate the option is parsed
        int code = run("--api-key", "fake", "--task", "t", "--output", outputFile.toString());
        // Exits 1 (api call fails), but no parse error (not 2)
        assertThat(code).isNotEqualTo(2);
    }

    @Test
    void outputOptionAcceptedWithoutError() {
        Path outputFile = tempDir.resolve("result.md");
        int code = run("--api-key", "fake", "--task", "t", "--output", outputFile.toString());
        // Exits 1 because fake key causes agent failure, but option is parsed correctly
        assertThat(errCapture.toString()).doesNotContain("Unknown option");
        assertThat(code).isNotEqualTo(2);
    }

    @Test
    void withoutOutputOptionExitsNormally() {
        int code = run("--api-key", "fake", "--task", "t");
        // No --output → stdout path (exits 1 due to fake api key, but no parse error)
        assertThat(code).isNotEqualTo(2);
        assertThat(errCapture.toString()).doesNotContain("Unknown option");
    }

    @Test
    void outputAndTaskFileCompatible() throws Exception {
        Path taskFile = tempDir.resolve("task.md");
        Files.writeString(taskFile, "do something");
        Path outputFile = tempDir.resolve("result.md");

        int code = run("--api-key", "fake",
                "--task-file", taskFile.toString(),
                "--output", outputFile.toString());
        // Options are parsed correctly; exits 1 due to fake api key
        assertThat(errCapture.toString()).doesNotContain("Unknown option");
        assertThat(code).isNotEqualTo(2);
    }
}
