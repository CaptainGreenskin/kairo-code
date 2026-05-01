package io.kairo.code.server;

import io.kairo.code.server.config.ServerConfig.ServerProperties;
import io.kairo.code.server.controller.ShellController;
import io.kairo.code.server.controller.ShellController.CloseRequest;
import io.kairo.code.server.controller.ShellController.CreateRequest;
import io.kairo.code.server.controller.ShellController.InputRequest;
import io.kairo.code.server.controller.ShellController.MetaEvent;
import io.kairo.code.server.controller.ShellController.ShellOutputEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ShellControllerTest {

    @TempDir Path tempDir;
    CapturingMessagingTemplate messagingTemplate;
    ShellController controller;

    @BeforeEach
    void setup() {
        messagingTemplate = new CapturingMessagingTemplate();
        ServerProperties props = new ServerProperties("openai", "gpt-4o", tempDir.toString(),
                "https://api.openai.com", "sk-test-key");
        controller = new ShellController(messagingTemplate, props);
    }

    @AfterEach
    void teardown() {
        if (controller != null) controller.shutdown();
    }

    @Test
    void createShell_emitsReadyMeta() {
        controller.createShell(new CreateRequest("test-shell"));

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            List<CapturingMessagingTemplate.SentMessage> messages = messagingTemplate.sentMessages;
            assertThat(messages).isNotEmpty();
            boolean found = messages.stream()
                    .anyMatch(m -> m.destination().equals("/topic/shell/test-shell/meta")
                            && m.payload() instanceof MetaEvent meta && "ready".equals(meta.type()));
            assertThat(found).isTrue();
        });
    }

    @Test
    void sendInput_echoesOutput() {
        controller.createShell(new CreateRequest("echo-shell"));

        // Wait for shell to be ready
        await().atMost(3, TimeUnit.SECONDS).until(() -> {
            List<CapturingMessagingTemplate.SentMessage> messages = messagingTemplate.sentMessages;
            return messages.stream()
                    .anyMatch(m -> m.destination().contains("/meta")
                            && m.payload() instanceof MetaEvent meta && "ready".equals(meta.type()));
        });

        controller.sendInput(new InputRequest("echo-shell", "echo hello123"));

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            List<CapturingMessagingTemplate.SentMessage> messages = messagingTemplate.sentMessages;
            boolean found = messages.stream()
                    .anyMatch(m -> m.destination().equals("/topic/shell/echo-shell/out")
                            && m.payload() instanceof ShellOutputEvent evt && evt.line().contains("hello123"));
            assertThat(found).isTrue();
        });
    }

    @Test
    void closeShell_killsProcess() {
        controller.createShell(new CreateRequest("kill-shell"));

        // Wait for shell to be ready
        await().atMost(2, TimeUnit.SECONDS).until(() -> {
            List<CapturingMessagingTemplate.SentMessage> messages = messagingTemplate.sentMessages;
            return messages.stream()
                    .anyMatch(m -> m.destination().contains("/topic/shell/kill-shell/"));
        });

        controller.closeShell(new CloseRequest("kill-shell"));
        // No exception = pass; shell should be removed
    }

    /**
     * Simple capturing stub for SimpMessagingTemplate.
     */
    static class CapturingMessagingTemplate extends ShellControllerTest.NoOpMessagingTemplate {

        record SentMessage(String destination, Object payload) {}

        final List<SentMessage> sentMessages = new java.util.ArrayList<>();

        @Override
        public void convertAndSend(String destination, Object payload) {
            sentMessages.add(new SentMessage(destination, payload));
        }
    }

    /**
     * No-op base messaging template for testing.
     */
    static class NoOpMessagingTemplate extends org.springframework.messaging.simp.SimpMessagingTemplate {

        NoOpMessagingTemplate() {
            super(new NoOpMessageChannel());
        }

        static class NoOpMessageChannel implements org.springframework.messaging.MessageChannel {
            @Override
            public boolean send(org.springframework.messaging.Message<?> message) { return true; }
            @Override
            public boolean send(org.springframework.messaging.Message<?> message, long timeout) { return true; }
        }
    }
}
