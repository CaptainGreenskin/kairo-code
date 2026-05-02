package io.kairo.code.server.controller;

import io.kairo.code.service.AgentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final AgentService agentService;

    public SessionController(AgentService agentService) {
        this.agentService = agentService;
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
}
