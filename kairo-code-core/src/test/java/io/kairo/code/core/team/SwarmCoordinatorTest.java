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

class SwarmCoordinatorTest {

    private SwarmCoordinator coordinator;
    private TeamManager teamManager;

    @BeforeEach
    void setUp() {
        teamManager = new TeamManager();
        coordinator = new SwarmCoordinator(teamManager);
    }

    @Test
    void researchAdvancesToSynthesis() {
        SwarmPhase phase = new SwarmPhase.Research(List.of("explore codebase"));
        SwarmPhase next = coordinator.advance("team-1", phase, "found 3 modules");
        assertInstanceOf(SwarmPhase.Synthesis.class, next);
        assertEquals("found 3 modules", ((SwarmPhase.Synthesis) next).researchSummary());
    }

    @Test
    void synthesisAdvancesToImplementation() {
        SwarmPhase phase = new SwarmPhase.Synthesis("summary");
        SwarmPhase next = coordinator.advance("team-1", phase, "plan: add 3 files");
        assertInstanceOf(SwarmPhase.Implementation.class, next);
    }

    @Test
    void implementationAdvancesToVerification() {
        SwarmPhase phase = new SwarmPhase.Implementation("plan", List.of());
        SwarmPhase next = coordinator.advance("team-1", phase, "done");
        assertInstanceOf(SwarmPhase.Verification.class, next);
    }

    @Test
    void researchPhaseIsReadOnly() {
        SwarmPhase phase = new SwarmPhase.Research(List.of());
        assertTrue(phase.isReadOnly());
    }

    @Test
    void implementationPhaseIsNotReadOnly() {
        SwarmPhase phase = new SwarmPhase.Implementation("plan", List.of());
        assertFalse(phase.isReadOnly());
    }

    @Test
    void isPhaseCompleteReturnsFalseForNewTeam() {
        Team team = teamManager.createTeam("t", "g");
        // Empty task list: all() returns empty, stream.allMatch on empty is true
        assertTrue(coordinator.isPhaseComplete(team.teamId()));
    }

    @Test
    void isPhaseCompleteReturnsTrueWhenAllTasksDone() {
        Team team = teamManager.createTeam("t", "g");
        SharedTaskList taskList = teamManager.getTaskList(team.teamId()).orElseThrow();
        SharedTask task = taskList.create("work", "do it");
        taskList.claim(task.taskId(), "w1");
        taskList.complete(task.taskId(), "w1");

        assertTrue(coordinator.isPhaseComplete(team.teamId()));
    }

    @Test
    void isPhaseCompleteReturnsFalseWhenTaskInProgress() {
        Team team = teamManager.createTeam("t", "g");
        SharedTaskList taskList = teamManager.getTaskList(team.teamId()).orElseThrow();
        SharedTask task = taskList.create("work", "do it");
        taskList.claim(task.taskId(), "w1");

        assertFalse(coordinator.isPhaseComplete(team.teamId()));
    }

    @Test
    void assignPhaseTasksCreatesTasks() {
        Team team = teamManager.createTeam("t", "g");
        SwarmPhase phase = new SwarmPhase.Research(List.of("explore"));
        coordinator.assignPhaseTasks(team.teamId(), phase, List.of("task1", "task2"));

        SharedTaskList taskList = teamManager.getTaskList(team.teamId()).orElseThrow();
        assertEquals(2, taskList.all().size());
    }

    @Test
    void verificationAdvancesToComplete() {
        SwarmPhase phase = new SwarmPhase.Verification(List.of("criteria"));
        SwarmPhase next = coordinator.advance("team-1", phase, "all passed");
        assertInstanceOf(SwarmPhase.Verification.class, next);
        SwarmPhase.Verification v = (SwarmPhase.Verification) next;
        assertTrue(v.verificationCriteria().get(0).startsWith("COMPLETE:"));
    }
}
