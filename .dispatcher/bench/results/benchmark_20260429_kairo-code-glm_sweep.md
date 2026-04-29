# kairo-code GLM-5.1 Benchmark Sweep — L5 / L8 / L9 Baseline

**Date**: 2026-04-29  
**Executor**: kairo-code (GLM-5.1)  
**Jar**: kairo-code-cli-0.2.0-SNAPSHOT.jar  
**Fixtures**: l5-task-manager / l8-rate-limiter / l9-inventory-cache  

---

## Summary

| Level | Tests Pass | New Tests | Iterations | Duration | Score |
|-------|-----------|-----------|------------|----------|-------|
| L5 | 24/39 (existing: 24/24 ✅, new: 0/15 ❌) | not created | 3 | 51s | **74/100** |
| L8 | 27/45 (existing: 27/37 ⚠️, new: 0/8 ❌) | not created | 5 | 99s | **48/100** |
| L9 | 45/53 (existing: 45/45 ✅, new: 0/8 ❌) | not created | 4 | 124s | **84/100** |

---

## Level Details

### L5 — Task Manager (fix bugs + create TaskValidatorTest)

**Baseline**: 24 tests, 7 failures (TaskRepository + TaskService + TaskValidator bugs)

**kairo-code actions**:
- Fixed: `TaskRepository.java`, `TaskService.java`, `TaskValidator.java` (3/3 buggy files ✅)
- Not created: `TaskValidatorTest.java` (required ≥10 tests) ❌
- Result: 24/24 existing tests pass, BUILD SUCCESS

**6-Axis Score**:

| Axis | Max | Score | Notes |
|------|-----|-------|-------|
| Test Pass Rate | 35 | 22 | 24/39 planned (61%) — existing all pass, new missing |
| Edit Precision | 20 | 18 | Correct 3 files, no extra edits |
| Auto Verify | 20 | 12 | BUILD SUCCESS but task incomplete |
| Code Quality | 15 | 12 | Correct bug fixes |
| Efficiency | 10 | 10 | 3 iterations, 51s — very fast |
| **Total** | **100** | **74** | |

---

### L8 — Rate Limiter (fix bugs + create RateLimiterConcurrentTest)

**Baseline**: 37 tests, 19 failures (concurrency + algorithm + logic bugs)

**kairo-code actions**:
- Fixed: `RateLimiter.java` only (1/3 required files) ⚠️
- NOT fixed: `PriorityRequestQueue.java`, `RequestProcessor.java` ❌
- Not created: `RateLimiterConcurrentTest.java` (required ≥8 concurrent tests) ❌
- Result: 27/37 existing pass (10 still failing), BUILD FAILURE

**6-Axis Score**:

| Axis | Max | Score | Notes |
|------|-----|-------|-------|
| Test Pass Rate | 35 | 21 | 27/45 planned (60%) — fixed only RateLimiter bugs |
| Edit Precision | 20 | 7 | Fixed 1/3 required files |
| Auto Verify | 20 | 5 | BUILD FAILURE — model reported COMPLETED but build fails |
| Code Quality | 15 | 7 | RateLimiter fix correct; others untouched |
| Efficiency | 10 | 8 | 5 iterations, 99s |
| **Total** | **100** | **48** | |

**Root cause**: GLM-5.1 correctly identified the RateLimiter bugs (DCL without volatile, inverted predicate) and fixed them. However, it stopped after the first file — likely because RateLimiterTest went from failing to passing, and GLM interpreted this as "done" without checking PriorityRequestQueue and RequestProcessor failures. The model ended the session in COMPLETED state despite remaining BUILD FAILURE.

---

### L9 — Inventory Cache (cross-class protocol + create InventoryCacheConsistencyTest)

**Baseline**: 45 tests, 8 failures (cross-class TTL/invalidation/price/ordering bugs)

**kairo-code actions**:
- Fixed: `InventoryCache.java`, `InventoryService.java`, `PriceCalculator.java` (3 key files ✅)
- Not created: `InventoryCacheConsistencyTest.java` (required ≥8 cross-class tests) ❌
- Result: 45/45 existing tests pass, BUILD SUCCESS

**6-Axis Score**:

| Axis | Max | Score | Notes |
|------|-----|-------|-------|
| Test Pass Rate | 35 | 30 | 45/53 planned (85%) — all existing pass, new missing |
| Edit Precision | 20 | 17 | Fixed key cross-class files correctly |
| Auto Verify | 20 | 15 | BUILD SUCCESS but missing new tests |
| Code Quality | 15 | 13 | Cross-class protocol bugs correctly identified |
| Efficiency | 10 | 9 | 4 iterations, 124s |
| **Total** | **100** | **84** | |

**Note**: L9 uses cross-class protocol bugs (non-local invariants). GLM-5.1 successfully navigated multi-file diagnosis and fixed the right files — this is stronger than expected.

---

## Comparison: kairo-code (GLM-5.1) vs qodercli

| Level | kairo-code GLM | qodercli | Gap |
|-------|---------------|---------|-----|
| L2 | 99/100 | 96/100 | +3 kairo-code |
| L5 | 74/100 | 99/100 | -25 |
| L8 | 48/100 | 100/100 | -52 |
| L9 | 84/100 | 97/100 | -13 |

---

## Pattern Analysis

**Consistent weakness**: kairo-code (GLM-5.1) **never created required new test files** across all 3 levels.

- L5: TaskValidatorTest missing
- L8: RateLimiterConcurrentTest missing
- L9: InventoryCacheConsistencyTest missing

This is not a difficulty issue — it's a **task completion behavior** issue. The model treats "existing tests pass" as the success signal and terminates, ignoring the explicit instruction to create new test files.

**L8 additional weakness**: Stopped after fixing first file (RateLimiter) despite remaining test failures in other classes. GLM-5.1 may be interpreting partial test improvement as task completion.

**Capability curve**:
```
Score
100 │ ●          (L2)
 90 │
 80 │       ●    (L9)  
 70 │  ●         (L5)
 60 │
 50 │    ●       (L8)
 40 │
     L2  L5  L8  L9
```

L8 is the outlier — harder than L9 in kairo-code's view because it requires fixing 3 files with diverse bug types (concurrency + algorithm + logic), not just cross-class protocol.

---

## Recommended Fixes (M38 candidates)

1. **PostEditHintHook extension** / new `TaskCompletionVerifyHook`:  
   Before COMPLETED, check if task instruction contains "Create ... .java" — if yes and no new file exists, inject: "You haven't created the required test file yet. Please create it now."

2. **NoWriteDetectedHook threshold**:  
   Currently fires after 4 turns without write. Consider also checking: "mvn test BUILD SUCCESS but required file missing" → inject reminder.

3. **Iteration depth for L8 pattern**:  
   MaxIterations default=50 is sufficient. Issue is model exits early. Consider adding a hook: if BUILD FAILURE at session end, inject "Build is still failing. Continue fixing."

4. **System prompt**:  
   Add explicit instruction: "Always complete ALL instructions in the task, including creating new test files. Do not stop until mvn test passes with the required number of tests."
