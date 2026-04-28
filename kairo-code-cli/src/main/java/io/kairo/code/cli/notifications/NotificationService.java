package io.kairo.code.cli.notifications;

import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends OS-level desktop notifications on macOS (osascript) and Linux (notify-send).
 *
 * <p>Fires asynchronously in a daemon thread; never blocks the caller. Failures are logged at
 * DEBUG — notification errors must never surface to the user.
 */
public final class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final int TIMEOUT_SECS = 3;

    private NotificationService() {}

    /**
     * Send a desktop notification. No-op on unsupported platforms or when the required binary is
     * absent.
     */
    public static void notify(String title, String message) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            sendMacOs(title, message);
        } else if (os.contains("linux") || os.contains("nix") || os.contains("nux")) {
            sendLinux(title, message);
        }
        // Other platforms: silently ignored
    }

    private static void sendMacOs(String title, String message) {
        String script =
                "display notification \""
                        + escape(message)
                        + "\" with title \""
                        + escape(title)
                        + "\"";
        runAsync(new String[] {"osascript", "-e", script});
    }

    private static void sendLinux(String title, String message) {
        if (!commandExists("notify-send")) {
            log.debug("notify-send not found, skipping notification");
            return;
        }
        runAsync(new String[] {"notify-send", title, message});
    }

    private static void runAsync(String[] cmd) {
        Thread t = new Thread(() -> execute(cmd));
        t.setDaemon(true);
        t.setName("kairo-notify");
        t.start();
    }

    static void execute(String[] cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            boolean done = p.waitFor(TIMEOUT_SECS, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                log.debug("Notification process timed out after {} seconds", TIMEOUT_SECS);
            }
        } catch (Exception e) {
            log.debug("Notification failed: {}", e.getMessage());
        }
    }

    static boolean commandExists(String cmd) {
        try {
            Process p = new ProcessBuilder("which", cmd).redirectErrorStream(true).start();
            return p.waitFor(1, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Escape double-quotes and backslashes for use inside a shell string literal. */
    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
