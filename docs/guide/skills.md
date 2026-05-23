# Skills

Markdown files that get injected into the system prompt when loaded. Think
of them as parameterized expertise — load `commit-message` and the agent
knows how to write conventional commits without you re-explaining each time.

## Built-in skills

Four shipped on the classpath:

- `code-review` — walk a diff, group findings by severity
- `commit-message` — Conventional-Commit style + WHY-focused body
- `refactor` — improve structure without changing behavior
- `test-writer` — discipline for writing / extending tests

List + load via `:skill`:

```
kairo-code> :skill list
○ commit-message
○ code-review
○ refactor
○ test-writer

kairo-code> :skill load commit-message
kairo-code> write a commit for the changes I just made
```

`○` = available, `●` = loaded. Multiple skills can be loaded
simultaneously; they're concatenated into the system prompt.

## User skills

Drop markdown files under:

- `~/.kairo-code/skills/` (global) — available everywhere
- `<project>/.kairo-code/skills/` (project-local) — only when working-dir matches
- `<plugin-dir>/skills/` — installed via `:plugin install`

Frontmatter is optional but supported:

```markdown
---
name: my-skill
description: Brief one-liner shown in :skill list
visibility: VISIBLE     # or USER_ONLY / HIDDEN
disable_model_invocation: false
---

# My skill

Body becomes the system prompt addition. Use it to teach the agent
domain conventions, code style, terminology, etc.
```

## Visibility

- `VISIBLE` (default) — listed in `:skill list`, loadable by user, model can
  see it in available-skills hint
- `USER_ONLY` — listed, loadable by user, but hidden from model's awareness
- `HIDDEN` — not listed at all

Use `USER_ONLY` for skills you want as on-demand context (the model can use
it once loaded, but won't auto-suggest it).

## Skill hot-reload

Edit a file under `.kairo-code/skills/` — the REPL picks it up on next
`:skill load`. No restart needed.
