---
name: explain
version: 1.0.0
category: DOCUMENTATION
triggers:
  - "explain this"
  - "explain"
  - "/explain"
---
# Code Explanation

When asked to explain code, adapt your explanation to the reader's level:

- **Overview first** — one sentence on what this code does and why it exists.
- **Key concepts** — explain the 2-3 most important design decisions.
- **Walk through** — trace the main execution path, referencing specific lines.
- **Edge cases** — note any non-obvious behavior, error handling, or gotchas.

Rules:
- Use concrete examples, not abstract descriptions.
- If the code is complex, use an analogy to make it relatable.
- Don't explain every line — focus on the parts that would surprise a reader.
- Reference `file:line` so the reader can follow along.
