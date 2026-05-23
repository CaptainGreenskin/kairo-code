# External runner protocol

Stable contract for tools (benchmark harnesses, dispatchers, IDE wrappers)
that drive `kairo-code` as a subprocess or REST client and read its results.
Mirrors what `kairo-code-eval` actually consumes today; if any of the
behaviors below regress, the eval CI smoke-test fails.

## Modes

`kairo-code` is invokable in three shapes:

| Mode | Entry | When to use |
|---|---|---|
| **CLI fat jar** | `java -jar kairo-code-cli-X.Y.Z.jar` | Batch / one-shot evaluation; no long-running process. Writes per-session result file to working dir. |
| **REST + WebSocket** | `kairo-code-server` Spring Boot | Web UI, interactive sessions, hot config swap. |
| **Embedded SDK** | `KairoCodeClient` (kairo-code-core public types) | Embedding in your own Java service. |

This page covers the contract for the first two — the SDK is documented in
[Java SDK](../guide/sdk).

## CLI fat jar contract

### Invocation

```bash
java -jar kairo-code-cli-X.Y.Z.jar \
    --working-dir /abs/path/to/repo \
    --task-file /abs/path/to/task.md \
    --provider {openai|anthropic|glm|qianwen} \
    --model <model-id> \
    [--timeout <seconds>] \
    [--tool-budget <int>] \
    [--max-iterations <int>] \
    [--no-hooks] \
    [--no-notifications] \
    [--show-usage] \
    [--verbose]
```

API key comes from `KAIRO_CODE_API_KEY` env (or `--api-key`). Other env
vars are listed in [Environment variables](./env). Provider names normalize
through `ProviderRegistry` — `zhipu` accepted as an alias of `glm`.

### Task file format

Plain markdown; the entire file content becomes the user message. Optional
substitution variables interpreted by the caller (NOT by kairo-code itself —
substitute before passing):

| Variable | Conventional source |
|---|---|
| `{{problem_statement}}` | bug report body |
| `{{repo}}` | `org/repo` |
| `{{base_commit}}` | git SHA |
| `{{hints_section}}` | optional `## Hints\n…` block |

Reference templates live in
[`kairo-code-examples/templates/`](../guide/sdk#prompt-templates).

### Exit codes

| Exit code | Meaning | Caller should |
|---|---|---|
| `0` | Session ended cleanly (`SessionPhase.COMPLETED`) | Read result file, capture patch |
| `1` | Configuration / startup error (missing API key, unknown provider, file IO) | Surface stderr to user; do NOT retry on the same input |
| `2` | Wall-clock timeout (`--timeout` exceeded) | Treat as "timeout" status; result file usually still written with partial metrics |
| `130` | Interrupted (`SIGINT` / `Ctrl-C`) | User aborted; treat like timeout |
| other | Unexpected error | Capture last N lines of stderr; flag as `error` for triage |

### Result file: `KAIRO_SESSION_RESULT.json`

Auto-written into `--working-dir` at `SessionEndEvent` by
[`SessionResultWriterHook`](https://github.com/captaingreenskin/kairo-code/blob/main/kairo-code-core/src/main/java/io/kairo/code/core/hook/SessionResultWriterHook.java).
Stable schema — additive fields only, never rename or remove:

```json
{
  "finalState": "COMPLETED" | "ERROR" | "REVERTED",
  "iterations": 23,
  "tokensUsed": 41273,
  "durationSeconds": 412,
  "error": null,
  "timestamp": "2026-05-23T18:42:00Z",

  "toolCallCounts": { "bash": 12, "read": 18, "batch_write": 4 },
  "redundantReads": [ {"file": "src/Foo.java", "count": 3} ],
  "iterationsWithoutTools": 2,
  "hookInterventions": { "ToolBudgetHook": 1 }
}
```

The bottom four fields appear only when `SessionMetricsCollector` was
auto-registered (every non-REPL session — i.e. the CLI batch mode you're
reading this for). For REPL sessions they're absent.

### Files to ignore when capturing diffs

`kairo-code` writes the following metadata under `--working-dir`. Filter
them out before computing patches / git diffs so they don't pollute the
review surface:

- `KAIRO_SESSION_RESULT.json`
- `.kairo-session/`
- `.kairo-trace/`
- any `*.jsonl` files at repo root

Reference filter: `kairo-code-eval/kairo_eval/agents/base.py:capture_patch`.

### Retry behavior

`kairo-code` itself does NOT retry the LLM call on transient failures
above 1 attempt (kairo-core has internal retry only for model-level
rate-limit). The caller is expected to:

- Treat exit 2 (timeout) as terminal — no retry of same task.
- Treat exit 1 (config) as terminal.
- Treat other non-zero as candidate for retry, but bound it: `kairo-code-eval`'s
  pattern is "git clone has 3 retries with 2^n backoff; agent invocation has
  zero retries" — bias toward producing a partial result rather than re-burning
  quota.

## REST + WebSocket contract

`kairo-code-server` exposes the same data via HTTP. The result-file fields
are mirrored on a live session.

### Session lifecycle

| Verb + path | Purpose |
|---|---|
| WebSocket `/api/agent` | Send `{type: "createSession", config: {...}}`, receive `{type: "sessionCreated", sessionId}`, then send `{type: "userMessage", text}` for each turn |
| `GET /api/sessions/count` | Total live sessions |
| `GET /api/sessions/{id}/metrics` | **Same schema as `KAIRO_SESSION_RESULT.json`** (camelCase JSON), mid-session |
| `POST /api/sessions/{id}/cancel` | Stop a running session |
| `GET /api/healthz` | 200 when api-key set + working-dir writeable |
| `GET /api/config` | Provider / model / base URL the server is using |
| `POST /api/config` | Hot-swap config (persisted) |
| `GET /api/providers` | Canonical provider list (replaces hardcoded UI dropdowns) |
| `GET /api/models` | Canonical model list across all providers |

`/api/sessions/{id}/metrics` returns mid-session values:

```json
{
  "sessionId": "8e9353…",
  "tokensUsed": 0,
  "iterations": 0,
  "durationMillis": 184523,
  "iterationsWithoutTools": 1,
  "toolCallCounts": { "bash": 4, "read": 11 },
  "redundantReads": []
}
```

> **Note**: `tokensUsed` and `iterations` are currently 0 mid-session because
> the agent runtime surfaces them only at `SessionEndEvent`. Use the CLI
> `KAIRO_SESSION_RESULT.json` if you need precise per-session totals; or
> wire `AgentDiagnostics.tokensUsed()` upstream and the REST will follow.

### Versioning

`/api/healthz` will start returning a `protocolVersion` field once the schema
above changes incompatibly. Today everything described here is unversioned
and assumed stable for `0.2.x`. Breaking changes require a `0.3.0` bump.

## Reference implementation: kairo-code-eval

If you want a working consumer of every contract on this page, the
[kairo-code-eval](https://github.com/captaingreenskin/kairo-code-eval)
repository drives `kairo-code` against SWE-bench Verified. Look at:

- [`kairo_eval/agents/base.py`](https://github.com/captaingreenskin/kairo-code-eval/blob/main/kairo_eval/agents/base.py) — `AgentRunner.read_session_result()` (the result-file consumer), `capture_patch()` (metadata-file filter)
- [`kairo_eval/agents/kairo_code.py`](https://github.com/captaingreenskin/kairo-code-eval/blob/main/kairo_eval/agents/kairo_code.py) — the CLI invocation, including the exit-code mapping at the bottom of `run()`
