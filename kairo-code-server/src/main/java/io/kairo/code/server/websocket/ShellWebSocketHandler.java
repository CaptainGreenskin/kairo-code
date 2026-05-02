package io.kairo.code.server.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kairo.code.server.config.ServerConfig.ServerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Raw WebSocket terminal bridge — one bash process per WebSocket session.
 *
 * <p>Client → Server messages (JSON):
 *   {"type":"input","data":"ls -la\n"}
 *   {"type":"resize","cols":120,"rows":30}   (optional, ignored for now)
 *
 * <p>Server → Client messages (JSON):
 *   {"type":"data","data":"output text\r\n"}
 *   {"type":"exit","exitCode":0}
 */
public class ShellWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ShellWebSocketHandler.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final ServerProperties props;

    // sessionId → running bash process
    private final ConcurrentHashMap<String, Process> processes = new ConcurrentHashMap<>();

    public ShellWebSocketHandler(ServerProperties props) {
        this.props = props;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String workingDir = props.workingDir();
        if (workingDir == null || workingDir.isBlank()) {
            workingDir = System.getProperty("user.dir");
        }

        ProcessBuilder pb = new ProcessBuilder("bash", "--norc", "--noprofile")
                .directory(new File(workingDir))
                .redirectErrorStream(true);  // merge stderr into stdout for terminal display
        pb.environment().put("TERM", "xterm-256color");
        pb.environment().put("PS1", "$ ");

        Process proc = pb.start();
        processes.put(session.getId(), proc);
        log.info("Shell started for session {} in {}", session.getId(), workingDir);

        // Background thread: read bash output and send to WebSocket client
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
        // "resize" messages are ignored for now (no PTY)
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
