package io.kairo.code.service.agent;

import io.kairo.api.agent.Agent;
import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.code.service.AgentEvent;
import io.kairo.code.service.SessionPhase;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Base64;
import java.util.Objects;

/**
 * Single-agent session payload wrapping a {@link CodeAgentSession} and its
 * {@link CodeAgentConfig}.
 *
 * <p>This is the default payload for "chat" mode sessions. It translates a
 * {@link MessageRequest} into a {@link Msg} and delegates to the underlying
 * agent via {@link Agent#call(Msg)}.
 *
 * <p>Note: the actual subscription + event bridging is still managed by
 * {@link io.kairo.code.service.AgentService} — this class only builds the
 * {@link Msg} and returns the call Flux. Full lifecycle ownership will move
 * here once the refactor is complete.
 */
public final class AgentSessionPayload implements SessionPayload {

    private final CodeAgentConfig config;
    private final CodeAgentSession session;

    public AgentSessionPayload(CodeAgentConfig config, CodeAgentSession session) {
        this.config = Objects.requireNonNull(config, "config");
        this.session = Objects.requireNonNull(session, "session");
    }

    /**
     * Build a {@link Msg} from the request and delegate to {@code agent.call()}.
     *
     * <p>The returned Flux is the raw agent response; the caller
     * ({@link io.kairo.code.service.AgentService}) subscribes, wires the sink,
     * and manages concurrency/lifecycle.
     */
    @Override
    public Flux<AgentEvent> handleMessage(MessageRequest request) {
        // Delegate to AgentService for now — lifecycle management (sink, concurrency,
        // progress tracker) stays there. This method returns an empty Flux as a
        // marker; the actual agent.call() is still wired in AgentService.sendMessage.
        // Full delegation will land in a follow-up task.
        throw new UnsupportedOperationException(
                "Direct handleMessage not yet wired — use AgentService.sendMessage");
    }

    @Override
    public void stop() {
        session.agent().interrupt();
    }

    @Override
    public boolean isRunning() {
        // Running state is tracked externally in AgentService.runningState
        return false;
    }

    @Override
    public SessionPhase getState() {
        // Single-agent sessions don't use the plan-preview state machine;
        // report IDLE unless externally overridden.
        return SessionPhase.IDLE;
    }

    // ── Accessors for backward compatibility with AgentService internals ──

    /** The agent configuration for this session. */
    public CodeAgentConfig config() {
        return config;
    }

    /** The underlying session (agent + tool runtime state). */
    public CodeAgentSession session() {
        return session;
    }

    /**
     * Build the user {@link Msg} from a {@link MessageRequest}.
     * Extracted here so AgentService.sendMessage can use it during the transition period.
     */
    public Msg buildUserMsg(MessageRequest request) {
        if (request.hasImage()) {
            byte[] bytes = Base64.getDecoder().decode(request.imageData());
            return Msg.builder()
                    .role(MsgRole.USER)
                    .addContent(new Content.TextContent(request.text()))
                    .addContent(new Content.ImageContent(null, request.imageMediaType(), bytes))
                    .build();
        }
        return Msg.of(MsgRole.USER, request.text());
    }
}
