package io.kairo.code.server.controller;

import io.kairo.code.server.config.ConfigPersistenceService;
import io.kairo.code.server.config.ServerConfig.ServerProperties;
import io.kairo.code.server.dto.ServerConfigResponse;
import io.kairo.code.server.dto.UpdateConfigRequest;
import io.kairo.code.service.AgentService;
import io.kairo.code.service.SessionInfo;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for server configuration and session management.
 */
@RestController
@RequestMapping("/api")
public class ConfigController {

    private final ServerProperties serverProperties;
    private final AgentService agentService;
    private final ConfigPersistenceService persistenceService;

    public ConfigController(ServerProperties serverProperties,
                            AgentService agentService,
                            ConfigPersistenceService persistenceService) {
        this.serverProperties = serverProperties;
        this.agentService = agentService;
        this.persistenceService = persistenceService;
    }

    /**
     * Return the current server configuration.
     */
    @GetMapping("/config")
    public ServerConfigResponse getConfig() {
        return buildConfigResponse();
    }

    /**
     * Update server configuration (partial update, persisted + hot-updated).
     */
    @PostMapping("/config")
    public ServerConfigResponse updateConfig(@RequestBody UpdateConfigRequest request) throws IOException {
        Map<String, String> current = new HashMap<>(persistenceService.load());

        if (request.apiKey() != null) current.put("apiKey", request.apiKey());
        if (request.model() != null) current.put("model", request.model());
        if (request.provider() != null) current.put("provider", request.provider());
        if (request.baseUrl() != null) current.put("baseUrl", request.baseUrl());
        if (request.workingDir() != null) current.put("workingDir", request.workingDir());

        persistenceService.save(current);

        if (request.provider() != null) serverProperties.setProvider(request.provider());
        if (request.model() != null) serverProperties.setModel(request.model());
        if (request.baseUrl() != null) serverProperties.setBaseUrl(request.baseUrl());
        if (request.workingDir() != null) serverProperties.setWorkingDir(request.workingDir());
        if (request.apiKey() != null) serverProperties.setApiKey(request.apiKey());

        agentService.updateDefaultConfig(
                serverProperties.apiKey(),
                serverProperties.model(),
                serverProperties.provider(),
                serverProperties.baseUrl(),
                serverProperties.workingDir()
        );

        return buildConfigResponse();
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
        return agentService.listSessions();
    }

    /**
     * Destroy a session by ID.
     */
    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Void> destroySession(@PathVariable String id) {
        boolean destroyed = agentService.destroySession(id);
        return destroyed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    private ServerConfigResponse buildConfigResponse() {
        return new ServerConfigResponse(
                serverProperties.provider(),
                serverProperties.model(),
                serverProperties.workingDir(),
                serverProperties.baseUrl(),
                serverProperties.apiKey() != null && !serverProperties.apiKey().isBlank()
        );
    }
}
