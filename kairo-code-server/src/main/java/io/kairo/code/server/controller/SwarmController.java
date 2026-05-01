package io.kairo.code.server.controller;

import io.kairo.code.core.team.SharedTask;
import io.kairo.code.core.team.SharedTaskList;
import io.kairo.code.core.team.SwarmCoordinator;
import io.kairo.code.core.team.SwarmPhase;
import io.kairo.code.core.team.Team;
import io.kairo.code.core.team.TeamManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST controller for swarm execution status.
 * Composes team + task state to report swarm progress.
 */
@RestController
@RequestMapping("/api/swarms")
public class SwarmController {

    @Autowired
    private TeamManager teamManager;

    @Autowired
    private SwarmCoordinator swarmCoordinator;

    @GetMapping("/{teamId}")
    public ResponseEntity<Map<String, Object>> getSwarmStatus(@PathVariable String teamId) {
        return teamManager.getTeam(teamId)
            .map(team -> {
                Map<String, Object> status = new LinkedHashMap<>();
                status.put("teamId", teamId);
                status.put("status", team.status().name());

                List<SharedTask> tasks = teamManager.getTaskList(teamId)
                    .map(SharedTaskList::all)
                    .orElse(List.of());

                // Derive current phase from task completion pattern
                String currentPhase = deriveCurrentPhase(tasks);
                status.put("currentPhase", currentPhase);

                // Build phase history from completed tasks grouped by phase
                List<Map<String, Object>> phaseHistory = buildPhaseHistory(tasks);
                status.put("phaseHistory", phaseHistory);

                return ResponseEntity.ok(status);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    private String deriveCurrentPhase(List<SharedTask> tasks) {
        if (tasks.isEmpty()) {
            return "Research";
        }
        long total = tasks.size();
        long completed = tasks.stream()
            .filter(t -> t.status() == SharedTask.TaskStatus.COMPLETED
                || t.status() == SharedTask.TaskStatus.FAILED)
            .count();

        if (completed == 0) return "Research";
        double ratio = (double) completed / total;
        if (ratio < 0.25) return "Research";
        if (ratio < 0.50) return "Synthesis";
        if (ratio < 0.75) return "Implementation";
        if (ratio < 1.0) return "Verification";
        return "Completed";
    }

    private List<Map<String, Object>> buildPhaseHistory(List<SharedTask> tasks) {
        return tasks.stream()
            .filter(t -> t.status() == SharedTask.TaskStatus.COMPLETED
                || t.status() == SharedTask.TaskStatus.FAILED)
            .sorted((a, b) -> Long.compare(a.updatedAt(), b.updatedAt()))
            .map(t -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("phase", deriveTaskPhase(t));
                entry.put("completedAt", t.updatedAt());
                entry.put("title", t.title());
                entry.put("status", t.status().name());
                return entry;
            })
            .collect(Collectors.toList());
    }

    private String deriveTaskPhase(SharedTask task) {
        String desc = task.description().toLowerCase();
        if (desc.contains("research") || desc.contains("explore")) return "Research";
        if (desc.contains("synthes") || desc.contains("plan")) return "Synthesis";
        if (desc.contains("implement") || desc.contains("phase: implementation")) return "Implementation";
        if (desc.contains("verif") || desc.contains("test") || desc.contains("phase: verification")) return "Verification";
        return "Research";
    }
}
