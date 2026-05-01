package io.kairo.code.server.controller;

import io.kairo.code.core.team.SharedTask;
import io.kairo.code.core.team.SharedTaskList;
import io.kairo.code.core.team.Team;
import io.kairo.code.core.team.TeamManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for team management.
 * Exposes CRUD operations for teams and task listing.
 */
@RestController
@RequestMapping("/api/teams")
public class TeamController {

    @Autowired
    private TeamManager teamManager;

    @GetMapping
    public List<Team> listActiveTeams() {
        return teamManager.activeTeams();
    }

    @PostMapping
    public Team createTeam(@RequestBody Map<String, String> body) {
        return teamManager.createTeam(
            body.getOrDefault("name", "unnamed-team"),
            body.getOrDefault("goal", ""));
    }

    @GetMapping("/{teamId}")
    public ResponseEntity<Team> getTeam(@PathVariable String teamId) {
        return teamManager.getTeam(teamId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{teamId}")
    public ResponseEntity<Void> dissolveTeam(@PathVariable String teamId) {
        teamManager.dissolveTeam(teamId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{teamId}/tasks")
    public ResponseEntity<List<SharedTask>> getTasks(@PathVariable String teamId) {
        return teamManager.getTaskList(teamId)
            .map(list -> ResponseEntity.ok(list.all()))
            .orElse(ResponseEntity.notFound().build());
    }
}
