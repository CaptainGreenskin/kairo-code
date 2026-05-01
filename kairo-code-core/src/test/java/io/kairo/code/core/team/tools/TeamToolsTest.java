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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.kairo.api.tool.ToolResult;
import io.kairo.code.core.team.MessageBus;
import io.kairo.code.core.team.Team;
import io.kairo.code.core.team.TeamManager;
import io.kairo.code.core.team.TeamMember;
import io.kairo.code.core.team.TeamRole;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TeamToolsTest {

    private TeamManager teamManager;
    private MessageBus messageBus;

    @BeforeEach
    void setUp() {
        teamManager = new TeamManager();
        messageBus = new MessageBus();
    }

    @Test
    void teamCreateTool() {
        TeamCreateTool tool = new TeamCreateTool(teamManager);
        ToolResult result = tool.execute(Map.of(
            "name", "test-team",
            "goal", "build feature"
        ));

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("\"teamId\":");
        assertThat(result.content()).contains("\"name\": \"test-team\"");
        assertThat(result.content()).contains("\"status\":");

        // Extract teamId from JSON response
        String teamId = extractTeamId(result.content());
        assertThat(teamManager.getTeam(teamId)).isPresent();
    }

    @Test
    void teamCreateToolMissingName() {
        TeamCreateTool tool = new TeamCreateTool(teamManager);
        ToolResult result = tool.execute(Map.of(
            "goal", "build feature"
        ));

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("'name' is required");
    }

    @Test
    void teamCreateToolMissingGoal() {
        TeamCreateTool tool = new TeamCreateTool(teamManager);
        ToolResult result = tool.execute(Map.of(
            "name", "test-team"
        ));

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("'goal' is required");
    }

    @Test
    void teamDeleteTool() {
        Team team = teamManager.createTeam("to-delete", "temp");
        TeamDeleteTool tool = new TeamDeleteTool(teamManager);

        ToolResult result = tool.execute(Map.of(
            "teamId", team.teamId()
        ));

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("\"dissolved\": true");
        assertThat(teamManager.getTeam(team.teamId())).isEmpty();
    }

    @Test
    void teamDeleteToolNotFound() {
        TeamDeleteTool tool = new TeamDeleteTool(teamManager);

        ToolResult result = tool.execute(Map.of(
            "teamId", "nonexistent"
        ));

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("Team not found");
    }

    @Test
    void teamDeleteToolMissingTeamId() {
        TeamDeleteTool tool = new TeamDeleteTool(teamManager);

        ToolResult result = tool.execute(Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("'teamId' is required");
    }

    @Test
    void sendMessageDelivered() {
        Team team = teamManager.createTeam("t1", "goal");
        teamManager.addMember(team.teamId(),
            new TeamMember("m1", "worker-1", TeamRole.IMPLEMENTER, "session-w1"));

        SendMessageTool tool = new SendMessageTool(teamManager, messageBus);
        ToolResult result = tool.execute(Map.of(
            "to", "worker-1",
            "message", "hello worker",
            "teamId", team.teamId(),
            "fromSessionId", "session-coordinator"
        ));

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("\"delivered\": true");
        assertThat(result.content()).contains("\"to\": \"worker-1\"");

        List<MessageBus.TeamMessage> msgs = messageBus.poll("session-w1");
        assertEquals(1, msgs.size());
        assertEquals("hello worker", msgs.get(0).content());
    }

    @Test
    void sendMessageToUnknownMember() {
        Team team = teamManager.createTeam("t1", "goal");
        teamManager.addMember(team.teamId(),
            new TeamMember("m1", "worker-1", TeamRole.IMPLEMENTER, "session-w1"));

        SendMessageTool tool = new SendMessageTool(teamManager, messageBus);
        ToolResult result = tool.execute(Map.of(
            "to", "unknown-worker",
            "message", "hello",
            "teamId", team.teamId(),
            "fromSessionId", "session-coordinator"
        ));

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("Member not found");
    }

    @Test
    void broadcastReachesAllMembers() {
        Team team = teamManager.createTeam("t2", "goal");
        teamManager.addMember(team.teamId(),
            new TeamMember("m1", "w1", TeamRole.RESEARCHER, "s1"));
        teamManager.addMember(team.teamId(),
            new TeamMember("m2", "w2", TeamRole.IMPLEMENTER, "s2"));

        SendMessageTool tool = new SendMessageTool(teamManager, messageBus);
        ToolResult result = tool.execute(Map.of(
            "to", "*",
            "message", "broadcast msg",
            "teamId", team.teamId(),
            "fromSessionId", "s-coord"
        ));

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("\"delivered\": true");

        assertEquals(1, messageBus.poll("s1").size());
        assertEquals(1, messageBus.poll("s2").size());
    }

    @Test
    void sendMessageMissingParams() {
        SendMessageTool tool = new SendMessageTool(teamManager, messageBus);

        ToolResult result = tool.execute(Map.of("to", "w1"));

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("'message' is required");
    }

    @Test
    void messageBusDirectSendAndPoll() {
        messageBus.send("session-w1", "session-coordinator", "hello worker");
        List<MessageBus.TeamMessage> msgs = messageBus.poll("session-w1");
        assertEquals(1, msgs.size());
        assertEquals("hello worker", msgs.get(0).content());
        assertEquals("session-coordinator", msgs.get(0).fromSessionId());
    }

    @Test
    void messageBusBroadcastDirect() {
        Team team = teamManager.createTeam("t3", "goal");
        teamManager.addMember(team.teamId(),
            new TeamMember("m1", "w1", TeamRole.RESEARCHER, "s1"));
        teamManager.addMember(team.teamId(),
            new TeamMember("m2", "w2", TeamRole.IMPLEMENTER, "s2"));

        messageBus.broadcast(team.teamId(), "s-coord", "broadcast msg", teamManager);
        assertEquals(1, messageBus.poll("s1").size());
        assertEquals(1, messageBus.poll("s2").size());
    }

    @Test
    void messageBusBroadcastToNonexistentTeam() {
        boolean result = messageBus.broadcast("no-such-team", "s1", "msg", teamManager);
        assertThat(result).isFalse();
    }

    @Test
    void messageBusPollEmpty() {
        List<MessageBus.TeamMessage> msgs = messageBus.poll("no-such-session");
        assertThat(msgs).isEmpty();
    }

    /* -- helpers -- */

    private static String extractTeamId(String json) {
        int start = json.indexOf("\"teamId\": \"") + "\"teamId\": \"".length();
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }
}
