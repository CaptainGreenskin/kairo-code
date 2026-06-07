package io.kairo.code.service.testutil;

import io.kairo.api.message.Msg;
import io.kairo.api.team.MessageBus;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * In-memory {@link MessageBus} that actually delivers messages.
 * Each {@code agentId} gets a hot {@link Sinks.Many} — {@code send()} emits into the
 * recipient's sink, and {@code receive()} returns the corresponding {@link Flux}.
 */
public final class InMemoryMessageBus implements MessageBus {

    private final ConcurrentMap<String, Sinks.Many<Msg>> channels = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> send(String fromAgentId, String toAgentId, Msg message) {
        Sinks.Many<Msg> sink = channels.computeIfAbsent(toAgentId,
                k -> Sinks.many().multicast().onBackpressureBuffer());
        sink.tryEmitNext(message);
        return Mono.empty();
    }

    @Override
    public Flux<Msg> receive(String agentId) {
        Sinks.Many<Msg> sink = channels.computeIfAbsent(agentId,
                k -> Sinks.many().multicast().onBackpressureBuffer());
        return sink.asFlux();
    }

    @Override
    public Mono<Void> broadcast(String fromAgentId, Msg message) {
        channels.forEach((id, sink) -> {
            if (!id.equals(fromAgentId)) {
                sink.tryEmitNext(message);
            }
        });
        return Mono.empty();
    }
}
