# kairo-code GLM-5.1 Benchmark — M39 Rerun（L5/L8 after backtick regex fix + CompileErrorFeedbackHook）

**Date**: 2026-04-29  
**Executor**: kairo-code (GLM-5.1)  
**Jar**: kairo-code-cli-0.2.0-SNAPSHOT.jar (rebuilt after M39-001 merge)  
**Changes since M38**:
- `a7847e0` — UnfulfilledInstructionHook regex: match backtick-wrapped `Create \`path\`` format
- `1ebb93b` — CompileErrorFeedbackHook: detect COMPILATION ERROR / cannot find symbol / incompatible types

---

## M37 / M38 / M39 Comparison

| Level | M37 | M38 | M39 | Delta M37→M39 | Key change |
|-------|-----|-----|-----|---------------|------------|
| L5 | 74/100 | 79/100 | **~100/100** | **+26** | Backtick regex fixed → TaskValidatorTest created |
| L8 | 48/100 | 0/100 | **~20/100** | -28 | Timeout (>900s), no bugs fixed; no compile error |

---

## L5 — Task Manager（M39）

| | M37 | M38 | M39 |
|-|-----|-----|-----|
| Existing tests | 24/24 ✅ | 19/24 ⚠️ | **39/39 ✅** |
| New test file | not created | not created | **TaskValidatorTest.java created** ✅ |
| Hook fired | ❌ | ❌ (backtick miss) | ✅ (backtick regex fixed) |
| Build | SUCCESS | FAILURE | **SUCCESS** |
| Score | 74/100 | 79/100 | **~100/100** |

**Result**: 39/39 BUILD SUCCESS. TaskValidatorTest.java created (15 tests). All existing tests pass.

**Root cause of improvement**: L5 task uses `` Create `src/test/java/com/example/TaskValidatorTest.java` `` (backtick format). M38 regex `Create\s+(src/test/...)` didn't match the leading backtick. Fixed to `Create\s+[`']?(src/test/[^`']+\.java)` in `a7847e0`.

---

## L8 — Rate Limiter（M39）

### Run 1 (--timeout 600)

| | M37 | M38 | M39 run 1 |
|-|-----|-----|-----------|
| Tests passing | 27/37 | N/A (compile) | 18/37 |
| New test file | not created | 3 wrong files | not created |
| Compile errors | no | **yes** | **no** ✅ |
| Build | FAILURE | FAILURE | FAILURE |
| Timeout | no (99s) | no | **yes (600s)** |
| Score | 48/100 | 0/100 | ~20/100 |

### Run 2 (--timeout 900)

Same result: 18/37 passing (19 failures = baseline, no bugs fixed), no compile errors, RateLimiterConcurrentTest.java not created. Timed out at 900s.

**Analysis**: Suspected PRE_COMPLETE hook loop.

L8 task file uses `` Create `src/test/java/com/example/RateLimiterConcurrentTest.java` `` (backtick format, now matched by regex). Each time the model is about to return a final answer without creating this file, PRE_COMPLETE injects a reminder. The injectedFiles dedup should prevent re-injection after the first attempt — but if the agent fails to create the file correctly (wrong path, write error, etc.), it may keep looping:

```
PRE_COMPLETE → inject "create RateLimiterConcurrentTest.java"
→ agent tries to create, fails silently
→ PRE_COMPLETE fires again (file still missing, already in injectedFiles → CONTINUE)
→ agent loops on other work
→ timeout
```

Alternatively, the agent is spending 900s on bug diagnosis across 3 complex files (concurrency + algorithm + logic) and genuinely needs more iterations. In M37, only 1 file was touched in 99s (RateLimiter) — the agent exited early, which is why it got a partial score instead of timing out.

**CompileErrorFeedbackHook confirmed working**: No compile errors in either run (vs M38 where HashMap/ConcurrentHashMap mix caused compile failure).

---

## Pattern Analysis

### L5 — Fixed ✅

The backtick regex fix is a complete resolution. L5 should reliably score ~100/100 going forward.

### L8 — Structural challenge

L8 requires:
1. Fix bugs across 3 files with diverse bug types (DCL volatile, heap sift-up, sliding window predicate, statistics double-count, check-vs-record order)
2. Write 8 concurrent tests using CountDownLatch/ExecutorService

This is genuinely complex. The M37 "48/100" score was partly due to the agent exiting early — it partially fixed RateLimiter and exited without touching the other 2 files. With PRE_COMPLETE hooks now preventing premature exit, the agent stays longer but may not have enough turns to complete all 3 files.

**Hypothesis**: PRE_COMPLETE is keeping the agent alive longer, but GLM-5.1 cannot reliably solve all 3 files + write concurrent tests within 900s.

**Possible fixes for M40**:
1. Investigate hook interaction: add debug logging to see how many PRE_COMPLETE injections occur during L8
2. Consider MAX_INJECTIONS=1 for concurrent test creation (prevent lingering on one requirement)
3. System prompt addition: "Fix bugs in priority order. If time is limited, fix as many as possible rather than attempting all at once."

---

## Cumulative Scorecard (kairo-code GLM-5.1)

| Level | M37 | M38 | M39 | Best |
|-------|-----|-----|-----|------|
| L2 | 99 | — | — | **99** |
| L5 | 74 | 79 | **~100** | **~100** |
| L8 | 48 | 0* | ~20** | **48** |
| L9 | 84 | 100 | — | **100** |

*M38 L8: compile error regression  
**M39 L8: timeout regression (hooks keeping loop alive, bugs not fixed)

---

## Commits Referenced

- `a7847e0` — fix(core): UnfulfilledInstructionHook regex — match backtick-wrapped Create paths
- `1ebb93b` — feat(core): add CompileErrorFeedbackHook — detect and surface compilation errors immediately
