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

    // ── Basic loading ──

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
        assertThat(result.get()).contains("Child rules.");
    }

    @Test
    void prefersKairoMdOverClaudeMd() throws IOException {
        Files.writeString(tempDir.resolve("KAIRO.md"), "Kairo rules.");
        Files.writeString(tempDir.resolve("CLAUDE.md"), "Claude rules.");

        Optional<String> result = KairoMdLoader.findAndLoad(tempDir);

        assertThat(result).isPresent();
        assertThat(result.get()).contains("Kairo rules.");
    }

    @Test
    void fallsBackToClaudeMdWhenNoKairoMd() throws IOException {
        Files.writeString(tempDir.resolve("CLAUDE.md"), "Claude rules.");

        Optional<String> result = KairoMdLoader.findAndLoad(tempDir);

        assertThat(result).isPresent();
        assertThat(result.get()).contains("Claude rules.");
    }

    @Test
    void findsClaudeMdInParentDir() throws IOException {
        Path childDir = tempDir.resolve("sub/deep");
        Files.createDirectories(childDir);
        Files.writeString(tempDir.resolve("CLAUDE.md"), "Parent Claude rules.");

        Optional<String> result = KairoMdLoader.findAndLoad(childDir);

        assertThat(result).isPresent();
        assertThat(result.get()).contains("Parent Claude rules.");
    }

    @Test
    void returnsEmptyWhenNeitherFileExists() {
        Optional<String> result = KairoMdLoader.findAndLoad(tempDir);

        assertThat(result).isEmpty();
    }

    // ── @import directive ──

    @Test
    void importDirective_includesReferencedFile() throws IOException {
        Path docs = tempDir.resolve("docs");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("security.md"), "No SQL injection.");
        Files.writeString(tempDir.resolve("KAIRO.md"), "# Rules\n@import docs/security.md\nEnd.");

        Optional<String> result = KairoMdLoader.findAndLoad(tempDir);

        assertThat(result).isPresent();
        assertThat(result.get()).contains("# Rules");
        assertThat(result.get()).contains("No SQL injection.");
        assertThat(result.get()).contains("End.");
    }

    @Test
    void importDirective_multipleImports() throws IOException {
        Files.writeString(tempDir.resolve("a.md"), "Content A.");
        Files.writeString(tempDir.resolve("b.md"), "Content B.");
        Files.writeString(tempDir.resolve("KAIRO.md"), "@import a.md\n@import b.md");

        Optional<String> result = KairoMdLoader.findAndLoad(tempDir);

        assertThat(result).isPresent();
        assertThat(result.get()).contains("Content A.");
        assertThat(result.get()).contains("Content B.");
    }

    @Test
    void importDirective_nestedImports() throws IOException {
        Files.writeString(tempDir.resolve("inner.md"), "Inner content.");
        Files.writeString(tempDir.resolve("outer.md"), "Before.\n@import inner.md\nAfter.");
        Files.writeString(tempDir.resolve("KAIRO.md"), "@import outer.md");

        Optional<String> result = KairoMdLoader.findAndLoad(tempDir);

        assertThat(result).isPresent();
        assertThat(result.get()).contains("Before.");
        assertThat(result.get()).contains("Inner content.");
        assertThat(result.get()).contains("After.");
    }

    @Test
    void importDirective_circularReferenceDetected() throws IOException {
        Files.writeString(tempDir.resolve("a.md"), "A content.\n@import b.md");
        Files.writeString(tempDir.resolve("b.md"), "B content.\n@import a.md");
        Files.writeString(tempDir.resolve("KAIRO.md"), "Base.\n@import a.md");

        Optional<String> result = KairoMdLoader.findAndLoad(tempDir);

        assertThat(result).isPresent();
        assertThat(result.get()).contains("Base.");
        assertThat(result.get()).contains("A content.");
        assertThat(result.get()).contains("B content.");
    }

    @Test
    void importDirective_maxDepthEnforced() throws IOException {
        // Create chain: d0 -> d1 -> d2 -> d3 -> d4 -> d5 -> d6
        for (int i = 0; i < 7; i++) {
            String content = i < 6 ? "@import d" + (i + 1) + ".md" : "Deepest.";
            Files.writeString(tempDir.resolve("d" + i + ".md"), content);
        }
        Files.writeString(tempDir.resolve("KAIRO.md"), "@import d0.md");

        Optional<String> result = KairoMdLoader.findAndLoad(tempDir);

        assertThat(result).isPresent();
        // depth 0→d0, 1→d1, 2→d2, 3→d3, 4→d4 (depth=5 stops), d5 not resolved
        assertThat(result.get()).doesNotContain("Deepest.");
    }

    @Test
    void importDirective_missingFileSkipped() throws IOException {
        Files.writeString(tempDir.resolve("KAIRO.md"), "Before.\n@import nonexistent.md\nAfter.");

        Optional<String> result = KairoMdLoader.findAndLoad(tempDir);

        assertThat(result).isPresent();
        assertThat(result.get()).contains("Before.");
        assertThat(result.get()).contains("After.");
        assertThat(result.get()).doesNotContain("@import");
    }

    @Test
    void importDirective_preservesNonImportAtLines() throws IOException {
        Files.writeString(tempDir.resolve("KAIRO.md"), "Contact @admin for help.");

        Optional<String> result = KairoMdLoader.findAndLoad(tempDir);

        assertThat(result).isPresent();
        assertThat(result.get()).contains("Contact @admin for help.");
    }

    @Test
    void importDirective_worksWithClaudeMd() throws IOException {
        Files.writeString(tempDir.resolve("extra.md"), "Extra rules.");
        Files.writeString(tempDir.resolve("CLAUDE.md"), "Base.\n@import extra.md");

        Optional<String> result = KairoMdLoader.findAndLoad(tempDir);

        assertThat(result).isPresent();
        assertThat(result.get()).contains("Base.");
        assertThat(result.get()).contains("Extra rules.");
    }

    // ── .kairo-code/rules/ directory ──

    @Test
    void rulesDir_appendsAllMdFiles() throws IOException {
        Path rulesDir = tempDir.resolve(".kairo-code/rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("01-security.md"), "No secrets in code.");
        Files.writeString(rulesDir.resolve("02-style.md"), "Use 4-space indent.");
        Files.writeString(tempDir.resolve("KAIRO.md"), "# Project");

        Optional<String> result = KairoMdLoader.findAndLoad(tempDir);

        assertThat(result).isPresent();
        assertThat(result.get()).contains("# Project");
        assertThat(result.get()).contains("No secrets in code.");
        assertThat(result.get()).contains("Use 4-space indent.");
    }

    @Test
    void rulesDir_sortedAlphabetically() throws IOException {
        Path rulesDir = tempDir.resolve(".kairo-code/rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("b-second.md"), "SECOND");
        Files.writeString(rulesDir.resolve("a-first.md"), "FIRST");
        Files.writeString(rulesDir.resolve("c-third.md"), "THIRD");
        Files.writeString(tempDir.resolve("KAIRO.md"), "BASE");

        Optional<String> result = KairoMdLoader.findAndLoad(tempDir);

        assertThat(result).isPresent();
        String content = result.get();
        int firstIdx = content.indexOf("FIRST");
        int secondIdx = content.indexOf("SECOND");
        int thirdIdx = content.indexOf("THIRD");
        assertThat(firstIdx).isLessThan(secondIdx);
        assertThat(secondIdx).isLessThan(thirdIdx);
    }

    @Test
    void rulesDir_ignoresNonMdFiles() throws IOException {
        Path rulesDir = tempDir.resolve(".kairo-code/rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("valid.md"), "Valid rule.");
        Files.writeString(rulesDir.resolve("ignored.txt"), "Should not appear.");
        Files.writeString(tempDir.resolve("KAIRO.md"), "BASE");

        Optional<String> result = KairoMdLoader.findAndLoad(tempDir);

        assertThat(result).isPresent();
        assertThat(result.get()).contains("Valid rule.");
        assertThat(result.get()).doesNotContain("Should not appear.");
    }

    @Test
    void rulesDir_skipsBlankFiles() throws IOException {
        Path rulesDir = tempDir.resolve(".kairo-code/rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("empty.md"), "   \n  ");
        Files.writeString(rulesDir.resolve("real.md"), "Real rule.");
        Files.writeString(tempDir.resolve("KAIRO.md"), "BASE");

        Optional<String> result = KairoMdLoader.findAndLoad(tempDir);

        assertThat(result).isPresent();
        assertThat(result.get()).contains("Real rule.");
    }

    @Test
    void rulesDir_emptyDirNoEffect() throws IOException {
        Path rulesDir = tempDir.resolve(".kairo-code/rules");
        Files.createDirectories(rulesDir);
        Files.writeString(tempDir.resolve("KAIRO.md"), "Only base.");

        Optional<String> result = KairoMdLoader.findAndLoad(tempDir);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("Only base.");
    }

    @Test
    void rulesDir_missingDirNoEffect() throws IOException {
        Files.writeString(tempDir.resolve("KAIRO.md"), "Only base.");

        Optional<String> result = KairoMdLoader.findAndLoad(tempDir);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("Only base.");
    }

    // ── Combined ──

    @Test
    void combinedImportAndRules() throws IOException {
        Files.writeString(tempDir.resolve("extra.md"), "Imported content.");
        Path rulesDir = tempDir.resolve(".kairo-code/rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("rule.md"), "Rule content.");
        Files.writeString(tempDir.resolve("KAIRO.md"), "Base.\n@import extra.md");

        Optional<String> result = KairoMdLoader.findAndLoad(tempDir);

        assertThat(result).isPresent();
        String content = result.get();
        assertThat(content).contains("Base.");
        assertThat(content).contains("Imported content.");
        assertThat(content).contains("Rule content.");
        int importIdx = content.indexOf("Imported content.");
        int ruleIdx = content.indexOf("Rule content.");
        assertThat(importIdx).isLessThan(ruleIdx);
    }
}
