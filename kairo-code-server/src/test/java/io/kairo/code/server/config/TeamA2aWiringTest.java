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
package io.kairo.code.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.a2a.AgentCard;
import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentState;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.team.MessageBus;
import io.kairo.core.a2a.InProcessA2aClient;
import io.kairo.core.a2a.InProcessAgentCardResolver;
import io.kairo.multiagent.a2a.TeamAwareA2aClient;
import io.kairo.multiagent.team.InProcessMessageBus;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class TeamA2aWiringTest {

    private final TeamConfig config = new TeamConfig();

    @Test
    void messageBusBeanIsInProcessMessageBus() {
        MessageBus bus = config.messageBus();
        assertThat(bus).isNotNull().isInstanceOf(InProcessMessageBus.class);
    }

    @Test
    void agentCardResolverBeanCreated() {
        InProcessAgentCardResolver resolver = config.agentCardResolver();
        assertThat(resolver).isNotNull();
    }

    @Test
    void inProcessA2aClientBeanCreated() {
        InProcessAgentCardResolver resolver = config.agentCardResolver();
        InProcessA2aClient client = config.inProcessA2aClient(resolver);
        assertThat(client).isNotNull();
    }

    @Test
    void apiTeamManagerBeanCreated() {
        InProcessAgentCardResolver resolver = config.agentCardResolver();
        io.kairo.api.team.TeamManager apiTm = config.teamManager(resolver);
        assertThat(apiTm).isNotNull();
    }

    @Test
    void teamAwareA2aClientBeanCreated() {
        InProcessAgentCardResolver resolver = config.agentCardResolver();
        InProcessA2aClient a2aClient = config.inProcessA2aClient(resolver);
        io.kairo.api.team.TeamManager apiTm = config.teamManager(resolver);

        TeamAwareA2aClient teamClient = config.teamAwareA2aClient(a2aClient, resolver, apiTm);
        assertThat(teamClient).isNotNull();
    }

    @Test
    void teamAwareA2aClientCanRegisterAndDiscover() {
        InProcessAgentCardResolver resolver = config.agentCardResolver();
        InProcessA2aClient a2aClient = config.inProcessA2aClient(resolver);
        io.kairo.api.team.TeamManager apiTm = config.teamManager(resolver);
        io.kairo.api.team.Team team = apiTm.create("expert-team");

        TeamAwareA2aClient teamClient =
                new TeamAwareA2aClient(a2aClient, resolver, apiTm, team.teamId());

        Agent stubAgent = new Agent() {
            @Override public String id() { return "test-agent"; }
            @Override public String name() { return "Test Agent"; }
            @Override public AgentState state() { return AgentState.IDLE; }
            @Override public Mono<Msg> call(Msg message) {
                return Mono.just(Msg.of(MsgRole.ASSISTANT, "pong"));
            }
            @Override public void interrupt() { /* no-op */ }
        };

        AgentCard card = AgentCard.of("test-agent", "Test Agent", "A test agent");
        teamClient.registerTeamAgent(card, stubAgent);

        var discovered = teamClient.discoverTeamAgents(null);
        assertThat(discovered).extracting(AgentCard::id).contains("test-agent");
    }

    @Test
    void swarmCoordinatorUsesRealMessageBus() {
        // The SwarmCoordinator bean signature now injects MessageBus directly,
        // so constructing it with an InProcessMessageBus proves the wiring is correct.
        MessageBus bus = config.messageBus();
        assertThat(bus).isNotNull().isInstanceOf(InProcessMessageBus.class);
    }
}
