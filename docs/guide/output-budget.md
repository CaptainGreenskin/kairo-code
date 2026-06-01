# Output-token Budget DSL

Tell the agent how much output you want from a turn — it'll keep working
until it hits that mark (or until the model has nothing more to say).

> Mirrors Claude Code's [`token_budget`](https://docs.claude.com/en/docs/claude-code/)
> syntax so prompts port over verbatim.

## Why

The default behaviour is "the model stops when it thinks it's done."
That works for short questions and fails for long jobs — refactors,
batch edits, audits — where the model often wraps up prematurely and
you have to press Enter to nudge it along.

Output budget flips this: you declare an output-token floor; on every
reasoning turn the hook checks progress; under 90% it injects a
"keep working" nudge automatically. No manual continuation prompts.

## Syntax

Three equivalent forms:

| Form                                       | Example                                    |
|--------------------------------------------|--------------------------------------------|
| Shorthand at start                         | `+500k explain the codebase`               |
| Shorthand at end (with optional punct.)    | `refactor the auth module +2m.`            |
| Verbose (natural language)                 | `spend 2M tokens auditing this`            |
|                                            | `use 1B tokens generating the report`      |

Units: `k` = 1 000, `m` = 1 000 000, `b` = 1 000 000 000. Case
insensitive. Decimal multipliers (`+1.5m`) supported.

The budget syntax is stripped from the prompt before it's sent to the
model — the model sees `"explain the codebase"`, not the `+500k` prefix.

## Behaviour

When the REPL detects a budget:

```
[output budget: 500k tokens — agent will keep working until ≥ 90% used]
```

is printed in cyan. Then each model turn:

| Condition                          | Action                                |
|------------------------------------|---------------------------------------|
| Output tokens this turn < 90%      | Inject continuation nudge, run again  |
| Output tokens this turn ≥ 90%      | Let the model stop naturally          |
| 3 nudges in a row produced < 500 tokens | Stop (diminishing returns)         |

The 90% threshold prevents the final nudge from massively overshooting
the requested budget. The diminishing-returns guard catches "model has
nothing more to say" cases that would otherwise loop forever.

The budget is **per-prompt** — the next time you submit without the
syntax, you're back to normal one-shot behaviour. Press `Ctrl-C` to
cancel mid-turn (also clears the budget).

## When to use it

| Use case                            | Suggested budget |
|-------------------------------------|------------------|
| Short answer / single bug fix       | (don't bother)   |
| Module-scale refactor               | `+200k` to `+500k` |
| Cross-module audit / large rewrite  | `+1m` to `+2m`   |
| Generate a long technical report    | `+2m` to `+5m`   |
| Batch test fixing (SWE-bench style) | per-task `+500k` |

## What it doesn't do

- It does NOT increase the model's per-call max-output cap (typically
  4k–32k depending on provider). The budget accumulates ACROSS calls.
- It does NOT pre-allocate tokens or reserve quota — you pay only for
  what the model actually outputs.
- It does NOT override `--max-iterations` or the model's natural stop
  conditions; it just suppresses early termination via the nudge loop.

## Tip: combine with batch mode

```bash
kairo-code --task "+1m audit src/main/ for null-safety violations"
```

In one-shot / batch mode the budget is especially useful because there's
no human to manually press Enter for continuation.
