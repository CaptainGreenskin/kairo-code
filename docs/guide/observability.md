# Observability — Metrics + OTLP Tracing

Kairo Code emits Micrometer metrics and OpenTelemetry traces. Both are no-op
by default — the cost is only paid when you opt in via environment variables.

## TL;DR — three working recipes

| You want… | Set these |
|---|---|
| Local Grafana stack (Tempo + Prometheus + Loki) | `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318` + `docker compose -f deploy/observability/docker-compose.otel.yml up -d` |
| Honeycomb | `OTEL_EXPORTER_OTLP_ENDPOINT=https://api.honeycomb.io` + `OTEL_EXPORTER_OTLP_HEADERS="x-honeycomb-team=YOUR_KEY"` |
| Langfuse (LLM-focused, **session-grouped**) | `OTEL_EXPORTER_OTLP_ENDPOINT=https://cloud.langfuse.com/api/public/otel` + `OTEL_EXPORTER_OTLP_HEADERS="Authorization=Basic $(echo -n pk:sk \| base64)"` |

Add `OTEL_SERVICE_NAME=kairo-code-prod` and `OTEL_RESOURCE_ATTRIBUTES=deployment.environment=prod` to either of the cloud setups before going live so trace search isn't a mess.

## Metrics (Micrometer, always on)

Every session registers a `SimpleMeterRegistry` and binds `AgentMetrics` as
the global `AgentCallObserver`. Meters are visible in `:metrics`:

```
kairo.agent.call.duration   count=12 mean=873.2ms total=10478ms
kairo.agent.calls.total              12
kairo.agents.active         VALUE=4.00
kairo.agents.idle           VALUE=3.00
kairo.agents.running        VALUE=1.00
kairo.tool.invoke.duration  count=89 mean=242.1ms (per-tool tags)
kairo.tool.errors           count=3  (per-tool tags)
kairo.session.active        VALUE=1.00
kairo.session.created.total          5
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

### Span catalog

Names emitted by `kairo-core` + `kairo-observability` that you'll see in any
backend:

| Span | When | Key attributes |
|---|---|---|
| `kairo.agent.call` | One LLM round trip | `gen_ai.system`, `gen_ai.request.model`, `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens` |
| `kairo.reasoning.step` | One ReAct step | `step.index`, `tool.calls.count` |
| `kairo.tool.invoke` | Tool execution | `tool.name`, `tool.risk`, `tool.duration_ms`, `tool.outcome` |
| `kairo.session.create` | Session bootstrap | `session.id`, `session.mode` (agent/team), `workspace.id` |
| `kairo.plan.review` | exit_plan_mode prompt | `plan.items_count`, `plan.approved` |
| `kairo.guardrail.block` | Policy refused a tool | `policy.name`, `tool.name`, `policy.reason` |

LLM-focused backends (Langfuse, Helicone) read the `gen_ai.*` attributes per
OpenTelemetry's GenAI semconv — you get input/output/usage/cost grouped per
agent call automatically.

### Langfuse — what you should actually see

Once OTLP is pointed at Langfuse and you run a turn:

- **Traces view** — every chat turn becomes one trace (root span `agent.run`,
  with `langfuse.trace.input` holding the user's message). Cost/usage/latency
  show up at the trace level via the `gen_ai.usage.*` attributes that the
  Spring-AI bridge stamps on the model spans.
- **Sessions view** — every turn from the same chat is grouped under one
  Session, keyed by the kairo session id (REST/WS) or REPL invocation UUID
  (CLI). The wrapping `SessionAwareTracer` writes `langfuse.session.id` on
  every span. Web users see their workspace id under `langfuse.user.id`;
  CLI users see the OS user.
- **Observation panel for tool calls** — `agent.tool` spans render with
  Input / Output panels populated: `langfuse.observation.input` carries
  the tool args JSON; `langfuse.observation.output` carries the tool
  result. Failures get `error` events with the exception message.
- **Score panel** — empty by default. Hook scoring via the
  `kairo-observability` event bus if you want to push automated evals
  (lesson quality, plan acceptance, etc.) as Langfuse Scores.

If the Sessions view is empty even after a turn, double-check
`langfuse.session.id` appears in the trace's attribute panel — that's the
attribute Langfuse groups on. Missing means the tracer wasn't wrapped (see
`AgentService.createSession` / `ReplLoop` bootstrap).

### Verifying the spans are flowing

After running a `:task` or REPL turn against a collector you've stood up:

- **Local Grafana**: `open http://localhost:3001` → Explore → Tempo → search `service.name="kairo-code-dev"`.
- **Honeycomb**: check the `kairo-code` dataset; sort by `duration` desc and click any `kairo.agent.call` span — usage attributes show as a flat table.
- **Langfuse**: spans appear under the project's traces view; the upstream `kairo-observability` `KairoEventOTelExporter` formats them per Langfuse's OTel semconv subset.

If nothing arrives, set `OTEL_LOG_LEVEL=debug` and look for `OkHttpGrpcExporter: Failed to export spans` in stderr — usually a TLS/header issue.

## Local Grafana stack

For dev / on-call drills, run a self-contained OTel collector + Tempo +
Prometheus + Grafana on the host:

```bash
docker compose -f deploy/observability/docker-compose.otel.yml up -d
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
export OTEL_SERVICE_NAME=kairo-code-dev
# run kairo-code-server or kairo-code-cli as usual
open http://localhost:3001       # Grafana, admin/admin
```

The compose file:
- `otel-collector` (port 4318): receives OTLP-HTTP, forwards traces → Tempo, metrics → Prometheus
- `tempo` (port 3200): trace storage
- `prometheus` (port 9090): scrapes `/actuator/prometheus` from kairo-code-server + otel-collector internal metrics
- `grafana` (port 3001): pre-provisioned with Tempo + Prometheus datasources and a "Kairo Code Overview" dashboard

The dashboard tracks: requests/sec by tool, agent call latency p50/p95/p99,
tool error rate, active sessions, idle reaper evictions, guardrail blocks.

To stop: `docker compose -f deploy/observability/docker-compose.otel.yml down`. Volume data (Tempo, Prometheus) survives.

## Cost

OTLP batching defaults: 512-span batches every 5s. Empirically on a busy
REPL session (~50 agent calls / hour, 200 tool invocations) this is
~10 KB / minute network out. Negligible.

Honeycomb pricing: ~$0.05 per million events. A typical 1k-task-per-day deploy
emits ~150k spans/day → ~$0.25/month.

Langfuse free tier: 50k observations/month — enough for solo + small team usage.

## Adding spans from your own hooks

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

## Disabling at runtime

Unset `OTEL_EXPORTER_OTLP_ENDPOINT` and restart. There is no kill switch
mid-session — the SDK is process-scoped.
