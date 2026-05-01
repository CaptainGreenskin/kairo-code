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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TeamManager {

    private final ConcurrentHashMap<String, Team> teams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SharedTaskList> taskLists = new ConcurrentHashMap<>();

    public Team createTeam(String name, String goal) {
        String teamId = "team-" + UUID.randomUUID().toString().substring(0, 8);
        Team team = new Team(teamId, name, goal, new ArrayList<>(),
            Team.TeamStatus.INITIALIZING, System.currentTimeMillis());
        teams.put(teamId, team);
        taskLists.put(teamId, new SharedTaskList(teamId));
        return team;
    }

    public Team addMember(String teamId, TeamMember member) {
        return teams.compute(teamId, (id, t) -> {
            if (t == null) throw new IllegalArgumentException("Team not found: " + teamId);
            List<TeamMember> updated = new ArrayList<>(t.members());
            updated.add(member);
            return new Team(id, t.name(), t.goal(), updated,
                Team.TeamStatus.ACTIVE, t.createdAt());
        });
    }

    public Optional<Team> getTeam(String teamId) {
        return Optional.ofNullable(teams.get(teamId));
    }

    public Optional<SharedTaskList> getTaskList(String teamId) {
        return Optional.ofNullable(taskLists.get(teamId));
    }

    public void dissolveTeam(String teamId) {
        teams.remove(teamId);
        taskLists.remove(teamId);
    }

    public List<Team> activeTeams() {
        return teams.values().stream()
            .filter(t -> t.status() == Team.TeamStatus.ACTIVE
                || t.status() == Team.TeamStatus.INITIALIZING)
            .collect(java.util.stream.Collectors.toList());
    }
}
