package io.kairo.code.server.controller;

import io.kairo.code.service.AgentService;
import io.kairo.code.service.SessionIndexEntry;
import io.kairo.code.service.SessionIndexService;
import io.kairo.code.service.SessionInfo;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final AgentService agentService;
    private final SessionIndexService sessionIndexService;

    public SessionController(AgentService agentService) {
        this.agentService = agentService;
        this.sessionIndexService = agentService.getSessionIndexService();
    }

    /**
     * Returns the total number of active sessions.
     * GET /api/sessions/count → {"count": N}
     */
    @GetMapping("/count")
    public ResponseEntity<Map<String, Integer>> count() {
        int count = agentService.listSessions().size();
        return ResponseEntity.ok(Map.of("count", count));
    }

    /**
     * Cancel (stop) a running agent session via REST.
     * Alternative to the WS "stop" action, usable when the WebSocket
     * connection is unavailable or broken.
     *
     * POST /api/sessions/{sessionId}/cancel → 204 No Content
     */
    @PostMapping("/{sessionId}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable String sessionId) {
        agentService.stopAgent(sessionId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Live mid-session metrics — schema mirrors {@code KAIRO_SESSION_RESULT.json}
     * so {@code kairo-code-eval} (the external-runner harness) and any other
     * REST client speak the same shape regardless of CLI vs web mode.
     *
     * <p>404 when the session id is unknown. 200 with empty maps when the
     * session exists but didn't auto-register a SessionMetricsCollector
     * (legacy REPL paths only — production WS / HTTP sessions always do).
     *
     * GET /api/sessions/{sessionId}/metrics
     */
    @GetMapping("/{sessionId}/metrics")
    public ResponseEntity<AgentService.SessionMetricsSnapshot> metrics(@PathVariable String sessionId) {
        AgentService.SessionMetricsSnapshot snapshot = agentService.getSessionMetrics(sessionId);
        if (snapshot == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(snapshot);
    }

    public record SessionIndexResponse(
            String sessionId,
            String name,
            String workspaceId,
            String workingDir,
            String model,
            String status,
            long createdAt,
            long updatedAt,
            int messageCount,
            boolean hasSnapshot,
            boolean running
    ) {}

    /**
     * Unified session index. Returns all known sessions (active, idle, archived)
     * with real-time running status from in-memory state.
     *
     * GET /api/sessions/index
     */
    @GetMapping("/index")
    public List<SessionIndexResponse> getSessionIndex() {
        List<SessionIndexEntry> entries = sessionIndexService.loadIndex();
        Set<String> runningIds = agentService.listSessions().stream()
                .filter(SessionInfo::running)
                .map(SessionInfo::sessionId)
                .collect(Collectors.toSet());

        return entries.stream().map(e -> new SessionIndexResponse(
                e.sessionId(), e.name(), e.workspaceId(), e.workingDir(),
                e.model(), e.status(), e.createdAt(), e.updatedAt(),
                e.messageCount(), e.hasSnapshot(),
                runningIds.contains(e.sessionId())
        )).toList();
    }
}
