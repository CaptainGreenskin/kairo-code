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

import io.kairo.api.team.TeamConfig;
import io.kairo.api.team.TeamResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Tool to start an expert team run.
 * Delegates to {@link SwarmCoordinator} which drives the upstream
 * plan→generate→evaluate lifecycle.
 */
public class SwarmTool {

    private final SwarmCoordinator coordinator;

    public SwarmTool(SwarmCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    /**
     * Start a new expert team execution.
     *
     * @param goal    the goal description for the team
     * @param roleIds specific roles to involve (empty = all available)
     * @return status map with requestId, status, and goal
     */
    public Map<String, Object> execute(String goal, List<String> roleIds) {
        Mono<TeamResult> resultMono = coordinator.startExpertTeam(
                goal, TeamConfig.defaults(), roleIds);
        TeamResult result = resultMono.block();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("requestId", result != null ? result.requestId() : "unknown");
        response.put("status", result != null ? result.status().name() : "UNKNOWN");
        response.put("goal", goal);
        response.put("finalOutput", result != null
                ? result.finalOutput().orElse("") : "");
        return response;
    }

    /**
     * Start a new expert team execution with default roles.
     *
     * @param goal             the goal description
     * @param researchWorkers  ignored (kept for API compat); planner decides parallelism
     * @param implementWorkers ignored (kept for API compat); planner decides parallelism
     * @return status map with requestId, status, and goal
     */
    public Map<String, Object> execute(String goal, int researchWorkers, int implementWorkers) {
        return execute(goal, List.of());
    }
}
