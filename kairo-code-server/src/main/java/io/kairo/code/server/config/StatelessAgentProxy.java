package io.kairo.code.server.config;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentDiagnostics;
import io.kairo.api.agent.AgentSnapshot;
import io.kairo.api.agent.AgentState;
import io.kairo.api.message.Msg;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/**
 * Delegating Agent that builds a fresh agent instance per {@link #call(Msg)} invocation.
 *
 * <p>SwarmCoordinator workers share a single Agent bean, but DefaultReActAgent accumulates
 * conversation history in its ContextManager across calls. This proxy ensures each swarm
 * step starts with a clean context by constructing a new agent from the provided supplier.
 */
final class StatelessAgentProxy implements Agent {

    private final Supplier<Agent> factory;
    private final String name;
    private volatile Agent lastDelegate;

    StatelessAgentProxy(String name, Supplier<Agent> factory) {
        this.name = name;
        this.factory = factory;
    }

    @Override
    public Mono<Msg> call(Msg input) {
        Agent fresh = factory.get();
        lastDelegate = fresh;
        return fresh.call(input);
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
        Agent d = lastDelegate;
        return d != null ? d.state() : AgentState.IDLE;
    }

    @Override
    public void interrupt() {
        Agent d = lastDelegate;
        if (d != null) {
            d.interrupt();
        }
    }

    @Override
    public AgentSnapshot snapshot() {
        throw new UnsupportedOperationException("Stateless proxy does not support snapshots");
    }

    @Override
    public AgentDiagnostics diagnostics() {
        Agent d = lastDelegate;
        return d != null ? d.diagnostics() : null;
    }
}
