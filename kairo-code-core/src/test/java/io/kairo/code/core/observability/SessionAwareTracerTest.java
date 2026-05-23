package io.kairo.code.core.observability;

import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.tracing.Span;
import io.kairo.api.tracing.Tracer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionAwareTracerTest {

    @Test
    void agentSpanGetsSessionAndUserAttributes() {
        RecordingTracer base = new RecordingTracer();
        SessionAwareTracer wrapped = new SessionAwareTracer(base, "sess-123", "ws-cnarch");

        Span s = wrapped.startAgentSpan("agent", Msg.of(MsgRole.USER, "hi"));

        assertThat(((RecordingSpan) s).attrs)
                .containsEntry("session.id", "sess-123")
                .containsEntry("langfuse.session.id", "sess-123")
                .containsEntry("user.id", "ws-cnarch")
                .containsEntry("langfuse.user.id", "ws-cnarch");
    }

    @Test
    void allSpanTypesAreStampedEqually() {
        RecordingTracer base = new RecordingTracer();
        SessionAwareTracer wrapped = new SessionAwareTracer(base, "sess-1");

        wrapped.startAgentSpan("a", null);
        wrapped.startIterationSpan(null, 1);
        wrapped.startReasoningSpan(null, "gpt-4o", 5);
        wrapped.startToolSpan(null, "bash", Map.of("cmd", "ls"));

        assertThat(base.recorded).hasSize(4);
        for (RecordingSpan s : base.recorded) {
            assertThat(s.attrs).containsEntry("session.id", "sess-1");
            assertThat(s.attrs).containsEntry("langfuse.session.id", "sess-1");
            // userId is null in this test → should NOT be set
            assertThat(s.attrs).doesNotContainKey("user.id");
        }
    }

    @Test
    void delegateMethodsPassThrough() {
        RecordingTracer base = new RecordingTracer();
        SessionAwareTracer wrapped = new SessionAwareTracer(base, "sess-x");
        Span s = wrapped.startAgentSpan("a", null);

        wrapped.recordTokenUsage(s, 10, 20, 30, 5);
        wrapped.recordToolResult(s, "ok", true, Duration.ofMillis(100));
        wrapped.recordCompaction(s, "context-overflow", 9000);
        wrapped.recordException(s, new RuntimeException("boom"));

        assertThat(base.callLog)
                .contains("recordTokenUsage(10,20,30,5)",
                        "recordToolResult(ok,true)",
                        "recordCompaction(context-overflow,9000)",
                        "recordException(boom)");
    }

    @Test
    void nullDelegateRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new SessionAwareTracer(null, "sess"));
    }

    // ── Recording test doubles ───────────────────────────────────────────────

    static class RecordingTracer implements Tracer {
        final List<RecordingSpan> recorded = new ArrayList<>();
        final List<String> callLog = new ArrayList<>();

        private RecordingSpan record() {
            RecordingSpan s = new RecordingSpan();
            recorded.add(s);
            return s;
        }

        @Override public Span startAgentSpan(String name, Msg input) { return record(); }
        @Override public Span startIterationSpan(Span parent, int iter) { return record(); }
        @Override public Span startReasoningSpan(Span parent, String m, int n) { return record(); }
        @Override public Span startToolSpan(Span parent, String n, Map<String, Object> in) { return record(); }
        @Override public void recordTokenUsage(Span s, int p, int c, int t, int r) {
            callLog.add("recordTokenUsage(" + p + "," + c + "," + t + "," + r + ")");
        }
        @Override public void recordToolResult(Span s, String result, boolean ok, Duration d) {
            callLog.add("recordToolResult(" + result + "," + ok + ")");
        }
        @Override public void recordCompaction(Span s, String reason, int tokens) {
            callLog.add("recordCompaction(" + reason + "," + tokens + ")");
        }
        @Override public void recordException(Span s, Throwable e) {
            callLog.add("recordException(" + e.getMessage() + ")");
        }
    }

    static class RecordingSpan implements Span {
        final Map<String, Object> attrs = new HashMap<>();
        @Override public String spanId() { return "rec-" + System.identityHashCode(this); }
        @Override public String name() { return "rec"; }
        @Override public Span parent() { return null; }
        @Override public void setAttribute(String key, Object value) { attrs.put(key, value); }
        @Override public void setStatus(boolean ok, String desc) {}
        @Override public void end() {}
    }
}
