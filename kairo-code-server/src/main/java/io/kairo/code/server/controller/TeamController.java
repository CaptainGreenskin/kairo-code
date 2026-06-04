package io.kairo.code.server.controller;

import io.kairo.api.team.Team;
import io.kairo.api.team.TeamCreateRequest;
import io.kairo.api.team.TeamManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for team management. Exposes CRUD operations for teams. Returns JSON-safe DTOs
 * (not raw Team objects which contain non-serializable fields like MessageBus).
 */
@RestController
@RequestMapping("/api/teams")
public class TeamController {

    @Autowired private TeamManager teamManager;

    @GetMapping
    public List<Map<String, Object>> listActiveTeams() {
        return teamManager.listActive().stream().map(TeamController::toDto).toList();
    }

    @PostMapping
    public Map<String, Object> createTeam(@RequestBody Map<String, String> body) {
        Team team =
                teamManager.create(
                        TeamCreateRequest.of(
                                body.getOrDefault("name", "unnamed-team"),
                                body.getOrDefault("goal", "")));
        return toDto(team);
    }

    @GetMapping("/{teamId}")
    public ResponseEntity<Map<String, Object>> getTeam(@PathVariable String teamId) {
        Team team = teamManager.get(teamId);
        return team != null ? ResponseEntity.ok(toDto(team)) : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{teamId}")
    public ResponseEntity<Void> dissolveTeam(@PathVariable String teamId) {
        teamManager.delete(teamId);
        return ResponseEntity.noContent().build();
    }

    private static Map<String, Object> toDto(Team team) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("teamId", team.teamId());
        dto.put("name", team.name());
        dto.put("goal", team.goal());
        dto.put("status", team.status().name());
        dto.put("agentCount", team.agents().size());
        dto.put("metadata", team.metadata());
        dto.put("createdAt", team.createdAt().toString());
        return dto;
    }
}
