# M30-001: qodercli L8 Benchmark — Rate Limiter DCL + Algorithm Correctness

**Date:** 2026-04-29
**Executor:** qodercli (auto model)
**Task:** Fix all 5 bugs (DCL/volatile, sliding window, heap sift-up, double-counting, order) + create RateLimiterConcurrentTest (>=8 tests)
**Fixture:** `.dispatcher/bench/fixtures/l8-rate-limiter/`

---

## Execution Log

| Metric | Value |
|--------|-------|
| Turns | 10 |
| Total tool calls | 18 |
| Read | 7 |
| Bash | 4 |
| Edit | 6 |
| TodoWrite | 4 |
| Write | 1 |

## Test Results

| Category | Count |
|----------|-------|
| Total tests after fix | 45 |
| Existing tests (RateLimiterTest + PriorityRequestQueueTest + RequestProcessorTest) | 37 |
| New RateLimiterConcurrentTest tests | 8 |
| Passing | 45 |
| Failing | 0 |

### Bugs Fixed

| File | Bug | Fix |
|------|-----|-----|
| `RateLimiter.java:22` | Bug 1: `clientWindows` uses non-volatile `HashMap` — DCL unsafe publication | Changed to `volatile ConcurrentHashMap<String, WindowData>` |
| `RateLimiter.java:78` | Bug 2: `removeIf(t -> t >= windowStart)` removes timestamps INSIDE window (inverted logic) | Changed to `removeIf(t -> t < windowStart)` — removes expired timestamps |
| `PriorityRequestQueue.java:62` | Bug 3: sift-up uses `>` comparison — MAX-HEAP becomes MIN-HEAP (LOW at root instead of HIGH) | Changed to `<` so higher ordinal (HIGH=2) rises to root |
| `RequestProcessor.java:58-63` | Bug 4: `successCount` incremented in both try block AND finally block — double-counting | Removed the duplicate increment from the finally block |
| `RequestProcessor.java:39-40` | Bug 5: `recordRequest` called before `isAllowed` — request consumes quota before checking | Check limit first via `getWindowedCount < maxRequestsPerWindow`, then record only if allowed |

### RateLimiterConcurrentTest Coverage (8 tests)

| Test | What it verifies |
|------|-----------------|
| `concurrentRequestsFromMultipleClientsShouldNotThrow` | Thread-safe recordRequest from 10 clients, no exceptions (ExecutorService + CountDownLatch) |
| `concurrentAllowAndRecordShouldEnforceLimit` | Rate limit enforcement under 20 concurrent threads sharing one client |
| `concurrentWindowedCountShouldBeAccurate` | Sliding window count accuracy after 50 concurrent records (CountDownLatch barrier) |
| `dclInitializationShouldBeThreadSafe` | DCL lazy init thread-safety — 20 threads trigger simultaneous initialization |
| `concurrentDifferentClientsShouldNotInterfere` | Client isolation — 10 independent clients each get correct count |
| `concurrentRecordAndCheckShouldNotDeadlock` | No deadlock under 10 threads doing mixed record/isAllowed/getWindowedCount |
| `concurrentMixedOperationsShouldMaintainConsistency` | Consistency under mixed allowAndRecord + read operations from 8 threads |
| `concurrentHighContentionShouldRespectRateLimit` | Exact rate limit (5) respected under 50-thread high contention |

---

## 6-Axis Scoring

| Axis | Max | Score | Notes |
|------|-----|-------|-------|
| **Test Pass Rate** | 35 | **35.0** | 45/45 = 100%. All 37 existing tests pass + all 8 new concurrent tests pass. Full marks. |
| **Edit Precision** | 20 | **20.0** | Only modified 3 buggy source files (RateLimiter.java, PriorityRequestQueue.java, RequestProcessor.java) + created RateLimiterConcurrentTest.java. Did NOT modify any existing test files or pom.xml. Perfect precision. |
| **Autonomous Verify** | 20 | **20.0** | Ran `mvn test` to confirm buggy baseline (19 failures), then after initial fixes (4 failures in RequestProcessorTest), iterated on Bug 5 fix to account for `isAllowed` <= semantics, then final run passed all 45. Full marks. |
| **Code Quality** | 15 | **15.0** | All 5 bugs addressed correctly: `volatile ConcurrentHashMap` for DCL, inverted `removeIf` predicate fixed, sift-up `<` for MAX-HEAP, single `successCount` increment, check-before-record with correct `< maxRequestsPerWindow` comparison. RateLimiterConcurrentTest uses ExecutorService, CountDownLatch, and AtomicInteger correctly. Full marks. |
| **Efficiency** | 10 | **10.0** | 10 turns — well within the ~25 turn target. Direct approach with one iteration on Bug 5 fix. Full marks. |
| **TOTAL** | **100** | **100.0** | |

---

## Comparison: L7 vs L8

| Metric | L7 (Concurrent Cache) | L8 (Rate Limiter DCL+Algorithm) |
|--------|----------------------|--------------------------------|
| Total | 100/100 | **100/100** |
| Test Pass Rate | 35/35 | 45/45 |
| Edit Precision | 20/20 | 20/20 |
| Autonomous Verify | 20/20 | 20/20 |
| Code Quality | 15/15 | 15/15 |
| Efficiency | 10/10 | 10/10 |
| Bug count | 5 | 5 |
| Bug types | All concurrency | 1 concurrency (DCL) + 4 algorithm/logic |
| New tests | 10 concurrent | 8 concurrent |
| Total tests | 35 | 45 |
| Turns | 15 | 10 |

### Analysis

1. **L8 scoring 100/100 — same as L7**: qodercli handled the L8 fixture with equal or better efficiency than L7 (10 turns vs 15), despite L8 introducing two qualitatively different bug dimensions:
   - **DCL/volatile semantics** (Bug 1): Requires understanding of the Java Memory Model, safe publication, and why `volatile` is necessary for double-checked locking.
   - **Algorithm correctness** (Bugs 2-5): Inverted predicate, heap comparison direction, double-counting, ordering — these test logical reasoning about code behavior.

2. **The Bug 5 iteration demonstrates real reasoning**: The initial fix (check `isAllowed` first, then record) caused 4 test failures because `isAllowed` uses `<=` comparison. qodercli correctly diagnosed that the check must account for the pending request (`getWindowedCount < maxRequestsPerWindow`), showing understanding of the interaction between the rate limiter's API contract and the processor's logic.

3. **Target score of 80-90 not achieved**: The design goal was for L8 to score 80-90 to differentiate top models. qodercli scored 100/100 again, suggesting:
   - The 5 bugs, while covering DCL and algorithm domains, are individually straightforward once identified.
   - The concurrent test creation (8 tests) is well within qodercli's capability with ExecutorService/CountDownLatch patterns.
   - Future L9+ fixtures may need more subtle issues: deadlock potential, reentrancy problems, memory leaks in caches, or complex interaction bugs that require deeper architectural understanding.

4. **L8 did add meaningful complexity**: 45 tests (vs 35 in L7), mixed bug types (concurrency + algorithm), and the Bug 5 semantic subtlety. But qodercli's performance was not degraded — it was actually more efficient (10 vs 15 turns).

### Conclusion

The M30-001 score of **100/100** shows qodercli's full capability extends through the L8 difficulty level. All 5 bugs (DCL/volatile, sliding window, MAX-HEAP, double-counting, order) were fixed correctly, 8 concurrent tests were created with proper synchronization primitives, and the fixture was restored to buggy state. The L8 fixture did not achieve its differentiation goal of 80-90 — qodercli handled it as cleanly as L5-L7.
