package io.kairo.code.core.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KairoMdLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void findsKairoMdInStartDir() throws IOException {
        Path kairoMd = tempDir.resolve("KAIRO.md");
        Files.writeString(kairoMd, "# Project Rules\nDo not break prod.");

        Optional<String> result = KairoMdLoader.findAndLoad(tempDir);

        assertThat(result).isPresent();
        assertThat(result.get()).contains("Project Rules").contains("Do not break prod.");
    }

    @Test
    void walksUpToFindKairoMd() throws IOException {
        Path parentDir = tempDir;
        Path childDir = parentDir.resolve("sub/dir");
        Files.createDirectories(childDir);
        Files.writeString(parentDir.resolve("KAIRO.md"), "Parent project instructions.");

        Optional<String> result = KairoMdLoader.findAndLoad(childDir);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("Parent project instructions.");
    }

    @Test
    void returnsEmptyWhenNoKairoMdFound() {
        Optional<String> result = KairoMdLoader.findAndLoad(tempDir);

        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyForBlankFile() throws IOException {
        Path kairoMd = tempDir.resolve("KAIRO.md");
        Files.writeString(kairoMd, "   \n  ");

        Optional<String> result = KairoMdLoader.findAndLoad(tempDir);

        assertThat(result).isEmpty();
    }

    @Test
    void trimsContentBeforeReturning() throws IOException {
        Path kairoMd = tempDir.resolve("KAIRO.md");
        Files.writeString(kairoMd, "\n\n  Project rules here.  \n\n");

        Optional<String> result = KairoMdLoader.findAndLoad(tempDir);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("Project rules here.");
    }

    @Test
    void stopsAtFirstKairoMdFound() throws IOException {
        Path childDir = tempDir.resolve("child");
        Files.createDirectories(childDir);
        Files.writeString(tempDir.resolve("KAIRO.md"), "Parent rules.");
        Files.writeString(childDir.resolve("KAIRO.md"), "Child rules.");

        Optional<String> result = KairoMdLoader.findAndLoad(childDir);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("Child rules.");
    }

    @Test
    void prefersKairoMdOverClaudeMd() throws IOException {
        Files.writeString(tempDir.resolve("KAIRO.md"), "Kairo rules.");
        Files.writeString(tempDir.resolve("CLAUDE.md"), "Claude rules.");

        Optional<String> result = KairoMdLoader.findAndLoad(tempDir);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("Kairo rules.");
    }

    @Test
    void fallsBackToClaudeMdWhenNoKairoMd() throws IOException {
        Files.writeString(tempDir.resolve("CLAUDE.md"), "Claude rules.");

        Optional<String> result = KairoMdLoader.findAndLoad(tempDir);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("Claude rules.");
    }

    @Test
    void findsClaudeMdInParentDir() throws IOException {
        Path childDir = tempDir.resolve("sub/deep");
        Files.createDirectories(childDir);
        Files.writeString(tempDir.resolve("CLAUDE.md"), "Parent Claude rules.");

        Optional<String> result = KairoMdLoader.findAndLoad(childDir);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("Parent Claude rules.");
    }

    @Test
    void returnsEmptyWhenNeitherFileExists() {
        Optional<String> result = KairoMdLoader.findAndLoad(tempDir);

        assertThat(result).isEmpty();
    }
}
