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
package io.kairo.code.core.team.tools;

import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolHandler;
import io.kairo.api.tool.ToolParam;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import io.kairo.code.core.team.Team;
import io.kairo.code.core.team.TeamManager;
import java.util.Map;

/**
 * Tool to create a new team of agents for collaborative work.
 */
@Tool(
    name = "team_create",
    description = "Create a new team of agents to work collaboratively on a goal.",
    category = ToolCategory.GENERAL,
    sideEffect = ToolSideEffect.READ_ONLY
)
public class TeamCreateTool implements ToolHandler {

    private final TeamManager teamManager;

    public TeamCreateTool(TeamManager teamManager) {
        this.teamManager = teamManager;
    }

    @ToolParam(description = "Name for the new team.", required = true)
    private String name;

    @ToolParam(description = "Goal description for the team.", required = true)
    private String goal;

    @Override
    public ToolResult execute(Map<String, Object> input) {
        String nameIn = stringInput(input, "name");
        String goalIn = stringInput(input, "goal");

        if (nameIn == null || nameIn.isBlank()) {
            return errorResult("Parameter 'name' is required and must be non-blank.");
        }
        if (goalIn == null || goalIn.isBlank()) {
            return errorResult("Parameter 'goal' is required and must be non-blank.");
        }

        Team team = teamManager.createTeam(nameIn, goalIn);
        String json = "{\"teamId\": \"" + team.teamId()
            + "\", \"name\": \"" + escape(team.name())
            + "\", \"status\": \"" + team.status() + "\"}";
        return new ToolResult(null, json, false, Map.of());
    }

    private static String stringInput(Map<String, Object> input, String key) {
        Object v = input.get(key);
        return v == null ? null : v.toString();
    }

    private static ToolResult errorResult(String message) {
        return new ToolResult(null, message, true, Map.of());
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
