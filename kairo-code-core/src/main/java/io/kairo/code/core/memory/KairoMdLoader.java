package io.kairo.code.core.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads a {@code KAIRO.md} file by walking up the directory tree from a starting point.
 * If no {@code KAIRO.md} is found, falls back to {@code CLAUDE.md}.
 *
 * <p>Supports two extension mechanisms:
 * <ul>
 *   <li><b>{@code @import path/to/file.md}</b> — inline directive that includes the
 *       referenced file's content, resolved relative to the directory containing the
 *       KAIRO.md file. Maximum recursion depth is 5.</li>
 *   <li><b>{@code .kairo-code/rules/}</b> — all {@code *.md} files in this directory
 *       (relative to the project root where KAIRO.md was found) are automatically appended
 *       in alphabetical order.</li>
 * </ul>
 */
public final class KairoMdLoader {

    private static final Logger log = LoggerFactory.getLogger(KairoMdLoader.class);
    private static final String FILENAME = "KAIRO.md";
    private static final String CLAUDE_FILENAME = "CLAUDE.md";
    private static final String RULES_DIR = ".kairo-code/rules";
    private static final int MAX_IMPORT_DEPTH = 5;
    private static final Pattern IMPORT_PATTERN = Pattern.compile("^@import\\s+(.+)$");

    private KairoMdLoader() {}

    /**
     * Walk up from {@code startDir} looking for a {@code KAIRO.md} file.
     * Falls back to {@code CLAUDE.md} if not found.
     *
     * <p>Processes {@code @import} directives and appends rules from
     * {@code .kairo-code/rules/} if that directory exists.
     *
     * @return the file content as a string, or empty if neither is found
     */
    public static Optional<String> findAndLoad(Path startDir) {
        FoundFile found = findFile(startDir, FILENAME);
        if (found == null) {
            found = findFile(startDir, CLAUDE_FILENAME);
        }
        if (found == null) {
            return Optional.empty();
        }

        String content = processImports(found.content, found.directory, 0, new HashSet<>());

        String rules = loadRulesDir(found.directory);
        if (!rules.isEmpty()) {
            content = content + "\n\n" + rules;
        }

        if (content.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(content.trim());
    }

    static String processImports(String content, Path baseDir, int depth, Set<Path> visited) {
        if (depth >= MAX_IMPORT_DEPTH) {
            log.warn("@import depth limit ({}) reached, skipping further imports", MAX_IMPORT_DEPTH);
            return content;
        }

        StringBuilder result = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            Matcher matcher = IMPORT_PATTERN.matcher(line.trim());
            if (matcher.matches()) {
                String importPath = matcher.group(1).trim();
                Path resolved = baseDir.resolve(importPath).normalize();
                if (visited.contains(resolved)) {
                    log.warn("Circular @import detected: {}, skipping", resolved);
                    continue;
                }
                if (Files.isRegularFile(resolved)) {
                    try {
                        String imported = Files.readString(resolved, StandardCharsets.UTF_8);
                        visited.add(resolved);
                        String processed =
                                processImports(imported, resolved.getParent(), depth + 1, visited);
                        result.append(processed);
                        if (!processed.endsWith("\n")) {
                            result.append('\n');
                        }
                    } catch (IOException e) {
                        log.warn("Failed to read @import file {}: {}", resolved, e.getMessage());
                    }
                } else {
                    log.warn("@import target not found: {}", resolved);
                }
            } else {
                result.append(line).append('\n');
            }
        }
        // Remove trailing newline added by split processing
        if (result.length() > 0 && result.charAt(result.length() - 1) == '\n') {
            result.setLength(result.length() - 1);
        }
        return result.toString();
    }

    static String loadRulesDir(Path projectRoot) {
        Path rulesDir = projectRoot.resolve(RULES_DIR);
        if (!Files.isDirectory(rulesDir)) {
            return "";
        }
        try (Stream<Path> files = Files.list(rulesDir)) {
            List<Path> mdFiles = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".md"))
                    .sorted()
                    .toList();
            if (mdFiles.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (Path mdFile : mdFiles) {
                try {
                    String ruleContent = Files.readString(mdFile, StandardCharsets.UTF_8).trim();
                    if (!ruleContent.isEmpty()) {
                        if (sb.length() > 0) {
                            sb.append("\n\n");
                        }
                        sb.append(ruleContent);
                    }
                } catch (IOException e) {
                    log.warn("Failed to read rule file {}: {}", mdFile, e.getMessage());
                }
            }
            return sb.toString();
        } catch (IOException e) {
            log.warn("Failed to scan rules directory {}: {}", rulesDir, e.getMessage());
            return "";
        }
    }

    private static FoundFile findFile(Path startDir, String filename) {
        Path current = startDir.toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(filename);
            if (Files.isRegularFile(candidate)) {
                try {
                    String content = Files.readString(candidate, StandardCharsets.UTF_8);
                    if (content.isBlank()) {
                        return null;
                    }
                    return new FoundFile(content.trim(), current);
                } catch (IOException e) {
                    log.warn("Failed to read {} at {}: {}", filename, candidate, e.getMessage());
                    return null;
                }
            }
            current = current.getParent();
        }
        return null;
    }

    private record FoundFile(String content, Path directory) {}
}
