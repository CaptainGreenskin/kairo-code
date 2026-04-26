package io.kairo.code.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Loads {@code ~/.kairo-code/config.properties} for default CLI option values.
 *
 * <p>Priority order: CLI arg &gt; environment variable &gt; config file &gt; built-in default.
 */
public final class ConfigLoader {

    private ConfigLoader() {}

    /**
     * Load {@code ~/.kairo-code/config.properties}. Returns empty {@link Properties} if the file
     * does not exist or cannot be read.
     */
    public static Properties load() {
        return load(defaultConfigPath());
    }

    static Properties load(Path configFile) {
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

    static Path defaultConfigPath() {
        return Path.of(System.getProperty("user.home"), ".kairo-code", "config.properties");
    }
}
