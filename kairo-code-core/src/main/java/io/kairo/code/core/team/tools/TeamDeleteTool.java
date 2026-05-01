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
import io.kairo.code.core.team.TeamManager;
import java.util.Map;

/**
 * Tool to dissolve (delete) an existing team.
 */
@Tool(
    name = "team_delete",
    description = "Dissolve (delete) an existing team by its teamId.",
    category = ToolCategory.GENERAL,
    sideEffect = ToolSideEffect.READ_ONLY
)
public class TeamDeleteTool implements ToolHandler {

    private final TeamManager teamManager;

    public TeamDeleteTool(TeamManager teamManager) {
        this.teamManager = teamManager;
    }

    @ToolParam(description = "The ID of the team to dissolve.", required = true)
    private String teamId;

    @Override
    public ToolResult execute(Map<String, Object> input) {
        String teamIdIn = stringInput(input, "teamId");

        if (teamIdIn == null || teamIdIn.isBlank()) {
            return errorResult("Parameter 'teamId' is required and must be non-blank.");
        }

        if (teamManager.getTeam(teamIdIn).isEmpty()) {
            return errorResult("Team not found: " + teamIdIn);
        }

        teamManager.dissolveTeam(teamIdIn);
        String json = "{\"dissolved\": true, \"teamId\": \"" + teamIdIn + "\"}";
        return new ToolResult(null, json, false, Map.of());
    }

    private static String stringInput(Map<String, Object> input, String key) {
        Object v = input.get(key);
        return v == null ? null : v.toString();
    }

    private static ToolResult errorResult(String message) {
        return new ToolResult(null, message, true, Map.of());
    }
}
