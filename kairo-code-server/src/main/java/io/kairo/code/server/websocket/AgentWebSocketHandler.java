package io.kairo.code.server.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.server.config.ServerConfig.ServerProperties;
import io.kairo.code.server.config.WorkspaceConfig;
import io.kairo.code.server.config.WorkspacePersistenceService;
import io.kairo.code.server.dto.CreateSessionResponse;
import io.kairo.code.service.AgentEvent;
import io.kairo.code.service.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Native WebSocket handler for agent traffic — replaces the STOMP/SockJS stack.
 *
 * <p>Wire protocol (JSON, both directions):
 *
 * <p>Client → Server:
 * <pre>
 *   {"action":"bind",     "sessionId":"..."}
 *   {"action":"create",   "workingDir":"...", "model":"...", "provider":"...", "apiKey":"..."}
 *   {"action":"message",  "sessionId":"...", "message":"...", "imageData":"...", "imageMediaType":"..."}
 *   {"action":"approve",  "sessionId":"...", "toolCallId":"...", "approved":true,  "reason":"..."}
 *   {"action":"stop",     "sessionId":"..."}
 * </pre>
 *
 * <p>Server → Client (events for the bound session arrive on this socket only):
 * <pre>
 *   {"type":"AGENT_THINKING", "sessionId":"...", ...}      // AgentEvent serialized
 *   {"type":"SESSION_CREATED","sessionId":"...","workingDir":"...","model":"..."}
 *   {"type":"ACK", "ok":true,  "action":"..."}             // optional ACK frames
 *   {"type":"ERR", "ok":false, "action":"...", "message":"..."}
 * </pre>
 */
@Component
public class AgentWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentWebSocketHandler.class);

    private static final String ATTR_SESSION_ID = "agentSessionId";

    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentService agentService;
    private final ServerProperties serverProperties;
    private final WorkspacePersistenceService workspaces;

    /** sessionId → WS sessions bound to it (multiple browser tabs supported). */
    private final ConcurrentHashMap<String, Set<WebSocketSession>> subscribers = new ConcurrentHashMap<>();

    public AgentWebSocketHandler(AgentService agentService,
                                 ServerProperties serverProperties,
                                 WorkspacePersistenceService workspaces) {
        this.agentService = agentService;
        this.serverProperties = serverProperties;
        this.workspaces = workspaces;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.debug("Agent WS connected: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode root = mapper.readTree(message.getPayload());
            String action = text(root, "action");
            if (action == null) {
                sendErr(session, null, "missing 'action'");
                return;
            }
            switch (action) {
                case "bind"    -> handleBind(session, root);
                case "create"  -> handleCreate(session, root);
                case "message" -> handleMessage(session, root);
                case "approve" -> handleApprove(session, root);
                case "stop"    -> handleStop(session, root);
                default -> sendErr(session, action, "unknown action: " + action);
            }
        } catch (Exception e) {
            log.warn("Agent WS message handling failed", e);
            sendErr(session, null, e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        unbind(session);
        log.debug("Agent WS closed: {} ({})", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("Agent WS transport error: {}", exception.getMessage());
    }

    // ----------------- action handlers -----------------

    private void handleBind(WebSocketSession session, JsonNode body) throws Exception {
        String sid = text(body, "sessionId");
        if (sid == null) {
            sendErr(session, "bind", "missing sessionId");
            return;
        }
        bind(session, sid);
        AgentEvent event = agentService.bindSession(sid);
        if (event != null) {
            sendJson(session, event);
        } else {
            sendJson(session, AgentEvent.error(sid, "Session not found: " + sid, "SESSION_NOT_FOUND"));
        }
    }

    private void handleCreate(WebSocketSession session, JsonNode body) throws Exception {
        String workspaceId = text(body, "workspaceId");
        if (workspaceId == null) {
            sendErr(session, "create", "missing workspaceId");
            return;
        }
        var wsOpt = workspaces.findById(workspaceId);
        if (wsOpt.isEmpty()) {
            sendErr(session, "create", "workspace not found: " + workspaceId);
            return;
        }
        WorkspaceConfig workspace = wsOpt.get();

        String apiKey = nonBlank(text(body, "apiKey"), serverProperties.apiKey());
        String provider = text(body, "provider");
        String baseUrl = resolveBaseUrl(provider);
        String model = nonBlank(text(body, "model"), serverProperties.model());

        CodeAgentConfig config = new CodeAgentConfig(
                apiKey, baseUrl, model, Integer.MAX_VALUE, workspace.workingDir(), null, 0, 0,
                serverProperties.thinkingBudget());

        String sessionId = agentService.createSession(config, workspace.id(), workspace.useWorktree());
        bind(session, sessionId);

        // After createSession, the actual cwd may have been swapped to a worktree path. Read the
        // effective workingDir from the live session so the UI sees the real path, not the
        // workspace dir.
        String effectiveDir = agentService.listSessions().stream()
                .filter(s -> s.sessionId().equals(sessionId))
                .map(io.kairo.code.service.SessionInfo::workingDir)
                .findFirst()
                .orElse(workspace.workingDir());

        CreateSessionResponse resp = new CreateSessionResponse(sessionId, effectiveDir, model);
        sendJson(session, new SessionCreatedEnvelope(sessionId, resp.workingDir(), resp.model()));
        log.info("Session {} created via WS (workspace={})", sessionId, workspace.id());
    }

    private void handleMessage(WebSocketSession session, JsonNode body) {
        String sid = text(body, "sessionId");
        String msg = text(body, "message");
        String imageData = text(body, "imageData");
        String imageMediaType = text(body, "imageMediaType");
        if (sid == null || msg == null) {
            sendErr(session, "message", "missing sessionId or message");
            return;
        }
        agentService.sendMessage(sid, msg, imageData, imageMediaType)
                .subscribe(
                        event -> broadcast(sid, event),
                        err -> log.error("Stream error for session {}", sid, err)
                );
    }

    private void handleApprove(WebSocketSession session, JsonNode body) {
        String sid = text(body, "sessionId");
        String toolCallId = text(body, "toolCallId");
        boolean approved = body.path("approved").asBoolean(false);
        String reason = text(body, "reason");
        if (sid == null || toolCallId == null) {
            sendErr(session, "approve", "missing sessionId or toolCallId");
            return;
        }
        // editedArgs is an optional shallow-merge patch for the pending tool's input map.
        // The exit_plan_mode card uses it to surface user-edited plan items.
        java.util.Map<String, Object> editedArgs = null;
        JsonNode editedNode = body.get("editedArgs");
        if (editedNode != null && editedNode.isObject()) {
            try {
                editedArgs = mapper.convertValue(
                        editedNode,
                        new com.fasterxml.jackson.core.type.TypeReference<
                                java.util.Map<String, Object>>() {});
            } catch (IllegalArgumentException e) {
                log.warn("Ignoring unparseable editedArgs for {}/{}: {}", sid, toolCallId, e.getMessage());
            }
        }
        boolean resolved = agentService.approveTool(sid, toolCallId, approved, reason, editedArgs);
        if (!resolved) {
            // Benign race: client double-clicked, auto-approval already resolved it, or the
            // tool was circuit-broken / aborted upstream so its Sink is gone. Log and drop —
            // surfacing this as AGENT_ERROR clutters the chat with a phantom error card.
            log.debug("Stale approve for session {} toolCallId {} (already resolved or expired)",
                    sid, toolCallId);
        }
    }

    private void handleStop(WebSocketSession session, JsonNode body) {
        String sid = text(body, "sessionId");
        if (sid == null) {
            sendErr(session, "stop", "missing sessionId");
            return;
        }
        agentService.stopAgent(sid);
        log.info("Stop requested for session {}", sid);
    }

    // ----------------- subscriber bookkeeping -----------------

    private void bind(WebSocketSession session, String sessionId) {
        // a single ws may rebind; remove prior subscription first
        unbind(session);
        session.getAttributes().put(ATTR_SESSION_ID, sessionId);
        subscribers
                .computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet())
                .add(session);
    }

    private void unbind(WebSocketSession session) {
        Object sid = session.getAttributes().remove(ATTR_SESSION_ID);
        if (sid instanceof String s) {
            Set<WebSocketSession> set = subscribers.get(s);
            if (set != null) {
                set.remove(session);
                if (set.isEmpty()) {
                    subscribers.remove(s);
                }
            }
        }
    }

    private void broadcast(String sessionId, Object payload) {
        Set<WebSocketSession> set = subscribers.get(sessionId);
        if (set == null || set.isEmpty()) return;
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("Failed to serialize event for session {}", sessionId, e);
            return;
        }
        for (WebSocketSession ws : set) {
            sendText(ws, json);
        }
    }

    private void sendJson(WebSocketSession session, Object payload) throws Exception {
        sendText(session, mapper.writeValueAsString(payload));
    }

    private void sendText(WebSocketSession session, String text) {
        if (!session.isOpen()) return;
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(text));
            }
        } catch (Exception e) {
            log.warn("Send failed on ws {}: {}", session.getId(), e.getMessage());
        }
    }

    private void sendErr(WebSocketSession session, String action, String msg) {
        try {
            sendJson(session, new ErrorEnvelope(action, msg));
        } catch (Exception ignored) {}
    }

    // ----------------- helpers -----------------

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText();
        return s.isEmpty() ? null : s;
    }

    private static String nonBlank(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }

    private String resolveBaseUrl(String provider) {
        if (provider == null || provider.isBlank()) {
            return serverProperties.baseUrl();
        }
        return switch (provider.toLowerCase()) {
            case "openai" -> "https://api.openai.com";
            case "anthropic" -> "https://api.anthropic.com";
            default -> serverProperties.baseUrl();
        };
    }

    // ----------------- envelope records (server → client) -----------------

    private record SessionCreatedEnvelope(
            String type, String sessionId, String workingDir, String model) {
        SessionCreatedEnvelope(String sessionId, String workingDir, String model) {
            this("SESSION_CREATED", sessionId, workingDir, model);
        }
    }

    private record ErrorEnvelope(String type, boolean ok, String action, String message) {
        ErrorEnvelope(String action, String message) {
            this("ERR", false, action, message);
        }
    }
}
