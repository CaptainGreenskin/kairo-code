# Local Observability Stack

OTel collector + Tempo (traces) + Prometheus (metrics) + Grafana (dashboards).
Single-command bring-up for dev / on-call drills / dashboard authoring.

```bash
docker compose -f deploy/observability/docker-compose.otel.yml up -d
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
export OTEL_SERVICE_NAME=kairo-code-dev
# run kairo-code as usual
open http://localhost:3001       # Grafana, admin/admin
```

## What's where

| Service | Host port | Purpose |
|---|---|---|
| `otel-collector` | 4318 (HTTP), 4317 (gRPC) | Receives OTLP from kairo-code; forwards traces → Tempo, metrics → Prometheus |
| `tempo` | 3200 | Trace storage (3-day local retention) |
| `prometheus` | 9090 | Scrapes `/actuator/prometheus` from kairo-code-server + collector self-metrics |
| `grafana` | 3001 | UI; pre-provisioned datasources + "Kairo Code Overview" dashboard |

Grafana port is **3001** on host (mapped from container's 3000) so it doesn't clash with kairo-code-web.

## Dashboards

`grafana/dashboards/kairo-code-overview.json` covers:
- Active sessions, sessions/5min, tool errors/5min, guardrail blocks/5min
- Agent call latency p50/p95/p99
- Tool invocations per second by tool name
- Idle-reaper evictions (correlates with abandoned tabs)
- Tool error rate by tool

Add more JSON files to `grafana/dashboards/` and they auto-provision on next compose up.

## Pointing at remote backends instead

Honeycomb / Langfuse / Datadog all speak OTLP. Skip this whole stack and just:

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=https://api.honeycomb.io
export OTEL_EXPORTER_OTLP_HEADERS="x-honeycomb-team=YOUR_KEY"
```

See `docs/guide/observability.md` for the per-backend recipes.

## Cleanup

```bash
docker compose -f deploy/observability/docker-compose.otel.yml down       # stop
docker compose -f deploy/observability/docker-compose.otel.yml down -v    # stop + wipe volumes
```
