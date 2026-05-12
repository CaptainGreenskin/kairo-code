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
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolParam;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.ToolSideEffect;
import io.kairo.code.core.team.MessageBus;
import io.kairo.code.core.team.Team;
import io.kairo.code.core.team.TeamManager;
import io.kairo.code.core.team.TeamMember;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Tool to send a message to a team member (or broadcast to all members).
 */
@Tool(
    name = "send_message",
    description = "Send a message to a team member or broadcast to all members of a team.",
    category = ToolCategory.GENERAL,
    sideEffect = ToolSideEffect.READ_ONLY
)
public class SendMessageTool implements SyncTool {

    private final TeamManager teamManager;
    private final MessageBus messageBus;

    public SendMessageTool(TeamManager teamManager, MessageBus messageBus) {
        this.teamManager = teamManager;
        this.messageBus = messageBus;
    }

    @ToolParam(description = "Target member name, or '*' for broadcast to all team members.", required = true)
    private String to;

    @ToolParam(description = "Message content to send.", required = true)
    private String message;

    @ToolParam(description = "The team ID the message belongs to.", required = true)
    private String teamId;

    @Override
    public Mono<ToolResult> execute(Map<String, Object> input, ToolContext ctx) {
        String toIn = stringInput(input, "to");
        String messageIn = stringInput(input, "message");
        String teamIdIn = stringInput(input, "teamId");

        if (toIn == null || toIn.isBlank()) {
            return Mono.just(ToolResult.error(null, "Parameter 'to' is required and must be non-blank."));
        }
        if (messageIn == null || messageIn.isBlank()) {
            return Mono.just(ToolResult.error(null, "Parameter 'message' is required and must be non-blank."));
        }
        if (teamIdIn == null || teamIdIn.isBlank()) {
            return Mono.just(ToolResult.error(null, "Parameter 'teamId' is required and must be non-blank."));
        }

        Team team = teamManager.getTeam(teamIdIn)
            .orElse(null);
        if (team == null) {
            return Mono.just(ToolResult.error(null, "Team not found: " + teamIdIn));
        }

        String msgId;
        if ("*".equals(toIn)) {
            // Broadcast: use the caller's session if available, otherwise empty string
            String fromSessionId = (String) input.getOrDefault("fromSessionId", "");
            boolean ok = messageBus.broadcast(teamIdIn, fromSessionId, messageIn, teamManager);
            if (!ok) {
                return Mono.just(ToolResult.error(null, "Failed to broadcast to team: " + teamIdIn));
            }
            msgId = "broadcast";
        } else {
            // Find member by name
            String targetSessionId = team.members().stream()
                .filter(m -> m.name().equals(toIn))
                .map(TeamMember::sessionId)
                .findFirst()
                .orElse(null);
            if (targetSessionId == null) {
                return Mono.just(ToolResult.error(null, "Member not found: " + toIn + " in team " + teamIdIn));
            }
            String fromSessionId = (String) input.getOrDefault("fromSessionId", "");
            msgId = messageBus.send(targetSessionId, fromSessionId, messageIn);
        }

        String json = "{\"delivered\": true, \"to\": \"" + escape(toIn)
            + "\", \"messageId\": \"" + escape(msgId) + "\"}";
        return Mono.just(ToolResult.success(null, json));
    }

    private static String stringInput(Map<String, Object> input, String key) {
        Object v = input.get(key);
        return v == null ? null : v.toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
