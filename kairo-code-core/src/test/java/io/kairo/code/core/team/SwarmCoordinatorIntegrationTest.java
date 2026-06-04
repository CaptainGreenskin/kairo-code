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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kairo.api.team.TeamConfig;
import io.kairo.api.team.TeamResult;
import io.kairo.api.team.TeamStatus;
import io.kairo.multiagent.orchestration.ExpertTeamCoordinator;
import io.kairo.multiagent.orchestration.SimpleEvaluationStrategy;
import io.kairo.multiagent.orchestration.internal.DefaultPlanner;
import io.kairo.multiagent.subagent.ExpertRoleRegistry;
import io.kairo.code.core.testutil.NoopMessageBus;
import io.kairo.code.core.testutil.StubAgent;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SwarmCoordinatorIntegrationTest {

    private SwarmCoordinator coordinator;
    private ExpertRoleRegistry roleRegistry;

    @BeforeEach
    void setUp() {
        roleRegistry = new ExpertRoleRegistry();
        DefaultPlanner planner = new DefaultPlanner(roleRegistry, null, null);
        ExpertTeamCoordinator expertCoordinator = new ExpertTeamCoordinator(
                null, new SimpleEvaluationStrategy(), null, planner, roleRegistry);

        coordinator = new SwarmCoordinator(
                expertCoordinator,
                roleRegistry,
                new NoopMessageBus(),
                List.of(StubAgent.fixed("coder", "implementation complete")));
    }

    @Test
    void fullExpertTeamExecution() {
        TeamResult result = coordinator.startExpertTeam(
                "refactor auth module", TeamConfig.defaults(), List.of())
                .block();

        assertNotNull(result);
        assertNotNull(result.requestId());
        assertFalse(result.requestId().isBlank());
        assertTrue(result.status() == TeamStatus.COMPLETED
                || result.status() == TeamStatus.DEGRADED);
        assertNotNull(result.stepOutcomes());
        assertFalse(result.stepOutcomes().isEmpty());
        assertNotNull(result.totalDuration());
    }

    @Test
    void multipleAgentsRoundRobin() {
        ExpertRoleRegistry registry = new ExpertRoleRegistry();
        DefaultPlanner planner = new DefaultPlanner(registry, null, null);
        ExpertTeamCoordinator expertCoordinator = new ExpertTeamCoordinator(
                null, new SimpleEvaluationStrategy(), null, planner, registry);

        SwarmCoordinator multiAgentCoord = new SwarmCoordinator(
                expertCoordinator,
                registry,
                new NoopMessageBus(),
                List.of(
                        StubAgent.fixed("agent-1", "output-1"),
                        StubAgent.fixed("agent-2", "output-2")));

        TeamResult result = multiAgentCoord.startExpertTeam(
                "complex task", TeamConfig.defaults(), List.of())
                .block();

        assertNotNull(result);
        assertTrue(result.status() == TeamStatus.COMPLETED
                || result.status() == TeamStatus.DEGRADED);
    }

    @Test
    void registeredRoleIdsAreAccessible() {
        var roleIds = coordinator.roleRegistry().registeredRoleIds();
        assertNotNull(roleIds);
        assertFalse(roleIds.isEmpty());
        assertTrue(roleIds.contains("expert:coder"));
        assertTrue(roleIds.contains("expert:researcher"));
    }
}
