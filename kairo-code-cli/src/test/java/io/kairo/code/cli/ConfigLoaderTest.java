package io.kairo.code.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
