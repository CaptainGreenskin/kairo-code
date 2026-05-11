package io.kairo.code.server.controller;

import io.kairo.code.service.AgentService;
import io.kairo.code.service.SessionDiagnostics;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Live telemetry endpoint complementing {@link SessionStateController}.
 *
 * <p>Where {@code /state} answers "what tools is this session waiting on?", this endpoint
 * answers "is this agent actually moving?" — emit counts, time since last event, ws subscribers.
 * The dev diagnostics drawer polls this so a hung agent is visible without opening backend logs.
 *
 * <p>Returns 404 for unknown ids (matching {@link SessionStateController}'s semantics) so the
 * frontend can treat both endpoints uniformly.
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionDiagnosticsController {

    private final AgentService agentService;

    public SessionDiagnosticsController(AgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping("/{id}/diagnostics")
    public ResponseEntity<SessionDiagnostics> getDiagnostics(@PathVariable("id") String id) {
        SessionDiagnostics diag = agentService.getSessionDiagnostics(id);
        if (diag == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(diag);
    }
}
