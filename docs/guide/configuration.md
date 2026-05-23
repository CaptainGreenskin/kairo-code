# Configuration

Three layers, lowest precedence first:

1. **`~/.kairo-code/config.properties`** — file-based defaults
2. **Environment variables** — override file
3. **CLI flags** — override env

::: warning
A common dev mistake: `MINIMAX_KEY=x java -jar ... --api-key "$MINIMAX_KEY"`
*looks* like it sets the env var for the java process AND expands `$MINIMAX_KEY`
in the args. It doesn't — bash evaluates `$MINIMAX_KEY` from the **calling
shell** (where it's still empty) before the inline assignment takes effect.
Use `export MINIMAX_KEY=x; ...` or `KAIRO_CODE_API_KEY=x java -jar ...`
(reading from env inside the program) instead.
:::

## Config file

`~/.kairo-code/config.properties`:

```properties
api-key=sk-...
base-url=https://api.openai.com
chat-path=/chat/completions
model=gpt-4o
provider=openai
```

Only `api-key` is required (and only if not supplied via env / flag).

The web server (`kairo-code-server`) reads the same file: any key set here is
visible to the web UI on the next server start, and saves from
**Settings → Account** write back to this file (atomic, mode 600). So a key
written by the web UI is picked up by the next `kairo-code` REPL invocation,
and a key edited by hand in `config.properties` shows up in the web UI on the
next server start.

## Environment variables

| Var | Maps to | Notes |
|---|---|---|
| `KAIRO_CODE_API_KEY` | `--api-key` | Required if no key in file or flag |
| `KAIRO_CODE_BASE_URL` | `--base-url` | Override only when not OpenAI |
| `KAIRO_CODE_CHAT_PATH` | `--chat-path` | Most providers use default |
| `KAIRO_CODE_PROVIDER` | `--provider` | `openai` / `anthropic` / `qianwen` / `glm` |
| `KAIRO_PII_REDACTION` | n/a | Set `off` to disable PII guardrail |
| `KAIRO_STREAMING` | n/a | Set `off` to use non-streaming model calls |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | n/a | Enable OTLP trace export (see [Observability](./observability)) |
| `OTEL_SERVICE_NAME` | n/a | Service name in spans (default `kairo-code`) |
| `OTEL_EXPORTER_OTLP_HEADERS` | n/a | E.g. `authorization=Bearer ...` for Honeycomb / Langfuse |
| `KAIRO_SESSION_POOL_SIZE` | n/a | Server-mode session pool max (default 64) |
| `KAIRO_SESSION_IDLE_TTL_MINUTES` | n/a | Server-mode idle session eviction TTL |

## CLI flags

Run `kairo-code --help` for the complete list. The frequently-used ones:

| Flag | Default | Notes |
|---|---|---|
| `--task <text>` | — | One-shot mode; omit for REPL |
| `--task-file <path>` | — | Read task from a markdown file |
| `--task-list <path>` | — | Run multiple tasks from a YAML/text list |
| `--api-key <key>` | env / file | The model provider's API key |
| `--base-url <url>` | `https://api.openai.com` | Provider endpoint |
| `--chat-path <path>` | provider default | Endpoint suffix |
| `--model <name>` | `gpt-4o` | Model identifier |
| `--provider <p>` | `openai` | `openai` / `anthropic` / `qianwen` / `glm` |
| `--working-dir <path>` | `$PWD` | Where tool ops happen |
| `--max-iterations <n>` | 50 | Hard cap on ReAct loop |
| `--timeout <s>` | 3600 | Per-call wall-clock limit |
| `--plan` | off | Read-only Plan Mode — blocks write tools |
| `--no-hooks` | off | Disable auto-registered hooks |
| `--no-notifications` | off | Suppress desktop alerts on task done |
| `--resume` | off | Load checkpoint from working-dir |
| `--show-usage` | off | Print token usage to stderr after task |
| `--acp-server` | off | Run as Agent Client Protocol stdio server |

## Per-session overrides in REPL

Inside the REPL, several slash commands change settings live:

- `:model <name>` — swap the model mid-session
- `:plan on|off|toggle` — flip Plan Mode
- `:skill load <name>` — inject a skill's instructions
- `:plugin enable|disable <id>` — toggle plugins
- `:cron add ... / :cron start` — schedule background tasks

See [Commands reference](../reference/commands) for the full set.
