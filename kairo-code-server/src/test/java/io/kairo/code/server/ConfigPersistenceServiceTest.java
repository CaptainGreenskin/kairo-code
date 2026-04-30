package io.kairo.code.server;

import io.kairo.code.server.config.ConfigPersistenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigPersistenceServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void load_missingFile_returnsEmptyMap() {
        Path configFile = tempDir.resolve("config.json");
        ConfigPersistenceService service = new ConfigPersistenceService(configFile);

        assertThat(service.load()).isEmpty();
    }

    @Test
    void save_thenLoad_roundtrip() throws Exception {
        Path configFile = tempDir.resolve("config.json");
        ConfigPersistenceService service = new ConfigPersistenceService(configFile);

        Map<String, String> config = Map.of(
                "apiKey", "sk-test",
                "model", "gpt-4o",
                "provider", "openai"
        );
        service.save(config);

        Map<String, String> loaded = service.load();
        assertThat(loaded).containsEntry("apiKey", "sk-test");
        assertThat(loaded).containsEntry("model", "gpt-4o");
        assertThat(loaded).containsEntry("provider", "openai");
    }

    @Test
    void save_createsConfigFile() throws Exception {
        Path configFile = tempDir.resolve("config.json");
        ConfigPersistenceService service = new ConfigPersistenceService(configFile);

        service.save(Map.of("key", "value"));

        assertThat(configFile).exists();
        assertThat(configFile.getFileName().toString()).isEqualTo("config.json");
    }

    @Test
    void save_overwritesExistingConfig() throws Exception {
        Path configFile = tempDir.resolve("config.json");
        ConfigPersistenceService service = new ConfigPersistenceService(configFile);

        service.save(Map.of("model", "gpt-4o"));
        service.save(Map.of("model", "gpt-4-turbo"));

        assertThat(service.load()).containsEntry("model", "gpt-4-turbo");
    }
}
