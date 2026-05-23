package io.kairo.code.core.observability;

import io.kairo.api.message.Msg;
import io.kairo.api.tracing.Span;
import io.kairo.api.tracing.Tracer;
import java.time.Duration;
import java.util.Map;

/**
 * Decorator over an upstream {@link Tracer} that stamps the agent's session id
 * (and optional user / workspace id) on every span it produces. The session-id
 * attributes are what Langfuse uses to roll up multi-turn chats into one
 * "Session" view — without them, every {@code agent.run} appears as an
 * isolated trace and there's no way in the UI to follow a conversation across
 * turns.
 *
 * <p>Attribute names cover three conventions so the spans render correctly in
 * the most common backends:
 * <ul>
 *   <li>{@code session.id} — OpenTelemetry GenAI semantic-convention key</li>
 *   <li>{@code langfuse.session.id} — Langfuse-specific override (takes
 *       precedence in the Langfuse UI's grouping)</li>
 *   <li>{@code langfuse.user.id} — Langfuse user grouping (workspace id or
 *       OS user, whichever the caller has)</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * Tracer base = OTelTracerFactory.create();
 * Tracer sessionScoped = new SessionAwareTracer(base, sessionId, workspaceId);
 * sessionOptions.withTracer(sessionScoped);
 * }</pre>
 */
public final class SessionAwareTracer implements Tracer {

    private final Tracer delegate;
    private final String sessionId;
    private final String userId;

    public SessionAwareTracer(Tracer delegate, String sessionId) {
        this(delegate, sessionId, null);
    }

    public SessionAwareTracer(Tracer delegate, String sessionId, String userId) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate Tracer required");
        }
        this.delegate = delegate;
        this.sessionId = sessionId;
        this.userId = userId;
    }

    private Span stamp(Span span) {
        if (span == null) return span;
        if (sessionId != null) {
            span.setAttribute("session.id", sessionId);
            span.setAttribute("langfuse.session.id", sessionId);
        }
        if (userId != null) {
            span.setAttribute("user.id", userId);
            span.setAttribute("langfuse.user.id", userId);
        }
        return span;
    }

    @Override
    public Span startAgentSpan(String agentName, Msg input) {
        return stamp(delegate.startAgentSpan(agentName, input));
    }

    @Override
    public Span startIterationSpan(Span parent, int iteration) {
        return stamp(delegate.startIterationSpan(parent, iteration));
    }

    @Override
    public Span startReasoningSpan(Span parent, String modelName, int messageCount) {
        return stamp(delegate.startReasoningSpan(parent, modelName, messageCount));
    }

    @Override
    public Span startToolSpan(Span parent, String toolName, Map<String, Object> input) {
        return stamp(delegate.startToolSpan(parent, toolName, input));
    }

    @Override
    public void recordTokenUsage(Span span, int prompt, int completion, int total, int reasoning) {
        delegate.recordTokenUsage(span, prompt, completion, total, reasoning);
    }

    @Override
    public void recordToolResult(Span span, String result, boolean success, Duration duration) {
        delegate.recordToolResult(span, result, success, duration);
    }

    @Override
    public void recordCompaction(Span span, String reason, int tokensBefore) {
        delegate.recordCompaction(span, reason, tokensBefore);
    }

    @Override
    public void recordException(Span span, Throwable error) {
        delegate.recordException(span, error);
    }
}
