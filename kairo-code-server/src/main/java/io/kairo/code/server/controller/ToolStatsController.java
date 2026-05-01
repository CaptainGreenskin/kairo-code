package io.kairo.code.server.controller;

import io.kairo.code.service.AgentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * REST API for per-session tool-usage statistics.
 *
 * <p>Endpoint: {@code GET /api/tool-stats/{sessionId}}
 *
 * <p>Returns a JSON object mapping each tool name to its usage stats:
 * {@code {calls, successes, totalMillis, successRate, avgMillis}}.
 * Returns 404 if the session does not exist.
 */
@RestController
@RequestMapping("/api/tool-stats")
public class ToolStatsController {

    private final AgentService agentService;

    public ToolStatsController(AgentService agentService) {
        this.agentService = agentService;
    }

    /**
     * Return tool-usage stats for the given session.
     */
    @GetMapping("/{sessionId}")
    public ResponseEntity<Map<String, Map<String, Object>>> getToolStats(
            @PathVariable("sessionId") String sessionId) {
        Map<String, Map<String, Object>> stats = agentService.getSessionToolStats(sessionId);
        if (stats == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found: " + sessionId);
        }
        return ResponseEntity.ok(stats);
    }
}
