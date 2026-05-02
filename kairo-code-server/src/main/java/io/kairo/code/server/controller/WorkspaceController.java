package io.kairo.code.server.controller;

import io.kairo.code.service.AgentService;
import io.kairo.code.service.SessionInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller that exposes a workspace-oriented view over active sessions.
 * Each session maps to one workspace (workingDir), with a derived status string.
 */
@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private final AgentService agentService;

    public WorkspaceController(AgentService agentService) {
        this.agentService = agentService;
    }

    /**
     * Return a summary of all active sessions, keyed by workingDir (= workspace).
     */
    @GetMapping
    public List<WorkspaceSummary> listWorkspaces() {
        return agentService.listSessions().stream()
                .map(this::toSummary)
                .toList();
    }

    private WorkspaceSummary toSummary(SessionInfo info) {
        return new WorkspaceSummary(
                info.sessionId(),
                info.workingDir(),
                info.running() ? "running" : "idle",
                info.createdAt(),
                0  // messageCount not available on SessionInfo; reserved for future use
        );
    }

    public record WorkspaceSummary(
            String sessionId,
            String workingDir,
            String status,
            long lastActivity,
            int messageCount
    ) {}
}
