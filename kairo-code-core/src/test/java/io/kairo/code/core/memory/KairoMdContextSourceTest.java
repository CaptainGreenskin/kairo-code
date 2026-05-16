package io.kairo.code.core.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KairoMdContextSourceTest {

    @TempDir
    Path tempDir;

    @Test
    void nameIsKairoMd() {
        var source = new KairoMdContextSource(tempDir);
        assertThat(source.getName()).isEqualTo("kairo-md");
    }

    @Test
    void priorityIsCritical() {
        var source = new KairoMdContextSource(tempDir);
        assertThat(source.priority()).isEqualTo(5);
    }

    @Test
    void activeWhenWorkingDirIsSet() {
        var source = new KairoMdContextSource(tempDir);
        assertThat(source.isActive()).isTrue();
    }

    @Test
    void inactiveWhenWorkingDirIsNull() {
        var source = new KairoMdContextSource(null);
        assertThat(source.isActive()).isFalse();
    }

    @Test
    void collectReturnsEmptyWhenNoKairoMd() {
        var source = new KairoMdContextSource(tempDir);
        assertThat(source.collect()).isEmpty();
    }

    @Test
    void collectReturnsProjectInstructionsWithHeader() throws IOException {
        Files.writeString(tempDir.resolve("KAIRO.md"), "Do not break prod.");
        var source = new KairoMdContextSource(tempDir);

        String result = source.collect();

        assertThat(result).startsWith("## Project Instructions (KAIRO.md)");
        assertThat(result).contains("Do not break prod.");
    }

    @Test
    void collectCachesResult() throws IOException {
        Files.writeString(tempDir.resolve("KAIRO.md"), "Original content.");
        var source = new KairoMdContextSource(tempDir);

        String first = source.collect();
        Files.writeString(tempDir.resolve("KAIRO.md"), "Changed content.");
        String second = source.collect();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void invalidateCacheForcesReload() throws IOException {
        Files.writeString(tempDir.resolve("KAIRO.md"), "Original content.");
        var source = new KairoMdContextSource(tempDir);

        source.collect();
        Files.writeString(tempDir.resolve("KAIRO.md"), "Changed content.");
        source.invalidateCache();
        String result = source.collect();

        assertThat(result).contains("Changed content.");
    }

    @Test
    void collectIncludesImportsAndRules() throws IOException {
        Files.writeString(tempDir.resolve("extra.md"), "Imported rule.");
        Path rulesDir = tempDir.resolve(".kairo-code/rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("01-safety.md"), "Safety first.");
        Files.writeString(tempDir.resolve("KAIRO.md"), "Base.\n@import extra.md");

        var source = new KairoMdContextSource(tempDir);
        String result = source.collect();

        assertThat(result).contains("Base.");
        assertThat(result).contains("Imported rule.");
        assertThat(result).contains("Safety first.");
    }
}
