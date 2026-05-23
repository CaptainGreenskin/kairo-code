# Environment variables

Complete list of env vars Kairo Code reads, grouped by domain.

## Model provider

| Var | Effect |
|---|---|
| `KAIRO_CODE_API_KEY` | Provider API key (alternative to `--api-key` / config file) |
| `KAIRO_CODE_BASE_URL` | Provider endpoint (alternative to `--base-url`) |
| `KAIRO_CODE_CHAT_PATH` | Chat completions path suffix (alternative to `--chat-path`) |
| `KAIRO_CODE_PROVIDER` | `openai` / `anthropic` / `qianwen` / `glm` (alternative to `--provider`) |

Precedence: CLI flag > env var > `~/.kairo-code/config.properties` > built-in default.

## Governance

| Var | Effect |
|---|---|
| `KAIRO_PII_REDACTION` | Set `off` to disable the default 4-policy guardrail chain |
| `KAIRO_STREAMING` | Set `off` to force non-streaming model calls (debug only — see note) |

::: warning
`KAIRO_STREAMING=off` is a debug switch. Some providers (MiniMax) require
specific request bodies that the non-streaming path doesn't fully exercise
yet — see M-A4a follow-up in the changelog. Leave streaming on for normal use.
:::

## Observability

| Var | Effect |
|---|---|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | Wire OTLP-HTTP trace export. Setting this enables the SDK auto-init |
| `OTEL_SERVICE_NAME` | Service name in spans (default `kairo-code`) |
| `OTEL_EXPORTER_OTLP_HEADERS` | Header injection (e.g. `authorization=Bearer ...` for Honeycomb) |
| `OTEL_RESOURCE_ATTRIBUTES` | Extra resource attrs (e.g. `deployment.environment=prod`) |

See [Observability](../guide/observability) for the full setup story.

## Server-mode session pool

Only used by `kairo-code-server` (not the CLI).

| Var | Default | Effect |
|---|---|---|
| `KAIRO_SESSION_POOL_SIZE` | 64 | Max concurrent agent sessions |
| `KAIRO_SESSION_IDLE_TTL_MINUTES` | 60 | Idle session eviction TTL |
| `KAIRO_COMPACTION_TRIGGER` | 0.50 | Context-usage threshold for compaction |
| `KAIRO_WEB_PORT` | 3000 | Web UI port (Docker compose default) |

## LSP

LSP is auto-wired and lazy — subprocesses only spawn when a tool calls
`snapshotBaseline` / `currentDiagnostics`. The relevant binaries must be on
PATH:

| Language | Binary |
|---|---|
| Python | `pyright-langserver` |
| TypeScript / JavaScript | `typescript-language-server` |
| Go | `gopls` |
| Rust | `rust-analyzer` |
| C / C++ | `clangd` |
| Java | `jdtls` |

Install via your package manager (`brew`, `apt`, `pacman`, language tool
chains, etc.). Kairo Code does not auto-install language servers.

## Cron + plugins + data dirs

Standard XDG-style:

- `~/.kairo-code/` — root config
- `~/.kairo-code/skills/` — user-global skills
- `~/.kairo-code/plugins/cache/<sha8>/` — extracted plugin source
- `~/.kairo-code/plugins/data/<plugin>/` — plugin runtime data
- `~/.kairo-code/cron/` — durable cron task state
- `~/.kairo-code/curator/` — skill telemetry for the curator daemon
- `~/.kairo-code/snapshots/<key>.json` — named session snapshots
- `~/.kairo-code/sessions/<id>.json` — auto-saved session history
- `~/.kairo-code/runtime/<version>/` — npm-installed jar cache

`HOME` honored via `System.getProperty("user.home")`. No env var override
for the base dir (yet) — file an issue if you need one.

## Tests

For embedded / programmatic test code only:

| Var / system prop | Effect |
|---|---|
| `kairo.code.dryrun` (system prop) | Set `true` so `KairoCodeMain` returns 0 without making API calls — used by `KairoCodeAcpServerTest` to skip the stdin-blocking ACP server loop |
