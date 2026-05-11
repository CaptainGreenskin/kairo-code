package io.kairo.code.server.observability;

import io.kairo.api.tracing.Span;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import java.util.Map;

/**
 * Adapter that maps a {@link io.kairo.api.tracing.Span} onto an OpenTelemetry {@link
 * io.opentelemetry.api.trace.Span}.
 *
 * <p>Holds a {@link Scope} so child spans created on the same thread inherit the OTel context
 * automatically. {@link #end()} closes the scope and ends the underlying OTel span.
 *
 * <p>Not thread-safe across {@link #end()} — callers must ensure end is invoked once.
 */
final class OtelSpan implements Span {

    private final io.opentelemetry.api.trace.Span otel;
    private final Span parent;
    private final String name;
    private final Scope scope;

    OtelSpan(io.opentelemetry.api.trace.Span otel, Span parent, String name, Scope scope) {
        this.otel = otel;
        this.parent = parent;
        this.name = name;
        this.scope = scope;
    }

    io.opentelemetry.api.trace.Span otel() {
        return otel;
    }

    @Override
    public String spanId() {
        return otel.getSpanContext().getSpanId();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Span parent() {
        return parent;
    }

    @Override
    public void setAttribute(String key, Object value) {
        if (key == null || value == null) {
            return;
        }
        if (value instanceof String s) {
            otel.setAttribute(AttributeKey.stringKey(key), s);
        } else if (value instanceof Long l) {
            otel.setAttribute(AttributeKey.longKey(key), l);
        } else if (value instanceof Integer i) {
            otel.setAttribute(AttributeKey.longKey(key), i.longValue());
        } else if (value instanceof Double d) {
            otel.setAttribute(AttributeKey.doubleKey(key), d);
        } else if (value instanceof Float f) {
            otel.setAttribute(AttributeKey.doubleKey(key), f.doubleValue());
        } else if (value instanceof Boolean b) {
            otel.setAttribute(AttributeKey.booleanKey(key), b);
        } else {
            otel.setAttribute(AttributeKey.stringKey(key), value.toString());
        }
    }

    @Override
    public void setStatus(boolean success, String message) {
        otel.setStatus(success ? StatusCode.OK : StatusCode.ERROR, message == null ? "" : message);
    }

    @Override
    public void addEvent(String eventName, Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            otel.addEvent(eventName);
            return;
        }
        AttributesBuilder b = Attributes.builder();
        for (Map.Entry<String, Object> e : attributes.entrySet()) {
            Object v = e.getValue();
            if (v == null) continue;
            if (v instanceof String s) {
                b.put(AttributeKey.stringKey(e.getKey()), s);
            } else if (v instanceof Long l) {
                b.put(AttributeKey.longKey(e.getKey()), l);
            } else if (v instanceof Integer i) {
                b.put(AttributeKey.longKey(e.getKey()), i.longValue());
            } else if (v instanceof Double d) {
                b.put(AttributeKey.doubleKey(e.getKey()), d);
            } else if (v instanceof Boolean bool) {
                b.put(AttributeKey.booleanKey(e.getKey()), bool);
            } else {
                b.put(AttributeKey.stringKey(e.getKey()), v.toString());
            }
        }
        otel.addEvent(eventName, b.build());
    }

    @Override
    public void end() {
        try {
            if (scope != null) {
                scope.close();
            }
        } finally {
            otel.end();
        }
    }
}
