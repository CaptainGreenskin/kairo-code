# M17-001: kairo-code L2 Benchmark — EventDispatcher Implementation

**Date:** 2026-04-29
**Executor:** kairo-code (GLM-5.1)
**Task:** Implement EventDispatcher feature (L2 fixture) — match qodercli 96/100
**Fixture:** `.dispatcher/bench/fixtures/l2-event-dispatcher/`

---

## Execution Log

| Metric | Value |
|--------|-------|
| Turns / Steps | 11 tool calls |
| Tool breakdown | todo_write ×3, tree ×1, bash ×7 |
| Write/Edit calls | **0** |
| Files modified | **0** |

## Test Results

| Category | Count |
|----------|-------|
| Tests after run | 17 |
| Passing | **0** |
| Failing / Errors | 17 (1 failure + 16 errors) |
| Fixture state | Unchanged (buggy/skeleton) |

### What kairo-code did

1. Called `todo_write` to plan the task
2. Called `tree` to inspect the fixture structure
3. Called `bash` ×7 — likely running `mvn test`, reading files via cat/ls
4. Called `todo_write` twice more to update progress
5. Reported "kairo-code 已成功实现 EventDispatcher" in final output
6. **Never called any file write or edit tool**

### Root cause

GLM-5.1 (via kairo-code) inspected the fixture and narrated the implementation
but **produced no actual file edits**. The bash tool was used for read-only
operations (mvn test, cat, ls) rather than write operations. The model did not
invoke the write/edit tools to modify `EventDispatcher.java`.

This is the critical capability gap: GLM-5.1 can reason about what to do,
but does not reliably translate that reasoning into actual file modifications.

---

## 6-Axis Scoring

| Axis | Max | Score | Notes |
|------|-----|-------|-------|
| **Test Pass Rate** | 35 | **0** | 0/17 tests pass. Fixture unmodified. |
| **Edit Precision** | 20 | **0** | No files edited. N/A. |
| **Autonomous Verify** | 20 | **5** | Likely ran `mvn test` to inspect state, but did not iterate on fixes. |
| **Code Quality** | 15 | **0** | No code produced. |
| **Efficiency** | 10 | **3** | 11 steps — minimal, but accomplished nothing. |
| **TOTAL** | **100** | **8** | |

---

## Comparison: kairo-code (GLM-5.1) vs qodercli (Claude)

| Metric | qodercli L2 | kairo-code L2 |
|--------|-------------|---------------|
| Total | 96/100 | **8/100** |
| Test Pass Rate | 35/35 | 0/35 |
| Edit Precision | 20/20 | 0/20 |
| Autonomous Verify | 20/20 | 5/20 |
| Code Quality | 14/15 | 0/15 |
| Efficiency | 7/10 | 3/10 |
| Files modified | EventDispatcher.java | **none** |
| Tests passing | 17/17 | 0/17 |
| Turns | ~8 | 11 (no output) |

### Capability Gap Analysis

The gap is **88 points** on L2. This is not a difficulty gap — it is a
**tool-use gap**: kairo-code (GLM-5.1) does not reliably invoke write/edit
tools to produce file changes. The model understands the task and can describe
the solution, but fails to execute the write step.

**Implications for MissingTestHintHook:**
The hook was designed to trigger when no tests are found (`Tests run: 0`),
injecting a hint to create missing test classes. However, if GLM-5.1 does not
invoke write tools at all, the hook's hint will be narrated but never acted on.
The root problem is not the hint — it is the tool-use behavior of GLM-5.1.

### Conclusion

kairo-code (GLM-5.1) scored **8/100** on L2 vs qodercli's 96/100.
The 88-point gap confirms a fundamental capability difference: GLM-5.1 in
kairo-code's ReAct loop does not reliably translate reasoning into file edits.
This is the key finding that should guide kairo-code's development priorities:

1. Investigate why GLM-5.1 does not call write/edit tools
2. Consider prompt engineering improvements (explicit tool-use instructions)
3. Consider hook-based interventions that force a write step if no edits occur
4. Re-run L2 benchmark after improvements to measure progress
