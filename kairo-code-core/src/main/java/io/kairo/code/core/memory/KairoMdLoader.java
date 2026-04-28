package io.kairo.code.core.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads a {@code KAIRO.md} file by walking up the directory tree from a starting point.
 *
 * <p>Returns the content of the first {@code KAIRO.md} found. If no file exists on the path to
 * root, returns {@link Optional#empty()}.
 */
public final class KairoMdLoader {

    private static final Logger log = LoggerFactory.getLogger(KairoMdLoader.class);
    private static final String FILENAME = "KAIRO.md";

    private KairoMdLoader() {}

    /**
     * Walk up from {@code startDir} looking for a {@code KAIRO.md} file.
     *
     * @return the file content as a string, or empty if not found
     */
    public static Optional<String> findAndLoad(Path startDir) {
        Path current = startDir.toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(FILENAME);
            if (Files.isRegularFile(candidate)) {
                try {
                    String content = Files.readString(candidate, StandardCharsets.UTF_8);
                    if (content.isBlank()) {
                        return Optional.empty();
                    }
                    return Optional.of(content.trim());
                } catch (IOException e) {
                    log.warn("Failed to read KAIRO.md at {}: {}", candidate, e.getMessage());
                    return Optional.empty();
                }
            }
            current = current.getParent();
        }
        return Optional.empty();
    }
}
