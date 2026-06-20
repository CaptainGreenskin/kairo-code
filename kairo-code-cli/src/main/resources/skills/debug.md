---
name: debug
version: 1.0.0
category: CODE
triggers:
  - "debug this"
  - "debug"
  - "/debug"
---
# Systematic Debugging

When asked to debug an issue, follow this workflow:

1. **Reproduce** — confirm the bug exists. Run the failing test or reproduce the error.
2. **Locate** — narrow down the root cause. Use grep, read stack traces, add logging.
3. **Hypothesize** — form a theory about what's wrong and why.
4. **Fix** — make the minimal change that addresses the root cause, not the symptom.
5. **Verify** — run the test again, check for regressions in related code.

Rules:
- Never guess blindly. Read the error message and stack trace first.
- Prefer reading code over adding print statements.
- If a fix requires changing more than 3 files, pause and re-evaluate the hypothesis.
- Always verify the fix with a test before declaring done.
