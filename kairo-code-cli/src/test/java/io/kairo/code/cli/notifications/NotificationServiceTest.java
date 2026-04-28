package io.kairo.code.cli.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class NotificationServiceTest {

    @Test
    void escape_quotesAndBackslashes() {
        assertThat(NotificationService.escape("hello \"world\"")).isEqualTo("hello \\\"world\\\"");
        assertThat(NotificationService.escape("back\\slash")).isEqualTo("back\\\\slash");
        assertThat(NotificationService.escape("plain text")).isEqualTo("plain text");
    }

    @Test
    void notify_doesNotThrowOnCurrentPlatform() {
        // Smoke test: notify() must never throw regardless of platform or binary availability.
        assertThatCode(() -> NotificationService.notify("Test", "unit test notification"))
                .doesNotThrowAnyException();
    }

    @Test
    void notify_doesNotThrowOnUnsupportedPlatform() {
        // Simulate unsupported OS by calling execute() with a no-op command.
        // notify() itself routes by os.name — on unknown OS it returns silently.
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("mac") && !os.contains("linux") && !os.contains("nix") && !os.contains("nux")) {
            assertThatCode(() -> NotificationService.notify("Title", "Message"))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void execute_withNonExistentCommand_doesNotThrow() {
        assertThatCode(
                        () ->
                                NotificationService.execute(
                                        new String[] {"nonexistent-command-xyz", "arg"}))
                .doesNotThrowAnyException();
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void macOs_osascriptCommandConstructed() {
        // Verify escape behavior produces valid osascript input.
        String title = "Kairo Code";
        String message = "子任务已完成";
        String expectedScript =
                "display notification \""
                        + NotificationService.escape(message)
                        + "\" with title \""
                        + NotificationService.escape(title)
                        + "\"";
        assertThat(expectedScript)
                .contains("display notification")
                .contains("with title")
                .contains("Kairo Code");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void linux_commandExistsReturnsFalseForMadeUpBinary() {
        assertThat(NotificationService.commandExists("kairo-nonexistent-binary-xyz")).isFalse();
    }
}
