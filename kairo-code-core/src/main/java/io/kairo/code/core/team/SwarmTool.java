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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool to start a Swarm run for a team.
 * Creates the team, configures SwarmConfig, starts the SwarmExecution,
 * and returns a status summary.
 */
public class SwarmTool {

    private final TeamManager teamManager;
    private final SwarmCoordinator coordinator;

    public SwarmTool(TeamManager teamManager, SwarmCoordinator coordinator) {
        this.teamManager = teamManager;
        this.coordinator = coordinator;
    }

    /**
     * Start a new Swarm run.
     *
     * @param goal             The goal description for the swarm
     * @param researchWorkers  Number of research workers (default 2)
     * @param implementWorkers Number of implementation workers (default 3)
     * @return Status map with swarmId, teamId, phase, and status
     */
    public Map<String, Object> execute(String goal, int researchWorkers, int implementWorkers) {
        SwarmConfig config = new SwarmConfig(
            goal,
            researchWorkers,
            implementWorkers,
            2,   // verifyWorkers default
            true, // allowParallelImpl
            300, // phaseTimeoutSeconds default
            List.of(),
            List.of()
        );
        return execute(config);
    }

    /**
     * Start a new Swarm run with full config control.
     */
    public Map<String, Object> execute(SwarmConfig config) {
        Team team = teamManager.createTeam("swarm-" + config.goal().substring(0, Math.min(20, config.goal().length())),
            config.goal());

        SwarmExecution exec = coordinator.startSwarm(team.teamId(), config);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("swarmId", team.teamId());
        result.put("teamId", team.teamId());
        result.put("phase", exec.currentPhase().getClass().getSimpleName());
        result.put("status", exec.status().name());
        return result;
    }
}
