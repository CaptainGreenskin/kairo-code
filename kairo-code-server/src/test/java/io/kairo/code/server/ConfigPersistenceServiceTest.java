package io.kairo.code.server;

import io.kairo.code.server.config.ConfigPersistenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigPersistenceServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void load_missingFile_returnsEmptyMap() {
        Path configFile = tempDir.resolve("config.properties");
        ConfigPersistenceService service = new ConfigPersistenceService(configFile);

        assertThat(service.load()).isEmpty();
    }

    @Test
    void save_thenLoad_roundtrip() throws Exception {
        Path configFile = tempDir.resolve("config.properties");
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
        Path configFile = tempDir.resolve("config.properties");
        ConfigPersistenceService service = new ConfigPersistenceService(configFile);

        service.save(Map.of("apiKey", "sk-only"));

        assertThat(configFile).exists();
        assertThat(configFile.getFileName().toString()).isEqualTo("config.properties");
    }

    @Test
    void save_overwritesExistingConfig() throws Exception {
        Path configFile = tempDir.resolve("config.properties");
        ConfigPersistenceService service = new ConfigPersistenceService(configFile);

        service.save(Map.of("model", "gpt-4o"));
        service.save(Map.of("model", "gpt-4-turbo"));

        assertThat(service.load()).containsEntry("model", "gpt-4-turbo");
    }

    @Test
    void load_readsHyphenatedKeysWrittenByCli() throws Exception {
        // Simulates the case the bug report uncovered: CLI writes hyphenated keys
        // (api-key, base-url) directly into ~/.kairo-code/config.properties; the
        // server must surface them as camelCase so /api/config reflects reality.
        Path configFile = tempDir.resolve("config.properties");
        Files.writeString(configFile,
                "api-key=sk-glm\nbase-url=https\\://open.bigmodel.cn/api/coding/paas/v4\nprovider=glm\nmodel=glm-5.1\n");

        Map<String, String> loaded = new ConfigPersistenceService(configFile).load();

        assertThat(loaded)
                .containsEntry("apiKey", "sk-glm")
                .containsEntry("baseUrl", "https://open.bigmodel.cn/api/coding/paas/v4")
                .containsEntry("provider", "glm")
                .containsEntry("model", "glm-5.1");
    }
}
