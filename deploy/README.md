# Deploying Kairo Code Server

Three options, lowest to highest operational surface:

| Method | Use when | Read |
|---|---|---|
| **Docker Compose** | Single host, hobby setup, easy `up -d` | `../docker-compose.yml` |
| **systemd** | Single host, no container runtime, prefer host-native | `systemd/` |
| **Kubernetes** | Multi-tenant, GitOps, autoscaling target | `k8s/` |

## Common contract — what the server needs to run

Regardless of method, three things are required:

1. **API key** — `KAIRO_CODE_API_KEY` env var, or `api-key=…` in `~/.kairo-code/config.properties` mounted into the container.
2. **Provider tuple matched** — `KAIRO_PROVIDER` + `KAIRO_BASE_URL` + `KAIRO_MODEL` should move together. ProviderRegistry derives any unset values from the canonical defaults for the named provider, but explicit is clearer for ops.
3. **Two writeable paths** —
   - `/data` (or wherever `$HOME` resolves) for `~/.kairo-code/` (sessions, snapshots, cron state, curator telemetry). Single-pod scoped.
   - `KAIRO_WORKING_DIR` (defaults to `/workspace` in Docker, `$PWD` for CLI) — the code the agent operates on. Bind-mount your repo here.

## Health probes

`/api/healthz` is the deep-check endpoint:
- **200 + `status:UP`** when the API key is set AND `KAIRO_WORKING_DIR` is writeable.
- **503 + `status:DOWN`** otherwise, with the failing field in the response body.

It deliberately does NOT call the LLM — that would burn quota on every probe and could rate-limit real chat. For "is the JVM up?" use Spring's default `/actuator/health`.

Recommended probe wiring:
- Liveness → `/actuator/health` (cheap, JVM-up)
- Readiness → `/api/healthz` (won't take traffic until config valid)

## Docker Compose

```bash
cp .env.example .env       # set KAIRO_CODE_API_KEY (+ provider triple if not GLM)
docker compose up -d --build
open http://localhost:3000
```

The compose file:
- Builds the server jar inside Docker (multi-stage), no prior `mvn package` needed.
- Mounts `kairo-data` at `/data` so sessions survive `down`/`up`.
- Bind-mount your repo into `/workspace` via `KAIRO_WORKSPACE_HOST=/abs/path/to/repo` in `.env`.
- Resource caps: 2G mem for server, 128M for web nginx.

## systemd

```bash
sudo useradd --system --home-dir /var/lib/kairo-code --create-home kairo-code
sudo mkdir -p /opt/kairo-code
sudo cp kairo-code-server/target/kairo-code-server-*.jar /opt/kairo-code/server.jar
sudo cp deploy/systemd/kairo-code-server.service /etc/systemd/system/
sudo cp deploy/systemd/kairo-code-server.env.example /etc/default/kairo-code-server
sudo chmod 600 /etc/default/kairo-code-server
# edit /etc/default/kairo-code-server, set KAIRO_CODE_API_KEY
sudo systemctl daemon-reload
sudo systemctl enable --now kairo-code-server
curl http://localhost:8080/api/healthz | jq
```

Unit ships with `ProtectSystem=strict`, `PrivateTmp=true`, and `MemoryMax=2G`. Bump caps in a drop-in (`/etc/systemd/system/kairo-code-server.service.d/limits.conf`) rather than editing the unit.

## Kubernetes

```bash
# 1. ConfigMap (provider / model / session-pool tuning).
kubectl apply -f deploy/k8s/configmap.yaml

# 2. Secret — create real one, NEVER commit the template's REPLACE_ME.
kubectl create secret generic kairo-code-secret \
  --from-literal=KAIRO_CODE_API_KEY=sk-real

# 3. Deployment + 2 PVCs + Service.
kubectl apply -f deploy/k8s/deployment.yaml

# 4. (optional) Ingress with nginx-ingress + cert-manager.
kubectl apply -f deploy/k8s/ingress.yaml
```

Single-replica is hard-coded (`strategy.type=Recreate`) because:
- The session map is in-process (no shared session store yet — M-MultiNode milestone).
- PVCs are `ReadWriteOnce`.

For HA, swap in S3-backed `SnapshotStore` + Redis-backed `SessionRegistry` (both exist as SPIs in `kairo-core`; wiring is the M-MultiNode milestone). Then bump `replicas:` and switch `strategy:` to `RollingUpdate`.

WebSocket connections are long-lived. The Ingress includes `proxy-read-timeout: 600s` so a slow model response doesn't kill the connection mid-stream.

## Image hosting

CI publishes images to `ghcr.io/captaingreenskin/kairo-code-server`. For air-gapped installs, `docker save` the local image and load on target hosts.
