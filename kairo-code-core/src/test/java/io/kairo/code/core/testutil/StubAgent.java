package io.kairo.code.core.testutil;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentState;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import java.util.UUID;
import reactor.core.publisher.Mono;

public class StubAgent implements Agent {
    private final String id;
    private final String name;

    public StubAgent() {
        this("stub-" + UUID.randomUUID().toString().substring(0, 8));
    }

    public StubAgent(String name) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
    }

    @Override
    public String id() { return id; }

    @Override
    public String name() { return name; }

    @Override
    public Mono<Msg> call(Msg input) {
        return Mono.just(Msg.of(MsgRole.ASSISTANT, "stub response"));
    }

    @Override
    public AgentState state() { return AgentState.IDLE; }

    @Override
    public void interrupt() {}

    public static StubAgent fixed(String name, String response) {
        return new StubAgent(name) {
            @Override
            public Mono<Msg> call(Msg input) {
                return Mono.just(Msg.of(MsgRole.ASSISTANT, response));
            }
        };
    }
}
