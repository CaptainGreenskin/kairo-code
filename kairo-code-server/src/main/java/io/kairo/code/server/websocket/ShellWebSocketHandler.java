package io.kairo.code.server.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kairo.code.server.config.ServerConfig.ServerProperties;
import io.kairo.code.server.config.WorkspacePersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Raw WebSocket terminal bridge — one bash process per WebSocket session.
 *
 * <p>Uses {@code /usr/bin/script} (BSD/macOS) or {@code script -qfc} (Linux) to allocate
 * a real PTY so bash sees a controlling terminal — without that, there is no echo,
 * no PS1, no line editing.
 *
 * <p>The connection URL may include {@code ?workspaceId=xxx} so the shell starts in
 * the workspace's working directory.
 *
 * <p>Client → Server messages (JSON):
 *   {"type":"input","data":"ls -la\n"}
 *   {"type":"resize","cols":120,"rows":30}   (ignored — script doesn't propagate resize)
 *
 * <p>Server → Client messages (JSON):
 *   {"type":"data","data":"output text\r\n"}
 *   {"type":"exit","exitCode":0}
 */
public class ShellWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ShellWebSocketHandler.class);
    private static final boolean IS_MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    private final ObjectMapper mapper = new ObjectMapper();
    private final ServerProperties props;
    private final WorkspacePersistenceService workspaces;

    private final ConcurrentHashMap<String, Process> processes = new ConcurrentHashMap<>();

    public ShellWebSocketHandler(ServerProperties props, WorkspacePersistenceService workspaces) {
        this.props = props;
        this.workspaces = workspaces;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String workingDir = resolveWorkingDir(session);

        // Allocate a PTY via /usr/bin/script. Using bash -i so it acts as an interactive shell.
        // BSD (macOS):  script -q /dev/null bash -i
        // Linux:        script -qfc "bash -i" /dev/null
        ProcessBuilder pb;
        if (IS_MAC) {
            pb = new ProcessBuilder("/usr/bin/script", "-q", "/dev/null", "/bin/bash", "-i");
        } else {
            pb = new ProcessBuilder("/usr/bin/script", "-qfc", "/bin/bash -i", "/dev/null");
        }
        pb.directory(new File(workingDir)).redirectErrorStream(true);
        pb.environment().put("TERM", "xterm-256color");
        pb.environment().put("PS1", "\\w $ ");

        Process proc = pb.start();
        processes.put(session.getId(), proc);
        log.info("Shell started for session {} in {}", session.getId(), workingDir);

        Thread reader = new Thread(() -> {
            try (InputStream in = proc.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    String text = new String(buf, 0, n);
                    String msg = mapper.writeValueAsString(Map.of("type", "data", "data", text));
                    synchronized (session) {
                        if (session.isOpen()) {
                            session.sendMessage(new TextMessage(msg));
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Shell reader closed for session {}: {}", session.getId(), e.getMessage());
            } finally {
                int exitCode = 0;
                try { exitCode = proc.waitFor(); } catch (InterruptedException ignored) {}
                processes.remove(session.getId());
                try {
                    if (session.isOpen()) {
                        String msg = mapper.writeValueAsString(Map.of("type", "exit", "exitCode", exitCode));
                        session.sendMessage(new TextMessage(msg));
                        session.close();
                    }
                } catch (Exception ignored) {}
                log.info("Shell exited for session {} with code {}", session.getId(), exitCode);
            }
        }, "shell-reader-" + session.getId());
        reader.setDaemon(true);
        reader.start();
    }

    private String resolveWorkingDir(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri != null && uri.getQuery() != null) {
            for (String part : uri.getQuery().split("&")) {
                int eq = part.indexOf('=');
                if (eq > 0 && "workspaceId".equals(part.substring(0, eq))) {
                    String wsid = part.substring(eq + 1);
                    if (!wsid.isBlank()) {
                        var wsOpt = workspaces.findById(wsid);
                        if (wsOpt.isPresent()) {
                            return wsOpt.get().workingDir();
                        }
                        log.warn("Shell: workspaceId {} not found, falling back to default", wsid);
                    }
                }
            }
        }
        String dir = props.workingDir();
        if (dir == null || dir.isBlank()) dir = System.getProperty("user.dir");
        return dir;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<?, ?> msg = mapper.readValue(message.getPayload(), Map.class);
        String type = (String) msg.get("type");
        if ("input".equals(type)) {
            String data = (String) msg.get("data");
            Process proc = processes.get(session.getId());
            if (proc != null && proc.isAlive()) {
                proc.getOutputStream().write(data.getBytes());
                proc.getOutputStream().flush();
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Process proc = processes.remove(session.getId());
        if (proc != null) {
            proc.destroyForcibly();
            log.info("Shell destroyed for session {} (status: {})", session.getId(), status);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("Transport error for shell session {}: {}", session.getId(), exception.getMessage());
        afterConnectionClosed(session, CloseStatus.SERVER_ERROR);
    }
}
