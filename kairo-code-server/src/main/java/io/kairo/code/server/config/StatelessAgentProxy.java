package io.kairo.code.server.config;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentDiagnostics;
import io.kairo.api.agent.AgentSnapshot;
import io.kairo.api.agent.AgentState;
import io.kairo.api.message.Msg;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/**
 * Delegating Agent that builds a fresh agent instance per {@link #call(Msg)} invocation.
 *
 * <p>SwarmCoordinator workers share a single Agent bean, but DefaultReActAgent accumulates
 * conversation history in its ContextManager across calls. This proxy ensures each swarm
 * step starts with a clean context by constructing a new agent from the provided supplier.
 *
 * <p>All active delegates are tracked so {@link #interrupt()} cancels every in-flight call,
 * not just the most recent one (which was the previous behavior and caused parallel workers
 * to silently ignore cancel).
 */
final class StatelessAgentProxy implements Agent {

    private final Supplier<Agent> factory;
    private final String name;
    private final Set<Agent> activeDelegates = ConcurrentHashMap.newKeySet();

    StatelessAgentProxy(String name, Supplier<Agent> factory) {
        this.name = name;
        this.factory = factory;
    }

    @Override
    public Mono<Msg> call(Msg input) {
        Agent fresh = factory.get();
        activeDelegates.add(fresh);
        return fresh.call(input)
                .doFinally(sig -> activeDelegates.remove(fresh));
    }

    @Override
    public String id() {
        return name + "-proxy";
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public AgentState state() {
        for (Agent d : activeDelegates) {
            AgentState s = d.state();
            if (s != AgentState.IDLE && s != AgentState.COMPLETED) return s;
        }
        return AgentState.IDLE;
    }

    @Override
    public void interrupt() {
        for (Agent d : activeDelegates) {
            d.interrupt();
        }
    }

    @Override
    public AgentSnapshot snapshot() {
        throw new UnsupportedOperationException("Stateless proxy does not support snapshots");
    }

    @Override
    public AgentDiagnostics diagnostics() {
        for (Agent d : activeDelegates) {
            AgentDiagnostics diag = d.diagnostics();
            if (diag != null) return diag;
        }
        return null;
    }
}
