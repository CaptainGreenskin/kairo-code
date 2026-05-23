package io.kairo.code.core.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/**
 * Single source of truth for {@code ~/.kairo-code/config.properties}.
 *
 * <p>Both the CLI and the HTTP server read defaults from this file so a key
 * written by {@code :config set api-key …} in the REPL is immediately visible
 * to the web UI on the next server start.
 *
 * <p>File format is {@link Properties}: hyphenated keys (e.g. {@code api-key},
 * {@code base-url}). In-memory, server code prefers camelCase ({@code apiKey},
 * {@code baseUrl}); {@link #loadAsMap()} and {@link #save(Path, Map)} handle the
 * round-trip transparently so callers never have to think about it.
 */
public final class ConfigLoader {

    public static final String FILE_NAME = "config.properties";

    /** Known hyphen→camelCase aliases. Unknown keys pass through verbatim. */
    private static final Map<String, String> HYPHEN_TO_CAMEL = Map.of(
            "api-key", "apiKey",
            "base-url", "baseUrl",
            "chat-path", "chatPath",
            "thinking-budget", "thinkingBudget"
    );

    private static final Map<String, String> CAMEL_TO_HYPHEN;
    static {
        Map<String, String> reverse = new HashMap<>();
        HYPHEN_TO_CAMEL.forEach((h, c) -> reverse.put(c, h));
        CAMEL_TO_HYPHEN = Collections.unmodifiableMap(reverse);
    }

    private ConfigLoader() {}

    /** Load {@code ~/.kairo-code/config.properties} as raw {@link Properties}. */
    public static Properties load() {
        return load(defaultConfigPath());
    }

    /** Test seam: load from a specific path. */
    public static Properties load(Path configFile) {
        Properties props = new Properties();
        if (configFile == null || !Files.exists(configFile)) {
            return props;
        }
        try (InputStream in = Files.newInputStream(configFile)) {
            props.load(in);
        } catch (IOException ignored) {
            // Best-effort: unreadable config is treated as absent
        }
        return props;
    }

    /**
     * Load and normalize keys to camelCase. Empty values are skipped so callers
     * can use {@code map.containsKey(...)} to mean "user actually set this".
     */
    public static Map<String, String> loadAsMap() {
        return loadAsMap(defaultConfigPath());
    }

    public static Map<String, String> loadAsMap(Path configFile) {
        Properties props = load(configFile);
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : props.stringPropertyNames()) {
            String value = props.getProperty(name);
            if (value == null || value.isBlank()) continue;
            result.put(HYPHEN_TO_CAMEL.getOrDefault(name, name), value);
        }
        return result;
    }

    /**
     * Atomic write of camelCase config to disk as hyphenated properties.
     * Creates parent dir 700 and file 600 on POSIX so secrets aren't world-readable.
     */
    public static void save(Path configFile, Map<String, String> camelCaseValues) throws IOException {
        Path dir = configFile.getParent();
        if (dir != null && !Files.exists(dir)) {
            Files.createDirectories(dir);
            setPermissions(dir, "rwx------");
        }

        // Deterministic key order so review-time diffs are clean.
        Properties props = new SortedProperties();
        for (Map.Entry<String, String> e : camelCaseValues.entrySet()) {
            if (e.getValue() == null) continue;
            String fileKey = CAMEL_TO_HYPHEN.getOrDefault(e.getKey(), e.getKey());
            props.setProperty(fileKey, e.getValue());
        }

        Path tmp = configFile.resolveSibling(configFile.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            props.store(out, "kairo-code config — managed by ConfigLoader, do not edit secrets here without 600 perms");
        }
        Files.move(tmp, configFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        setPermissions(configFile, "rw-------");
    }

    public static Path defaultConfigPath() {
        return Path.of(System.getProperty("user.home"), ".kairo-code", FILE_NAME);
    }

    private static void setPermissions(Path path, String perms) {
        try {
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                Set<PosixFilePermission> permissions = PosixFilePermissions.fromString(perms);
                Files.setPosixFilePermissions(path, permissions);
            }
        } catch (IOException ignored) {
            // Windows / non-POSIX FS — fall through silently.
        }
    }

    /** {@link Properties} that iterates keys in sorted order — for stable file output. */
    private static final class SortedProperties extends Properties {
        @Override
        public synchronized java.util.Enumeration<Object> keys() {
            return Collections.enumeration(new TreeMap<>(this).keySet());
        }
    }
}
