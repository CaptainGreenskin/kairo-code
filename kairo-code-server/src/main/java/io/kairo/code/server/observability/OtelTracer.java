package io.kairo.code.server.observability;

import io.kairo.api.message.Msg;
import io.kairo.api.tracing.NoopSpan;
import io.kairo.api.tracing.Span;
import io.kairo.api.tracing.Tracer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.Map;

/**
 * OpenTelemetry-backed {@link Tracer} that produces the three-layer span tree consumed by
 * Langfuse: {@code agent.run} → {@code agent.iteration} → ({@code agent.tool} | model spans).
 *
 * <p>Each kairo {@link Span} wraps an OTel {@link io.opentelemetry.api.trace.Span} plus its
 * activation {@link Scope}, so child spans created via the SPI are automatically attached as
 * OTel children even when the SPI doesn't pass a parent. The Spring-AI Micrometer bridge layers
 * its {@code gen_ai.*} model-call spans underneath whichever span is currently active, which is
 * exactly the iteration span by construction.
 */
final class OtelTracer implements Tracer {

    private final io.opentelemetry.api.trace.Tracer otel;

    OtelTracer(OpenTelemetry openTelemetry) {
        this.otel = openTelemetry.getTracer("kairo-code");
    }

    @Override
    public Span startAgentSpan(String agentName, Msg input) {
        // Agent runs are trace roots — never inherit from thread-local Context.current(),
        // which on Reactor scheduler threads can be a leaked Scope from a prior run.
        SpanBuilder b = otel.spanBuilder("agent.run").setNoParent();
        if (agentName != null) {
            b.setAttribute("agent.name", agentName);
        }
        if (input != null) {
            String text = input.text();
            if (text != null) {
                b.setAttribute("user.message.preview", preview(text));
                b.setAttribute("user.message.length", (long) text.length());
                // Langfuse renders the trace's Input panel from `langfuse.trace.input`;
                // `langfuse.observation.input` covers the observation panel; `input.value`
                // is the OpenInference fallback.
                b.setAttribute("langfuse.trace.input", text);
                b.setAttribute("langfuse.observation.input", text);
                b.setAttribute("input.value", text);
            }
        }
        return start(b, null, "agent.run");
    }

    @Override
    public Span startIterationSpan(Span parent, int iteration) {
        SpanBuilder b = otel.spanBuilder("agent.iteration")
                .setParent(parentContext(parent))
                .setAttribute("iter", (long) iteration);
        return start(b, parent, "agent.iteration");
    }

    @Override
    public Span startReasoningSpan(Span parent, String modelName, int messageCount) {
        SpanBuilder b = otel.spanBuilder("agent.reasoning")
                .setParent(parentContext(parent))
                .setAttribute("message.count", (long) messageCount);
        if (modelName != null) {
            b.setAttribute("model.name", modelName);
        }
        return start(b, parent, "agent.reasoning");
    }

    @Override
    public Span startToolSpan(Span parent, String toolName, Map<String, Object> input) {
        SpanBuilder b = otel.spanBuilder("agent.tool")
                .setParent(parentContext(parent))
                .setAttribute("langfuse.observation.type", "tool");
        if (toolName != null) {
            b.setAttribute("tool.name", toolName);
        }
        if (input != null && !input.isEmpty()) {
            String inputStr = input.toString();
            b.setAttribute("tool.input.preview", preview(inputStr));
            // Mirror tool args into Langfuse's Input panel for the tool span.
            b.setAttribute("langfuse.observation.input", inputStr);
            b.setAttribute("input.value", inputStr);
        }
        return start(b, parent, "agent.tool");
    }

    private Span start(SpanBuilder builder, Span parent, String name) {
        try {
            io.opentelemetry.api.trace.Span s = builder.startSpan();
            Scope scope = s.makeCurrent();
            return new OtelSpan(s, parent, name, scope);
        } catch (RuntimeException e) {
            return NoopSpan.INSTANCE;
        }
    }

    private Context parentContext(Span parent) {
        if (parent instanceof OtelSpan os) {
            return Context.current().with(os.otel());
        }
        return Context.current();
    }

    private static String preview(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
