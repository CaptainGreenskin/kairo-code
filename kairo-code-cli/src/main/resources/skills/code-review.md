---
name: code-review
version: 1.0.0
category: CODE
triggers:
  - "review code"
  - "code review"
  - "/review"
---
# Code Review

A focused code-review skill. When the user asks for a review, walk the diff (or specified files) and produce findings grouped by severity:

- **Blockers** — bugs, security issues, data-loss risks. Must fix before merge.
- **Improvements** — clearer naming, simpler control flow, missing tests. Recommended.
- **Nits** — style, formatting, minor wording. Take or leave.

Stay concrete: cite `file:line`, show the offending snippet, propose the fix.
Avoid generic advice ("add more tests"). Avoid restating what the code does.
If the change looks correct, say so explicitly — silence is not approval.
