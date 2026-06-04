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

import io.kairo.api.agent.Agent;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.team.MessageBus;
import io.kairo.api.team.Team;
import io.kairo.api.team.TeamManager;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolParam;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import io.kairo.code.core.task.SubagentRegistry;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import reactor.core.publisher.Mono;

/**
 * Tool to send a message to a named subagent or team member. When {@code teamId} is omitted, routes
 * to a named subagent via {@link SubagentRegistry}; when provided, routes through the team {@link
 * MessageBus}.
 */
@Tool(
        name = "send_message",
        description =
                "Send a message to a named subagent or team member. "
                        + "For subagents spawned with a name, omit teamId and set 'to' to the agent"
                        + " name. For team members, provide teamId.",
        category = ToolCategory.GENERAL,
        sideEffect = ToolSideEffect.READ_ONLY)
public class SendMessageTool implements SyncTool {

    private final TeamManager teamManager;
    private final MessageBus messageBus;
    @Nullable private final SubagentRegistry subagentRegistry;

    SendMessageTool() {
        this.teamManager = null;
        this.messageBus = null;
        this.subagentRegistry = null;
    }

    public SendMessageTool(TeamManager teamManager, MessageBus messageBus) {
        this(teamManager, messageBus, null);
    }

    public SendMessageTool(
            TeamManager teamManager,
            MessageBus messageBus,
            @Nullable SubagentRegistry subagentRegistry) {
        this.teamManager = teamManager;
        this.messageBus = messageBus;
        this.subagentRegistry = subagentRegistry;
    }

    @ToolParam(
            description = "Target: subagent name, team member name, or '*' for broadcast.",
            required = true)
    private String to;

    @ToolParam(description = "Message content to send.", required = true)
    private String message;

    @ToolParam(
            description =
                    "Team ID (required for team messaging, omit for subagent messaging).",
            required = false)
    private String teamId;

    @Override
    public Mono<ToolResult> execute(Map<String, Object> input, ToolContext ctx) {
        String toIn = stringInput(input, "to");
        String messageIn = stringInput(input, "message");
        String teamIdIn = stringInput(input, "teamId");

        if (toIn == null || toIn.isBlank()) {
            return Mono.just(
                    ToolResult.error(null, "Parameter 'to' is required and must be non-blank."));
        }
        if (messageIn == null || messageIn.isBlank()) {
            return Mono.just(
                    ToolResult.error(
                            null, "Parameter 'message' is required and must be non-blank."));
        }

        if (teamIdIn == null || teamIdIn.isBlank()) {
            return routeToSubagent(toIn, messageIn);
        }

        return routeToTeam(toIn, messageIn, teamIdIn, input);
    }

    private Mono<ToolResult> routeToSubagent(String to, String message) {
        if (subagentRegistry == null) {
            return Mono.just(
                    ToolResult.error(
                            null,
                            "No subagent registry available. Provide a 'teamId' for team"
                                    + " messaging."));
        }
        var entry = subagentRegistry.lookup(to);
        if (entry.isEmpty()) {
            String active = String.join(", ", subagentRegistry.activeNames());
            return Mono.just(
                    ToolResult.error(
                            null,
                            "Subagent not found: '"
                                    + to
                                    + "'. Active subagents: ["
                                    + active
                                    + "]"));
        }
        if (entry.get().status().get() != SubagentRegistry.Status.RUNNING) {
            return Mono.just(
                    ToolResult.error(
                            null,
                            "Subagent '"
                                    + to
                                    + "' has already "
                                    + entry.get().status().get().name().toLowerCase()
                                    + ". Cannot send messages to finished agents."));
        }
        boolean queued = subagentRegistry.enqueue(to, message);
        if (!queued) {
            return Mono.just(
                    ToolResult.error(null, "Failed to enqueue message to subagent: " + to));
        }
        return Mono.just(
                ToolResult.success(
                        null,
                        "{\"delivered\": true, \"to\": \""
                                + escape(to)
                                + "\", \"channel\": \"subagent\"}"));
    }

    private Mono<ToolResult> routeToTeam(
            String to, String message, String teamId, Map<String, Object> input) {
        Team team = teamManager.get(teamId);
        if (team == null) {
            return Mono.just(ToolResult.error(null, "Team not found: " + teamId));
        }

        String fromAgentId = (String) input.getOrDefault("fromSessionId", "");
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .addContent(new io.kairo.api.message.Content.TextContent(message))
                        .metadata("from", fromAgentId)
                        .build();
        String msgId = UUID.randomUUID().toString().substring(0, 8);

        if ("*".equals(to)) {
            if (team.agents().isEmpty()) {
                return Mono.just(
                        ToolResult.error(null, "Team '" + teamId + "' has no members yet."));
            }
            messageBus.broadcast(fromAgentId, msg).block();
        } else {
            String targetAgentId =
                    team.agents().stream()
                            .filter(a -> a.name().equals(to))
                            .map(Agent::id)
                            .findFirst()
                            .orElse(null);
            if (targetAgentId == null) {
                return Mono.just(
                        ToolResult.error(
                                null, "Member not found: " + to + " in team " + teamId));
            }
            messageBus.send(fromAgentId, targetAgentId, msg).block();
        }

        String json =
                "{\"delivered\": true, \"to\": \""
                        + escape(to)
                        + "\", \"messageId\": \""
                        + escape(msgId)
                        + "\"}";
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
