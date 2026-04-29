# kairo-code GLM-5.1 Benchmark — M40 L8 Rerun

**Date**: 2026-04-29  
**Executor**: kairo-code (GLM-5.1)  
**Changes since M39**:
- `a2be91c` — system-prompt: "Fix failures before new files" priority rule  
- `26f4133` — **BUG FIX**: PostBatchEditVerifyHook/PlanWithoutActionHook — correct tool names (write_file→write, edit_file→edit)

---

## M37 / M38 / M39 / M40 Comparison (L8)

| Milestone | Score | Tests Pass | Compile Error | Hook fix notes |
|-----------|-------|-----------|---------------|----------------|
| M37 | 48/100 | 27/37 | no | agent exited early (partial fix) |
| M38 | 0/100 | N/A | **yes** | PRE_COMPLETE added; HashMap import missing |
| M39 | ~20/100 | 18/37 (baseline) | no | CompileErrorFeedbackHook added; timeout |
| M40 attempt 1 | 0/100 | N/A | **yes** | fix-first prompt + PostBatchEditVerifyHook fix |
| M40 attempt 2 | 0/100 | N/A | **yes** | same root cause |

---

## Root Cause Analysis

**GLM-5.1 L8 deterministic failure pattern**:

Every run, the agent produces this exact error in `RateLimiter.java`:
```java
import java.util.concurrent.ConcurrentHashMap;  // ← added (correct intent)

// ← field type NOT changed:
private HashMap<String, WindowData> clientWindows = new HashMap<>();
```

The agent understands the bug (the original comment says "should be volatile ConcurrentHashMap") but fails to replace the `HashMap` references in the field declaration, method signatures, and local variables. It only adds the `ConcurrentHashMap` import without making the corresponding type substitutions.

This is a **model capability issue**, not a framework/hook issue:
- `CompileErrorFeedbackHook` fires correctly when the agent runs `mvn test`
- `PostBatchEditVerifyHook` now fires with correct tool names (`write`/`edit`)
- But the agent cannot reliably fix the HashMap→ConcurrentHashMap substitution even with repeated prompting

---

## Framework Bug Found and Fixed (M40 side-effect)

During M40 investigation, discovered a **long-standing silent bug**:

`PostBatchEditVerifyHook` and `PlanWithoutActionHook` used wrong tool names:
- Old (broken): `"write_file"`, `"edit_file"`, `"read_file"`, `"search_files"`
- Actual kairo-code tools: `"write"`, `"edit"`, `"read"`, `"grep"`

**Impact**: `PostBatchEditVerifyHook` has never fired since it was introduced (M13). The hook correctly detected `"bash"` tool but never detected file edits — it always saw `turnsSinceEdit = 0`.

Fixed in commit `26f4133`. This means `PostBatchEditVerifyHook` is NOW working for the first time.

---

## Conclusion

L8 is beyond GLM-5.1's reliable capability for the following reasons:
1. 3 files with diverse bug types (concurrency, heap algorithm, sliding window logic)
2. Concurrent test creation requirement (CountDownLatch/ExecutorService)
3. The DCL+HashMap→ConcurrentHashMap fix requires simultaneous type substitution across field declaration, method return types, and local variables — GLM-5.1 consistently does this partially

**Recommended action**: Accept L8 as GLM-5.1's "hard wall" level. Focus future milestones on:
1. L10 benchmark for GLM-5.1 (parallel/concurrent challenges at the design level, not bug-fix)
2. Kairo framework improvements
3. Better L8 measurement methodology (separate bug-fix score from test-creation score)

---

## Framework Improvements from M40

| Fix | Commit | Impact |
|-----|--------|--------|
| system-prompt "fix-first" rule | `a2be91c` | L5/L9 benefit confirmed; L8 GLM-5.1 can't apply |
| PostBatchEditVerifyHook tool names | `26f4133` | Hook now actually fires (was silently broken) |
| PlanWithoutActionHook tool names | `26f4133` | Hook now actually fires |
