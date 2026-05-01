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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates a Swarm run through 4 phases: Research → Synthesis → Implementation → Verification.
 * Each phase barrier waits for all workers to complete before advancing.
 */
public class SwarmCoordinator {

    private final TeamManager teamManager;
    private final MessageBus messageBus;
    private final ConcurrentHashMap<String, SwarmExecution> executions = new ConcurrentHashMap<>();

    public SwarmCoordinator(TeamManager teamManager) {
        this(teamManager, new MessageBus());
    }

    public SwarmCoordinator(TeamManager teamManager, MessageBus messageBus) {
        this.teamManager = teamManager;
        this.messageBus = messageBus;
    }

    /**
     * Start a new Swarm run for a team.
     */
    public SwarmExecution startSwarm(String teamId, SwarmConfig config) {
        SwarmExecution exec = new SwarmExecution(teamId, config);
        executions.put(teamId, exec);
        return exec;
    }

    /**
     * Report worker completion for current phase.
     * When all workers complete, automatically advance to next phase.
     */
    public synchronized PhaseAdvanceResult reportWorkerDone(String teamId,
                                                             String workerId,
                                                             String workerOutput) {
        SwarmExecution exec = executions.get(teamId);
        if (exec == null) return PhaseAdvanceResult.NOT_FOUND;

        SharedTaskList taskList = teamManager.getTaskList(teamId).orElse(null);
        if (taskList == null) return PhaseAdvanceResult.NOT_FOUND;

        // Mark the worker's in-progress task as complete
        taskList.all().stream()
            .filter(t -> workerId.equals(t.ownerId())
                && t.status() == SharedTask.TaskStatus.IN_PROGRESS)
            .findFirst()
            .ifPresent(t -> taskList.complete(t.taskId(), workerId));

        // Check if phase is done
        if (!isPhaseComplete(teamId)) {
            return PhaseAdvanceResult.PHASE_ONGOING;
        }

        // Collect all worker outputs for synthesis
        List<String> outputs = taskList.all().stream()
            .filter(t -> t.status() == SharedTask.TaskStatus.COMPLETED)
            .map(SharedTask::description)
            .collect(java.util.stream.Collectors.toList());

        String summary = String.join("\n---\n", outputs);
        exec.recordPhase(new SwarmExecution.PhaseResult(
            exec.currentPhase().getClass().getSimpleName(),
            outputs, summary, System.currentTimeMillis()));

        // Advance phase
        SwarmPhase next = advance(teamId, exec.currentPhase(), summary);
        exec.advanceTo(next);

        // Check if swarm is complete (verification phase already passed)
        if (next instanceof SwarmPhase.Verification v
                && v.verificationCriteria().stream().anyMatch(s -> s.startsWith("COMPLETE:"))) {
            exec.complete();
            return PhaseAdvanceResult.SWARM_COMPLETE;
        }

        // Notify team of phase transition
        messageBus.broadcast(teamId, "coordinator",
            "Phase complete. Advancing to: " + next.getClass().getSimpleName(), teamManager);

        return PhaseAdvanceResult.PHASE_ADVANCED;
    }

    public enum PhaseAdvanceResult { PHASE_ONGOING, PHASE_ADVANCED, SWARM_COMPLETE, NOT_FOUND }

    public Optional<SwarmExecution> getExecution(String teamId) {
        return Optional.ofNullable(executions.get(teamId));
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
