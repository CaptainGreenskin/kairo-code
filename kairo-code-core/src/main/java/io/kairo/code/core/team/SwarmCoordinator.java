/*
 * Copyright 2025-2026 the Kairo authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.kairo.code.core.team;

import java.util.List;

/**
 * Orchestrates a Swarm run through 4 phases: Research → Synthesis → Implementation → Verification.
 * Each phase barrier waits for all workers to complete before advancing.
 */
public class SwarmCoordinator {

    private final TeamManager teamManager;

    public SwarmCoordinator(TeamManager teamManager) {
        this.teamManager = teamManager;
    }

    /**
     * Advance a team to the next Swarm phase.
     * Returns the new phase.
     */
    public SwarmPhase advance(String teamId, SwarmPhase currentPhase,
                               String phaseOutput) {
        if (currentPhase instanceof SwarmPhase.Research) {
            return new SwarmPhase.Synthesis(phaseOutput);
        }
        if (currentPhase instanceof SwarmPhase.Synthesis) {
            return new SwarmPhase.Implementation(phaseOutput, List.of());
        }
        if (currentPhase instanceof SwarmPhase.Implementation) {
            return new SwarmPhase.Verification(List.of(phaseOutput));
        }
        // Swarm complete
        return new SwarmPhase.Verification(List.of("COMPLETE: " + phaseOutput));
    }

    /**
     * Check if all tasks in the current phase are complete, enabling phase advancement.
     */
    public boolean isPhaseComplete(String teamId) {
        return teamManager.getTaskList(teamId)
            .map(list -> list.all().stream()
                .allMatch(t -> t.status() == SharedTask.TaskStatus.COMPLETED
                    || t.status() == SharedTask.TaskStatus.FAILED))
            .orElse(false);
    }

    /**
     * Assign tasks for a new phase to available team members.
     */
    public void assignPhaseTasks(String teamId, SwarmPhase phase,
                                  List<String> taskTitles) {
        SharedTaskList taskList = teamManager.getTaskList(teamId)
            .orElseThrow(() -> new IllegalArgumentException("Team not found: " + teamId));
        for (String title : taskTitles) {
            taskList.create(title, "Phase: " + phase.getClass().getSimpleName());
        }
    }
}
