---
name: commit-message
version: 1.0.0
category: CODE
triggers:
  - "commit message"
  - "write commit"
  - "/commit"
---
# Commit Message

When asked to write a commit message, produce a Conventional-Commit-style header plus a short body.

**Header**: `<type>(<scope>): <subject>`
- `type` ∈ {feat, fix, refactor, test, docs, build, chore, perf, revert}
- `scope` is optional; use the module name when it clarifies (e.g. `feat(skill): ...`).
- `subject` is imperative mood, ≤ 72 chars, no trailing period.

**Body** (1–3 short paragraphs, blank line after header):
- Explain *why*, not *what*. The diff already shows the what.
- Note any non-obvious tradeoffs or follow-ups.
- Reference issue/ticket IDs if the project uses them.

**Footers** (optional):
- `BREAKING CHANGE: <description>` for SPI-incompatible changes.
- `Co-authored-by: ...` only if there were actual co-authors.

Avoid filler ("improve code quality", "minor cleanup"). If you can't justify the change in one sentence, the commit is probably too big.
