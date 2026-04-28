package io.kairo.code.cli.task;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentSnapshot;
import io.kairo.api.agent.AgentState;
import io.kairo.api.message.Msg;
import io.kairo.code.cli.notifications.NotificationService;
import reactor.core.publisher.Mono;

/** Wraps an agent and fires a desktop notification when {@link #call} succeeds. */
final class NotifyingAgent implements Agent {

    private final Agent delegate;
    private final String title;
    private final String message;

    NotifyingAgent(Agent delegate, String title, String message) {
        this.delegate = delegate;
        this.title = title;
        this.message = message;
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public Mono<Msg> call(Msg input) {
        return delegate
                .call(input)
                .doOnSuccess(
                        msg -> {
                            if (msg != null) {
                                NotificationService.notify(title, message);
                            }
                        });
    }

    @Override
    public AgentState state() {
        return delegate.state();
    }

    @Override
    public void interrupt() {
        delegate.interrupt();
    }

    @Override
    public AgentSnapshot snapshot() {
        return delegate.snapshot();
    }
}
