package io.kairo.code.service.testutil;

import io.kairo.api.agent.Agent;
import io.kairo.api.message.Msg;
import io.kairo.api.team.MessageBus;
import io.kairo.api.team.Team;
import io.kairo.api.team.TeamCreateRequest;
import io.kairo.api.team.TeamManager;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public final class StubTeamPrimitives {
    private StubTeamPrimitives() {}

    public static TeamManager teamManager() {
        return new TeamManager() {
            @Override
            public Team create(TeamCreateRequest req) {
                return new Team("stub", List.of(), messageBus());
            }
            @Override
            public void delete(String teamId) {}
            @Override
            public Team get(String teamId) { return null; }
            @Override
            public void addAgent(String teamId, Agent agent) {}
            @Override
            public void removeAgent(String teamId, String agentId) {}
        };
    }

    public static MessageBus messageBus() {
        return new NoopMessageBus();
    }
}
