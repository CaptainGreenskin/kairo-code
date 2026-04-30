package io.kairo.code.server.controller;

import io.kairo.code.server.config.ServerConfig.ServerProperties;
import io.kairo.code.server.dto.ServerConfigResponse;
import io.kairo.code.service.SessionInfo;
import io.kairo.code.server.session.AgentSessionManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for server configuration and session management.
 */
@RestController
@RequestMapping("/api")
public class ConfigController {

    private final ServerProperties serverProperties;
    private final AgentSessionManager sessionManager;

    public ConfigController(ServerProperties serverProperties,
                            AgentSessionManager sessionManager) {
        this.serverProperties = serverProperties;
        this.sessionManager = sessionManager;
    }

    /**
     * Return the current server configuration.
     */
    @GetMapping("/config")
    public ServerConfigResponse getConfig() {
        return new ServerConfigResponse(
                serverProperties.provider(),
                serverProperties.model(),
                serverProperties.workingDir());
    }

    /**
     * Return the list of available models.
     */
    @GetMapping("/models")
    public List<String> getModels() {
        return List.of(
                "gpt-4o",
                "gpt-4o-mini",
                "gpt-4-turbo",
                "claude-sonnet-4-20250514",
                "claude-opus-4-20250514",
                "glm-4-plus"
        );
    }

    /**
     * Return the list of active sessions.
     */
    @GetMapping("/sessions")
    public List<SessionInfo> getSessions() {
        return sessionManager.listSessions();
    }

    /**
     * Destroy a session by ID.
     */
    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Void> destroySession(@PathVariable String id) {
        boolean destroyed = sessionManager.destroySession(id);
        return destroyed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
