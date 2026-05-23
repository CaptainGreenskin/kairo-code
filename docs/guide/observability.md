# Observability — Metrics + OTLP Tracing

Kairo Code emits Micrometer metrics and OpenTelemetry traces. Both are no-op
by default — the cost is only paid when you opt in via environment variables.

## Metrics (Micrometer, always on)

Every session registers a `SimpleMeterRegistry` and binds `AgentMetrics` as
the global `AgentCallObserver`. Meters are visible in `:metrics`:

```
kairo.agent.call.duration   count=12 mean=873.2ms total=10478ms
kairo.agent.calls.total              12
kairo.agents.active         VALUE=4.00
kairo.agents.idle           VALUE=3.00
kairo.agents.running        VALUE=1.00
```

`active = main + 3 swarm workers` once the expert team is bootstrapped.

To export to Prometheus / Datadog / etc., construct a `PrometheusMeterRegistry`
(or appropriate) and set it via `MeterRegistry` injection. The default
`SimpleMeterRegistry` is in-process only.

## Tracing (OpenTelemetry, opt-in)

Tracing is wired through `OTelTracerFactory.create()` (kairo-observability),
which auto-detects `GlobalOpenTelemetry`. When the SDK is configured by
`OTEL_EXPORTER_OTLP_ENDPOINT` at process start, every agent call, tool
invocation, and reasoning step gets a span.

### Quick start

```bash
# 1. Set the OTLP endpoint of your collector (Jaeger / Honeycomb / Langfuse /
#    SigNoz / Tempo — anything that speaks OTLP-HTTP works)
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
export OTEL_SERVICE_NAME=kairo-code-dev   # optional, default "kairo-code"

# 2. Optional auth (Honeycomb, Langfuse, etc.)
export OTEL_EXPORTER_OTLP_HEADERS="authorization=Bearer YOUR_API_KEY"

# 3. Run normally
java -jar kairo-code-cli/target/kairo-code-cli-0.2.0-SNAPSHOT.jar
```

The bootstrap (`ReplLoop`) checks for `OTEL_EXPORTER_OTLP_ENDPOINT` and calls
`AutoConfiguredOpenTelemetrySdk.initialize()` only when present. With the var
unset, the SDK is never loaded — zero cost on the happy path.

### Verifying the spans are flowing

After running a `:task` or REPL turn against a collector you've stood up:

```bash
# Jaeger
open http://localhost:16686

# Honeycomb
# Check the "kairo-code" dataset

# Langfuse
# Spans appear under the project's traces view; the upstream
# kairo-observability KairoEventOTelExporter formats them per Langfuse's
# OTel semconv subset.
```

You should see spans named `kairo.agent.call`, `kairo.tool.invoke`,
`kairo.reasoning.step`, etc. (full catalog in
`kairo-observability/GenAiSemanticAttributes`).

### Adding spans from your own hooks

If you write a custom hook (`@HookHandler(...)`), inject a `Tracer` via the
`ToolContext.dependencies()` map or call `OTelTracerFactory.create()` once
at startup. Both routes hit `GlobalOpenTelemetry`.

```java
Tracer tracer = OTelTracerFactory.create();
try (var span = tracer.span("my.custom.work").start()) {
    span.setAttribute("user.id", userId);
    doWork();
}
```

### Disabling at runtime

Unset `OTEL_EXPORTER_OTLP_ENDPOINT` and restart. There is no kill switch
mid-session — the SDK is process-scoped.

### Cost

OTLP batching defaults: 512-span batches every 5 s. Empirically on a busy
REPL session (~50 agent calls / hour, 200 tool invocations) this is
~10 KB / minute network out. Negligible.
