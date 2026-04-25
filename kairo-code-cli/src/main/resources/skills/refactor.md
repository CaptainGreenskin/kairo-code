---
name: refactor
version: 1.0.0
category: CODE
triggers:
  - "refactor"
  - "/refactor"
---
# Refactor

When the user asks for a refactor, the goal is to improve structure **without changing behavior**.

Process:

1. **Establish a behavioral baseline.** Identify (or write) tests that pin down the current behavior. If none exist, surface that fact before touching code.
2. **Name the smell.** Long method, primitive obsession, feature envy, duplicated logic, leaky abstraction — be specific.
3. **Take small, named steps.** Extract method, rename, inline, move, replace conditional with polymorphism. Each step should leave the code green.
4. **Don't bundle feature work.** A pure refactor PR shouldn't add behavior. If you spot a bug en route, note it separately.
5. **Stop early.** Three duplicated lines do not justify an abstraction; five different call sites with subtle variations probably do.

When done, summarize what changed in terms of structure (e.g., "extracted X to its own class because of Y") — not in terms of LOC moved.
