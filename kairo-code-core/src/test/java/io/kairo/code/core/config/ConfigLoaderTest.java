package io.kairo.code.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void returnsEmptyPropertiesWhenFileDoesNotExist() {
        Path nonExistent = tempDir.resolve("config.properties");

        Properties props = ConfigLoader.load(nonExistent);

        assertThat(props).isEmpty();
    }

    @Test
    void parsesValidConfigFile() throws IOException {
        Path configFile = tempDir.resolve("config.properties");
        Files.writeString(configFile, "api-key=sk-test\nmodel=gpt-4o-mini\n");

        Properties props = ConfigLoader.load(configFile);

        assertThat(props.getProperty("api-key")).isEqualTo("sk-test");
        assertThat(props.getProperty("model")).isEqualTo("gpt-4o-mini");
    }

    @Test
    void ignoresCommentLines() throws IOException {
        Path configFile = tempDir.resolve("config.properties");
        Files.writeString(configFile, "# this is a comment\napi-key=sk-real\n");

        Properties props = ConfigLoader.load(configFile);

        assertThat(props.getProperty("api-key")).isEqualTo("sk-real");
        assertThat(props.stringPropertyNames()).doesNotContain("# this is a comment");
    }

    @Test
    void parsesAllSupportedKeys() throws IOException {
        Path configFile = tempDir.resolve("config.properties");
        Files.writeString(configFile,
                "api-key=sk-xyz\nmodel=qwen-max\nbase-url=https://example.com\nprovider=qianwen\n");

        Properties props = ConfigLoader.load(configFile);

        assertThat(props.getProperty("api-key")).isEqualTo("sk-xyz");
        assertThat(props.getProperty("model")).isEqualTo("qwen-max");
        assertThat(props.getProperty("base-url")).isEqualTo("https://example.com");
        assertThat(props.getProperty("provider")).isEqualTo("qianwen");
    }

    @Test
    void returnsEmptyPropertiesForNullPath() {
        Properties props = ConfigLoader.load(null);

        assertThat(props).isEmpty();
    }

    @Test
    void loadAsMapNormalizesHyphenKeysToCamelCase() throws IOException {
        Path configFile = tempDir.resolve("config.properties");
        Files.writeString(configFile,
                "api-key=sk-xyz\nbase-url=https://glm.example/v4\nchat-path=/chat/completions\nprovider=glm\n");

        Map<String, String> map = ConfigLoader.loadAsMap(configFile);

        assertThat(map)
                .containsEntry("apiKey", "sk-xyz")
                .containsEntry("baseUrl", "https://glm.example/v4")
                .containsEntry("chatPath", "/chat/completions")
                .containsEntry("provider", "glm");
    }

    @Test
    void loadAsMapSkipsBlankValues() throws IOException {
        Path configFile = tempDir.resolve("config.properties");
        Files.writeString(configFile, "api-key=\nmodel=gpt-4o\n");

        Map<String, String> map = ConfigLoader.loadAsMap(configFile);

        assertThat(map).doesNotContainKey("apiKey").containsEntry("model", "gpt-4o");
    }

    @Test
    void saveRoundTripsCamelCaseThroughHyphenatedFile() throws IOException {
        Path configFile = tempDir.resolve("config.properties");

        ConfigLoader.save(configFile, Map.of(
                "apiKey", "sk-test",
                "baseUrl", "https://glm.example/v4",
                "provider", "glm",
                "model", "glm-5.1"));

        // On disk: hyphenated keys.
        String onDisk = Files.readString(configFile);
        assertThat(onDisk).contains("api-key=sk-test").contains("base-url=https\\://glm.example/v4");

        // Round-trip: camelCase again on load.
        Map<String, String> reloaded = ConfigLoader.loadAsMap(configFile);
        assertThat(reloaded)
                .containsEntry("apiKey", "sk-test")
                .containsEntry("baseUrl", "https://glm.example/v4")
                .containsEntry("provider", "glm")
                .containsEntry("model", "glm-5.1");
    }

    @Test
    void saveOverwritesExistingFile() throws IOException {
        Path configFile = tempDir.resolve("config.properties");

        ConfigLoader.save(configFile, Map.of("apiKey", "sk-old"));
        ConfigLoader.save(configFile, Map.of("apiKey", "sk-new"));

        assertThat(ConfigLoader.loadAsMap(configFile)).containsEntry("apiKey", "sk-new");
    }

    @Test
    void saveCreatesParentDirectoryWhenMissing() throws IOException {
        Path nested = tempDir.resolve("subdir/config.properties");

        ConfigLoader.save(nested, Map.of("apiKey", "sk-test"));

        assertThat(Files.exists(nested)).isTrue();
    }
}
