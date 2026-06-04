package io.kairo.code.core.testutil;

import io.kairo.api.message.Msg;
import io.kairo.api.team.MessageBus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public final class NoopMessageBus implements MessageBus {
    @Override
    public Mono<Void> send(String fromAgentId, String toAgentId, Msg message) {
        return Mono.empty();
    }

    @Override
    public Flux<Msg> receive(String agentId) {
        return Flux.empty();
    }

    @Override
    public Mono<Void> broadcast(String fromAgentId, Msg message) {
        return Mono.empty();
    }
}
