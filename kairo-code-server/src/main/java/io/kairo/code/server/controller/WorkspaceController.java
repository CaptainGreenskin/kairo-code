package io.kairo.code.server.controller;

import io.kairo.code.server.config.WorkspaceConfig;
import io.kairo.code.server.config.WorkspacePersistenceService;
import io.kairo.code.service.AgentService;
import io.kairo.code.service.SessionInfo;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * CRUD for first-class workspaces, plus a per-workspace active-session listing.
 *
 * <p>{@code workspaces.json} is the source of truth; sessions reference workspaces by id.
 */
@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private final WorkspacePersistenceService workspaces;
    private final AgentService agentService;

    public WorkspaceController(WorkspacePersistenceService workspaces, AgentService agentService) {
        this.workspaces = workspaces;
        this.agentService = agentService;
    }

    @GetMapping
    public List<WorkspaceConfig> list() {
        return workspaces.loadAll();
    }

    @PostMapping
    public ResponseEntity<WorkspaceConfig> create(@RequestBody CreateRequest body) throws IOException {
        if (body == null || body.workingDir() == null || body.workingDir().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        String name = (body.name() != null && !body.name().isBlank())
                ? body.name() : defaultNameFor(body.workingDir());
        WorkspaceConfig created = workspaces.add(name, body.workingDir(), body.useWorktree());
        return ResponseEntity.ok(created);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<WorkspaceConfig> update(@PathVariable String id,
                                                  @RequestBody UpdateRequest body) throws IOException {
        Optional<WorkspaceConfig> updated = workspaces.update(
                id,
                body != null ? body.name() : null,
                body != null ? body.workingDir() : null,
                body != null ? body.useWorktree() : null
        );
        return updated.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) throws IOException {
        // Refuse if the workspace still owns active sessions — losing a workspace mid-run would
        // strand the worktree path the agent is writing to. Caller must destroy sessions first.
        boolean hasActive = agentService.listSessions().stream()
                .anyMatch(s -> id.equals(s.workspaceId()));
        if (hasActive) {
            return ResponseEntity.status(409).build();
        }
        return workspaces.delete(id) ? ResponseEntity.noContent().build()
                                     : ResponseEntity.notFound().build();
    }

    @GetMapping("/{id}/sessions")
    public List<SessionInfo> sessionsFor(@PathVariable String id) {
        return agentService.listSessions().stream()
                .filter(s -> id.equals(s.workspaceId()))
                .toList();
    }

    private String defaultNameFor(String workingDir) {
        String[] parts = workingDir.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (!parts[i].isBlank()) return parts[i];
        }
        return workingDir;
    }

    public record CreateRequest(String name, String workingDir, boolean useWorktree) {}

    public record UpdateRequest(String name, String workingDir, Boolean useWorktree) {}
}
