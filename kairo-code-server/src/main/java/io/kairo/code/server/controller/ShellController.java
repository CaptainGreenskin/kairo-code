package io.kairo.code.server.controller;

import io.kairo.code.server.config.ServerConfig.ServerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * STOMP shell bridge — one persistent bash process per shell session.
 *
 * Client endpoints (under /app prefix):
 *   /app/shell/create  → starts bash, returns shellId via /topic/shell/{shellId}/meta
 *   /app/shell/input   → writes line to bash stdin
 *   /app/shell/close   → kills bash process
 *
 * Server pushes:
 *   /topic/shell/{shellId}/out  → stdout/stderr lines (ShellOutputEvent)
 *   /topic/shell/{shellId}/meta → control events {type: "ready"|"exit", shellId, exitCode?}
 */
@Controller
public class ShellController {

    private static final Logger log = LoggerFactory.getLogger(ShellController.class);
    private static final int MAX_SHELLS = 5;
    private static final long IDLE_TIMEOUT_MS = 30 * 60 * 1000; // 30 min

    public record CreateRequest(String shellId) {}
    public record InputRequest(String shellId, String line) {}
    public record CloseRequest(String shellId) {}
    public record ShellOutputEvent(String shellId, String line, boolean isError) {}
    public record MetaEvent(String type, String shellId, Integer exitCode) {}

    private record ShellEntry(Process process, PrintWriter stdin, long createdAt) {}

    private final ConcurrentHashMap<String, ShellEntry> shells = new ConcurrentHashMap<>();
    private final SimpMessagingTemplate messagingTemplate;
    private final Path workingDir;
    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor();

    public ShellController(SimpMessagingTemplate messagingTemplate, ServerProperties props) {
        this.messagingTemplate = messagingTemplate;
        this.workingDir = Paths.get(props.workingDir());
        // Periodic cleanup of dead/idle shells
        cleaner.scheduleAtFixedRate(this::cleanupDeadShells, 5, 5, TimeUnit.MINUTES);
    }

    @MessageMapping("/shell/create")
    public void createShell(@Payload CreateRequest req) {
        if (shells.size() >= MAX_SHELLS) {
            log.warn("Max shell limit reached, rejecting create for {}", req.shellId());
            return;
        }
        String shellId = (req.shellId() != null && !req.shellId().isBlank())
                ? req.shellId() : UUID.randomUUID().toString();
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "--norc")
                    .directory(workingDir.toFile())
                    .redirectErrorStream(false);
            pb.environment().put("TERM", "xterm-256color");
            pb.environment().put("PS1", "");  // suppress prompt clutter

            Process proc = pb.start();
            PrintWriter stdin = new PrintWriter(new OutputStreamWriter(proc.getOutputStream()), true);
            shells.put(shellId, new ShellEntry(proc, stdin, System.currentTimeMillis()));

            // stdout reader thread
            startReader(shellId, proc.getInputStream(), false);
            // stderr reader thread
            startReader(shellId, proc.getErrorStream(), true);

            // Watch for process exit
            Thread exitWatcher = new Thread(() -> {
                try {
                    int code = proc.waitFor();
                    shells.remove(shellId);
                    messagingTemplate.convertAndSend(
                            "/topic/shell/" + shellId + "/meta",
                            new MetaEvent("exit", shellId, code));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "shell-exit-" + shellId);
            exitWatcher.setDaemon(true);
            exitWatcher.start();

            messagingTemplate.convertAndSend(
                    "/topic/shell/" + shellId + "/meta",
                    new MetaEvent("ready", shellId, null));

        } catch (IOException e) {
            log.error("Failed to start shell", e);
        }
    }

    @MessageMapping("/shell/input")
    public void sendInput(@Payload InputRequest req) {
        ShellEntry entry = shells.get(req.shellId());
        if (entry == null || !entry.process().isAlive()) return;
        entry.stdin().println(req.line());
    }

    @MessageMapping("/shell/close")
    public void closeShell(@Payload CloseRequest req) {
        ShellEntry entry = shells.remove(req.shellId());
        if (entry != null) entry.process().destroyForcibly();
    }

    private void startReader(String shellId, InputStream stream, boolean isError) {
        Thread readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    messagingTemplate.convertAndSend(
                            "/topic/shell/" + shellId + "/out",
                            new ShellOutputEvent(shellId, line, isError));
                }
            } catch (IOException e) {
                log.debug("Shell {} reader closed: {}", shellId, e.getMessage());
            }
        }, "shell-reader-" + shellId + (isError ? "-err" : "-out"));
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void cleanupDeadShells() {
        long now = System.currentTimeMillis();
        shells.entrySet().removeIf(entry -> {
            boolean dead = !entry.getValue().process().isAlive();
            boolean idle = (now - entry.getValue().createdAt()) > IDLE_TIMEOUT_MS;
            if (dead || idle) {
                entry.getValue().process().destroyForcibly();
                return true;
            }
            return false;
        });
    }

    @PreDestroy
    public void shutdown() {
        shells.values().forEach(e -> e.process().destroyForcibly());
        cleaner.shutdownNow();
    }
}
