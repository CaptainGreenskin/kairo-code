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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TeamManagerTest {

    private TeamManager manager;

    @BeforeEach
    void setUp() {
        manager = new TeamManager();
    }

    @Test
    void createTeamAndGetById() {
        Team team = manager.createTeam("my-team", "build feature X");
        assertNotNull(team.teamId());
        assertEquals("my-team", team.name());
        assertEquals(Team.TeamStatus.INITIALIZING, team.status());

        Optional<Team> found = manager.getTeam(team.teamId());
        assertTrue(found.isPresent());
    }

    @Test
    void addMemberActivatesTeam() {
        Team team = manager.createTeam("team-2", "goal");
        TeamMember member = new TeamMember("m1", "researcher-1", TeamRole.RESEARCHER, "session-1");
        Team updated = manager.addMember(team.teamId(), member);

        assertEquals(Team.TeamStatus.ACTIVE, updated.status());
        assertEquals(1, updated.members().size());
    }

    @Test
    void dissolveTeamRemovesIt() {
        Team team = manager.createTeam("temp", "temporary");
        manager.dissolveTeam(team.teamId());
        assertTrue(manager.getTeam(team.teamId()).isEmpty());
        assertTrue(manager.getTaskList(team.teamId()).isEmpty());
    }
}
