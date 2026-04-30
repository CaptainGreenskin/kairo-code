package io.kairo.code.server.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Persists server configuration to ~/.kairo-code/config.json.
 * Uses atomic write (tmp → rename) to prevent corruption.
 */
@Component
public class ConfigPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(ConfigPersistenceService.class);

    private final Path configPath;
    private final ObjectMapper mapper = new ObjectMapper();

    public ConfigPersistenceService() {
        this.configPath = Path.of(System.getProperty("user.home"), ".kairo-code", "config.json");
    }

    public ConfigPersistenceService(Path configPath) {
        this.configPath = configPath;
    }

    /**
     * Read persisted config. Returns empty map if file doesn't exist.
     */
    public Map<String, String> load() {
        if (!Files.exists(configPath)) {
            return Collections.emptyMap();
        }
        try {
            String json = Files.readString(configPath);
            return mapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (IOException e) {
            log.warn("Failed to load config from {}: {}", configPath, e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Atomically write config to disk.
     * Creates parent directory with mode 700, file with mode 600.
     */
    public void save(Map<String, String> config) throws IOException {
        Path dir = configPath.getParent();
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            setPermissions(dir, "rwx------");
        }

        Path tmpPath = configPath.resolveSibling(configPath.getFileName() + ".tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(tmpPath.toFile(), config);
        Files.move(tmpPath, configPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        setPermissions(configPath, "rw-------");
    }

    private void setPermissions(Path path, String perms) {
        try {
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                Set<PosixFilePermission> permissions = PosixFilePermissions.fromString(perms);
                Files.setPosixFilePermissions(path, permissions);
            }
        } catch (IOException e) {
            log.debug("Could not set permissions on {}: {}", path, e.getMessage());
        }
    }
}
