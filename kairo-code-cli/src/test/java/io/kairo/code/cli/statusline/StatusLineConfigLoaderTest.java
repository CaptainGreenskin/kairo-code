/*
 * Copyright 2025-2026 the Kairo authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.kairo.code.cli.statusline;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatusLineConfigLoaderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void noConfigFiles_returnsDisabled(@TempDir Path dir) {
        StatusLineConfigLoader loader =
                new StatusLineConfigLoader(
                        mapper, List.of(dir.resolve("absent.json")));
        StatusLineConfig cfg = loader.load();
        assertThat(cfg.isShellEnabled()).isFalse();
    }

    @Test
    void singleLayer_command_isLoaded(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("statusline.json");
        Files.writeString(
                file,
                """
                {"type":"command","command":"echo hello","refreshInterval":2,"timeoutMs":3000}
                """);
        StatusLineConfig cfg =
                new StatusLineConfigLoader(mapper, List.of(file)).load();
        assertThat(cfg.command()).isEqualTo("echo hello");
        assertThat(cfg.refreshInterval()).isEqualTo(Duration.ofMillis(2000));
        assertThat(cfg.timeout()).isEqualTo(Duration.ofMillis(3000));
        assertThat(cfg.isShellEnabled()).isTrue();
    }

    @Test
    void laterLayer_overridesEarlier(@TempDir Path dir) throws IOException {
        Path user = dir.resolve("user.json");
        Path project = dir.resolve("project.json");
        Files.writeString(user, "{\"command\":\"echo USER\"}");
        Files.writeString(project, "{\"command\":\"echo PROJECT\"}");
        StatusLineConfig cfg =
                new StatusLineConfigLoader(mapper, List.of(user, project)).load();
        assertThat(cfg.command()).isEqualTo("echo PROJECT");
    }

    @Test
    void laterLayer_blankCommand_doesNotOverrideEarlier(@TempDir Path dir) throws IOException {
        // A project-level config that overrides only `padding` shouldn't blow away the user's
        // command. Empty/blank command on the later layer should fall through.
        Path user = dir.resolve("user.json");
        Path project = dir.resolve("project.json");
        Files.writeString(user, "{\"command\":\"echo USER\"}");
        Files.writeString(project, "{\"padding\":4}");
        StatusLineConfig cfg =
                new StatusLineConfigLoader(mapper, List.of(user, project)).load();
        assertThat(cfg.command()).isEqualTo("echo USER");
        assertThat(cfg.padding()).isEqualTo(4);
    }

    @Test
    void malformedLayer_isIgnored_andRestAreLoaded(@TempDir Path dir) throws IOException {
        // A broken local override mustn't lock the user out of their statusline.
        Path good = dir.resolve("good.json");
        Path broken = dir.resolve("broken.json");
        Files.writeString(good, "{\"command\":\"echo OK\"}");
        Files.writeString(broken, "{this is not json");
        StatusLineConfig cfg =
                new StatusLineConfigLoader(mapper, List.of(good, broken)).load();
        assertThat(cfg.command()).isEqualTo("echo OK");
    }

    @Test
    void nonObjectLayer_isIgnored(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("arr.json");
        Files.writeString(file, "[1,2,3]");
        StatusLineConfig cfg =
                new StatusLineConfigLoader(mapper, List.of(file)).load();
        assertThat(cfg.isShellEnabled()).isFalse();
    }

    @Test
    void unreadableLayer_isIgnored(@TempDir Path dir) {
        // Absent file is treated as not-present (no exception).
        Path absent = dir.resolve("does-not-exist.json");
        StatusLineConfig cfg =
                new StatusLineConfigLoader(mapper, List.of(absent)).load();
        assertThat(cfg.isShellEnabled()).isFalse();
    }

    @Test
    void refreshInterval_fractionalSeconds_areHonored(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("frac.json");
        Files.writeString(file, "{\"command\":\"x\",\"refreshInterval\":0.5}");
        StatusLineConfig cfg =
                new StatusLineConfigLoader(mapper, List.of(file)).load();
        assertThat(cfg.refreshInterval()).isEqualTo(Duration.ofMillis(500));
    }
}
