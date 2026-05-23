package io.kairo.code.server.config;

import io.kairo.code.core.config.ConfigLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Thin Spring wrapper around {@link ConfigLoader} so the controller can keep
 * being constructor-injected. All persistence logic lives in core — this class
 * exists only to bind the default {@code ~/.kairo-code/config.properties} path
 * and stay swappable for tests.
 */
@Component
public class ConfigPersistenceService {

    private final Path configPath;

    public ConfigPersistenceService() {
        this(ConfigLoader.defaultConfigPath());
    }

    public ConfigPersistenceService(Path configPath) {
        this.configPath = configPath;
    }

    /** Read persisted config in camelCase form (empty map when file is absent). */
    public Map<String, String> load() {
        return ConfigLoader.loadAsMap(configPath);
    }

    /** Atomic, 600-perm write of the camelCase config. */
    public void save(Map<String, String> config) throws IOException {
        ConfigLoader.save(configPath, config);
    }
}
