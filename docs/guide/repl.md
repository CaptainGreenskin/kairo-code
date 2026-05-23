# REPL workflow

The REPL is the primary surface — `kairo-code` with no args opens it.

## Anatomy of a session

1. **Bootstrap**: ReplLoop wires the agent, hooks (CheckpointWriter,
   ExecutionTrace, AgentEventPrinter), tool registry, guardrail chain,
   plugin manager, cron scheduler, LSP service, expert team coordinator.
2. **Prompt loop**: each line you type is either a `:command` (slash) or a
   user message to the model.
3. **Per-turn**: model call → optional tool calls → tool execution →
   POST_REASONING hook (writes checkpoint) → next turn.

## Daily flow

```
kairo-code
> what's the structure of this repo?       # opens with a survey

> look at FooService and tell me what it does

> :plan on                                  # switch to read-only

> rewrite the test for the edge case I just described

> :plan off                                 # back to write mode

> apply the rewrite

> :compact                                  # squash history if you've been chatty

> what's left to do?

> :exit
```

## Cancel an in-flight turn

`Ctrl+C` interrupts the current agent call without exiting. `Ctrl+D` exits.

## Persistence

Every turn flushes:

- `.kairo-session/checkpoint.json` — full message history (resume with `:resume`)
- `.kairo-trace/session-<id>.jsonl` — phase trace (one line per POST_REASONING / PRE_COMPLETE)
- `KAIRO_SESSION_RESULT.json` — last task's summary (CI-friendly)

Sessions auto-saved across REPL exit. Use `:snapshot save <key>` for named checkpoints you can hop back to later via `:resume <key>`.

## Multi-tab REPL

There's no built-in multiplexer — use tmux:

```bash
tmux new-session -d -s main 'kairo-code --working-dir ~/proj-a'
tmux new-window      -t main 'kairo-code --working-dir ~/proj-b'
tmux attach -t main
```

Each REPL is fully independent (own working-dir, own session file).
