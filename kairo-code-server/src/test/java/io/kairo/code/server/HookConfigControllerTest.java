package io.kairo.code.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kairo.code.server.config.ServerConfig.ServerProperties;
import io.kairo.code.server.controller.HookConfigController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class HookConfigControllerTest {

    @TempDir
    Path tempDir;
    HookConfigController controller;

    @BeforeEach
    void setUp() {
        ServerProperties props = new ServerProperties(
                "openai", "gpt-4o", tempDir.toString(),
                "https://api.openai.com", "sk-test");
        controller = new HookConfigController(props, new ObjectMapper());
    }

    @Test
    void listHooks_allEnabledByDefault() throws IOException {
        var hooks = controller.listHooks();
        assertThat(hooks).isNotEmpty();
        assertThat(hooks.stream().allMatch(HookConfigController.HookInfo::enabled)).isTrue();
    }

    @Test
    void toggle_disablesEnabledHook() throws IOException {
        var result = controller.toggle("AutoCommitOnSuccessHook");
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().enabled()).isFalse();
    }

    @Test
    void toggle_twice_reEnables() throws IOException {
        controller.toggle("AutoCommitOnSuccessHook");
        var result = controller.toggle("AutoCommitOnSuccessHook");
        assertThat(result.getBody().enabled()).isTrue();
    }

    @Test
    void toggle_unknownHook_returns404() throws IOException {
        assertThat(controller.toggle("UnknownHook").getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void listHooks_reflectsPersistedState() throws IOException {
        controller.toggle("TextOnlyStallHook");
        var hooks = controller.listHooks();
        assertThat(hooks.stream().filter(h -> h.name().equals("TextOnlyStallHook")).findFirst().get().enabled()).isFalse();
    }
}
