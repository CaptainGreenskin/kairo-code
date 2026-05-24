# Status Line — Custom Shell Footer

The REPL footer can be rendered by a user shell command. The kairo-code
runtime serialises the current session state to JSON, pipes it to the
command via stdin, and renders the command's first stdout line as the
footer above the prompt.

> Inspired by Claude Code's [`statusLine` settings](https://docs.claude.com/en/docs/claude-code/) —
> field shape mirrors that schema so existing scripts port over largely
> unchanged.

## Quick start

Drop a file at `~/.kairo-code/statusline.json`:

```json
{
  "type": "command",
  "command": "bash ~/.kairo-code/statusline.sh",
  "refreshInterval": 0,
  "timeoutMs": 5000
}
```

And a script at `~/.kairo-code/statusline.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail
JSON=$(cat)
MODEL=$(echo "$JSON" | jq -r '.model.displayName // "?"')
USED=$(echo "$JSON" | jq -r '.contextWindow.usedPercentage // 0')
echo -e "\033[90m[$MODEL | ctx ${USED%.*}%]\033[0m"
```

Make it executable: `chmod +x ~/.kairo-code/statusline.sh`. Restart the
REPL. The grey `[<model> | ctx <pct>%]` line appears above every prompt.

## Config layers

Settings merge across three files (later overrides earlier):

| Path                                           | Role                                  |
|------------------------------------------------|---------------------------------------|
| `~/.kairo-code/statusline.json`                | User default — your global setup      |
| `<project>/.kairo/statusline.json`             | Project committed — team's setup      |
| `<project>/.kairo/statusline.local.json`       | Local override — gitignored, per-clone|

A later layer overrides only fields it sets; blank `command` on the
override falls through to the earlier layer. A broken JSON layer is
logged at WARN and skipped — your statusline keeps working.

## Schema

```jsonc
{
  "type": "command",         // required, only "command" today
  "command": "<shell line>",  // required; invoked via /bin/sh -c
  "refreshInterval": 0,       // seconds; 0 = only render on agent turns
  "padding": 0,               // informational, may be ignored
  "timeoutMs": 5000           // per-invocation ceiling; default 5000
}
```

If `command` is blank or the config file is absent, the REPL falls back
to the built-in token bar (`[tokens: 12k/200k]`).

## Input JSON contract

The script receives this shape on stdin:

```jsonc
{
  "sessionId": "repl-d4f...",                   // stable UUID
  "model": {
    "id": "claude-sonnet-4-20250514",
    "displayName": "claude-sonnet-4-20250514"
  },
  "workspace": {
    "currentDir": "/Users/me/projects/foo",      // process cwd
    "projectDir": "/Users/me/projects/foo"       // config workingDir
  },
  "version": "0.2.0-SNAPSHOT",
  "contextWindow": {
    "totalInputTokens": 12345,
    "contextWindowSize": 200000,
    "usedPercentage": 6.17,                     // 0..100, never NaN
    "remainingPercentage": 93.82,
    "compactionPhase": null                      // "snip"/"micro"/... when active
  }
}
```

Fields the runtime can't populate (e.g. `sessionName`, `agent.name`)
are omitted by the JSON serializer. Always `jq -r '.field // default'`
to handle absence gracefully.

## Output contract

- `exit 0` + non-empty stdout → first non-blank line(s) rendered
- `exit 0` + empty stdout → footer cleared
- non-zero exit → footer cleared, exit logged at DEBUG
- exceeding `timeoutMs` → process killed, footer cleared, WARN logged
- stdout truncated at 16 KB to keep a runaway script from OOMing the JVM

Multi-line output is preserved (blank lines dropped, others re-joined)
but **single-line is recommended** — multi-line footers steal vertical
space from the conversation.

## Why a shell, not Java?

- **Language-agnostic** — bash, python, node, jq, any shell pipeline
- **Process-isolated** — a buggy script can't crash the REPL
- **Offline-testable** — `echo '{...}' | ./statusline.sh` reproduces what
  the REPL will render

## Recipes

**Show branch + dirty marker** (requires `git`):

```bash
#!/usr/bin/env bash
cat > /dev/null   # discard JSON; we don't need it
BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "-")
DIRTY=$(git status --porcelain 2>/dev/null | head -c 1)
[ -n "$DIRTY" ] && BRANCH="${BRANCH}*"
echo -e "\033[34m[$BRANCH]\033[0m"
```

**Show model + cost-budget remaining** (when CostBudget is wired):

```bash
#!/usr/bin/env bash
JSON=$(cat)
MODEL=$(echo "$JSON" | jq -r '.model.displayName')
COST_REMAINING=$(cat ~/.kairo-code/cost-budget.json 2>/dev/null \
  | jq -r '.remaining // 0' || echo "0")
printf "[%s | \$%.2f left]\n" "$MODEL" "$COST_REMAINING"
```

**Empty footer when nothing interesting**:

```bash
#!/usr/bin/env bash
JSON=$(cat)
USED=$(echo "$JSON" | jq -r '.contextWindow.usedPercentage')
# Only show footer when > 50% used
awk -v u="$USED" 'BEGIN { if (u < 50) exit 1 }' && \
  echo -e "\033[33m[ctx ${USED%.*}% — consider /compact]\033[0m"
```
