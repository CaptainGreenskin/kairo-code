package io.kairo.code.server.controller;

import io.kairo.code.service.AgentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only introspection endpoint for live agent sessions.
 *
 * <p>Exposes {@link AgentService#getSessionState(String)} so an operator (or a future
 * dev-tools UI) can answer "what is this session waiting on?" without needing to
 * scrape WebSocket traffic. Pairs with the {@code TOOL_PROGRESS} heartbeat — when a
 * card has been "Running" for an unexpected amount of time, hitting this endpoint
 * confirms whether the tool is still tracked and whether it's waiting on a human.
 *
 * <p>Returns 404 for unknown ids so {@code curl} and dashboards see a clear HTTP
 * signal that the session is gone, rather than having to parse {@code exists=false}
 * out of a 200 body.
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionStateController {

    private final AgentService agentService;

    public SessionStateController(AgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping("/{id}/state")
    public ResponseEntity<AgentService.SessionState> getState(@PathVariable("id") String id) {
        AgentService.SessionState state = agentService.getSessionState(id);
        if (!state.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(state);
    }
}
