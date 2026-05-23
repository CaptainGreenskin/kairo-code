# Tools reference

The 17+ built-in tools every kairo-code session exposes to the model.
Provided by upstream [kairo-tools](https://github.com/captaingreenskin/kairo)
unless noted.

## File operations

| Tool | Purpose | LSP-aware (M-D1') |
|---|---|---|
| `read` | Read a file's contents | — |
| `write` | Create/overwrite a file | ✅ post-edit diagnostics |
| `edit` | Find-and-replace edit | ✅ post-edit diagnostics |
| `patch` | Apply a unified diff | — |
| `glob` | List files matching pattern | — |
| `grep` | Search file contents | — |
| `tree` | Print directory tree | — |
| `batch_read` | Read many files in one call | — |
| `batch_write` | Write many files in one call | — |

LSP-aware tools attach `metadata.newDiagnostics` to the `ToolResult` when an
edit introduces new compile errors. Requires a language server on PATH —
see [LSP setup](./env#lsp).

## Shell

| Tool | Purpose | Notes |
|---|---|---|
| `bash` | Execute a shell command | Guarded by `DangerousCommandPolicy` |

## Git

| Tool | Purpose |
|---|---|
| `git` | Read git state (status, log, diff, blame) |

Writes (commit, push) go through `bash` so the agent's intent is explicit.

## Web

| Tool | Purpose |
|---|---|
| `web_fetch` | GET a URL, return cleaned-up text |
| `http` | Generic HTTP client (GET/POST/etc.) |

## Data manipulation

| Tool | Purpose |
|---|---|
| `json_query` | jq-style JSON queries |
| `diff` | Compute / display a unified diff |

## Agent control

| Tool | Purpose |
|---|---|
| `task` | Spawn an isolated sub-agent in a worktree |
| `todo_read` / `todo_write` | Per-session TODO list |
| `ask_user` | Interactive prompt for human input |

## Adding tools

Implement `io.kairo.api.tool.SyncTool` or `AsyncTool`, annotate with
`@Tool`, and add to the registry. Lifecycle / arg parsing / schema is all
reflection-driven via `kairo-tools`. See `WriteTool` in upstream for a
complete reference impl.

## Guardrails

All tools go through the default guardrail chain (PII + dangerous-command +
path-traversal + tool-loop) before / after dispatch — see [PII](../guide/pii)
for the full breakdown.
