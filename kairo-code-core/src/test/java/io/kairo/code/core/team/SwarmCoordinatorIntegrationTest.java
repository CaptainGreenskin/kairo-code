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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SwarmCoordinatorIntegrationTest {

    private TeamManager teamManager;
    private MessageBus messageBus;
    private SwarmCoordinator coordinator;

    @BeforeEach
    void setUp() {
        teamManager = new TeamManager();
        messageBus = new MessageBus();
        coordinator = new SwarmCoordinator(teamManager, messageBus);
    }

    @Test
    void fullSwarmLifecycle_researchToSynthesis() {
        // Create team
        Team team = teamManager.createTeam("swarm-team", "refactor auth module");
        teamManager.addMember(team.teamId(),
            new TeamMember("r1", "researcher-1", TeamRole.RESEARCHER, "s-r1"));
        teamManager.addMember(team.teamId(),
            new TeamMember("r2", "researcher-2", TeamRole.RESEARCHER, "s-r2"));

        // Start swarm
        SwarmConfig config = new SwarmConfig("refactor auth module", 2, 2, 1, true, 60,
            List.of("explore auth package", "find test coverage"), List.of());
        SwarmExecution exec = coordinator.startSwarm(team.teamId(), config);
        assertInstanceOf(SwarmPhase.Research.class, exec.currentPhase());

        // Create tasks first
        SharedTaskList taskList = teamManager.getTaskList(team.teamId()).orElseThrow();
        SharedTask t1 = taskList.create("explore auth", "phase 1 worker 1");
        SharedTask t2 = taskList.create("find tests", "phase 1 worker 2");
        taskList.claim(t1.taskId(), "r1");
        taskList.claim(t2.taskId(), "r2");

        // Worker 1 completes
        SwarmCoordinator.PhaseAdvanceResult r1 = coordinator.reportWorkerDone(
            team.teamId(), "r1", "found 3 auth classes");
        assertEquals(SwarmCoordinator.PhaseAdvanceResult.PHASE_ONGOING, r1);

        // Worker 2 completes — should advance
        SwarmCoordinator.PhaseAdvanceResult r2 = coordinator.reportWorkerDone(
            team.teamId(), "r2", "test coverage 40%");
        assertEquals(SwarmCoordinator.PhaseAdvanceResult.PHASE_ADVANCED, r2);

        // Should now be in Synthesis
        assertInstanceOf(SwarmPhase.Synthesis.class, exec.currentPhase());
        assertEquals(1, exec.phaseHistory().size());
    }

    @Test
    void researchPhaseIsReadOnly() {
        SwarmPhase research = new SwarmPhase.Research(List.of("explore"));
        assertTrue(research.isReadOnly());
        assertTrue(research.allowedRoles().contains(TeamRole.RESEARCHER));
    }

    @Test
    void implementationPhaseAllowsWrites() {
        SwarmPhase impl = new SwarmPhase.Implementation("plan", List.of("task1", "task2"));
        assertFalse(impl.isReadOnly());
        assertTrue(impl.allowedRoles().contains(TeamRole.IMPLEMENTER));
    }

    @Test
    void fullLifecycleThroughAllPhases() {
        Team team = teamManager.createTeam("full-swarm", "build feature");

        SwarmConfig config = SwarmConfig.defaults("build feature");
        SwarmExecution exec = coordinator.startSwarm(team.teamId(), config);

        SharedTaskList taskList = teamManager.getTaskList(team.teamId()).orElseThrow();

        // Research phase: 2 workers
        SharedTask r1 = taskList.create("research A", "output A");
        SharedTask r2 = taskList.create("research B", "output B");
        taskList.claim(r1.taskId(), "w1");
        taskList.claim(r2.taskId(), "w2");

        assertEquals(SwarmCoordinator.PhaseAdvanceResult.PHASE_ONGOING,
            coordinator.reportWorkerDone(team.teamId(), "w1", "output A"));
        assertEquals(SwarmCoordinator.PhaseAdvanceResult.PHASE_ADVANCED,
            coordinator.reportWorkerDone(team.teamId(), "w2", "output B"));
        assertInstanceOf(SwarmPhase.Synthesis.class, exec.currentPhase());

        // Synthesis phase: 1 worker (coordinator)
        SharedTask s1 = taskList.create("synthesize", "synthesis output");
        taskList.claim(s1.taskId(), "coord");
        assertEquals(SwarmCoordinator.PhaseAdvanceResult.PHASE_ADVANCED,
            coordinator.reportWorkerDone(team.teamId(), "coord", "synthesis plan"));
        assertInstanceOf(SwarmPhase.Implementation.class, exec.currentPhase());

        // Implementation phase: workers
        SharedTask i1 = taskList.create("impl A", "impl output A");
        SharedTask i2 = taskList.create("impl B", "impl output B");
        taskList.claim(i1.taskId(), "w3");
        taskList.claim(i2.taskId(), "w4");
        assertEquals(SwarmCoordinator.PhaseAdvanceResult.PHASE_ONGOING,
            coordinator.reportWorkerDone(team.teamId(), "w3", "impl A done"));
        assertEquals(SwarmCoordinator.PhaseAdvanceResult.PHASE_ADVANCED,
            coordinator.reportWorkerDone(team.teamId(), "w4", "impl B done"));
        assertInstanceOf(SwarmPhase.Verification.class, exec.currentPhase());

        // Verification phase
        SharedTask v1 = taskList.create("verify", "verify output");
        taskList.claim(v1.taskId(), "w5");
        SwarmCoordinator.PhaseAdvanceResult vResult =
            coordinator.reportWorkerDone(team.teamId(), "w5", "all tests pass");
        assertEquals(SwarmCoordinator.PhaseAdvanceResult.SWARM_COMPLETE, vResult);
        assertEquals(SwarmExecution.SwarmStatus.COMPLETED, exec.status());
        assertEquals(4, exec.phaseHistory().size());
    }

    @Test
    void reportWorkerDoneForUnknownTeamReturnsNotFound() {
        assertEquals(SwarmCoordinator.PhaseAdvanceResult.NOT_FOUND,
            coordinator.reportWorkerDone("nonexistent", "w1", "output"));
    }

    @Test
    void getExecutionReturnsEmptyForUnknownTeam() {
        assertTrue(coordinator.getExecution("nonexistent").isEmpty());
    }

    @Test
    void swarmToolCreatesTeamAndStartsSwarm() {
        SwarmTool tool = new SwarmTool(teamManager, coordinator);
        var result = tool.execute("refactor auth", 2, 2);

        assertTrue(result.containsKey("swarmId"));
        assertTrue(result.containsKey("teamId"));
        assertEquals("Research", result.get("phase"));
        assertEquals("RUNNING", result.get("status"));
    }
}
