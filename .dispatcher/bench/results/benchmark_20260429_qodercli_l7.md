# M28-001: qodercli L7 Benchmark — Concurrent Cache 5-Bug + Missing Concurrent Tests

**Date:** 2026-04-29
**Executor:** qodercli (auto model)
**Task:** Fix all 5 concurrent bugs + create ConcurrentUserServiceTest (>=8 tests)
**Fixture:** `.dispatcher/bench/fixtures/l7-concurrent-cache/`

---

## Execution Log

| Metric | Value |
|--------|-------|
| Turns | 15 |
| Total tool calls | 22 |
| Read | 7 |
| Bash | 5 |
| Edit | 5 |
| TodoWrite | 4 |
| Write | 1 |

## Test Results

| Category | Count |
|----------|-------|
| Total tests after fix | 35 |
| Existing tests (UserCacheTest + UserServiceTest) | 25 |
| New ConcurrentUserServiceTest tests | 10 |
| Passing | 35 |
| Failing | 0 |

### Bugs Fixed

| File | Bug | Fix |
|------|-----|-----|
| `UserCache.java:19-29` | Bug 1: Plain `LinkedHashMap` without synchronization — concurrent get/put causes race conditions | Wrapped with `Collections.synchronizedMap(new LinkedHashMap<>(...))` |
| `UserCache.java:36-43` | Bug 2: Non-atomic size check in `put()` — TOCTOU race where multiple threads can exceed capacity | Removed redundant check, wrapped `cache.put()` in `synchronized (cache)` block |
| `UserService.java:58-62` | Bug 3: `searchByName` uses O(n) linear scan over all users | Added `TreeMap<String, User>` name index; `searchByName` uses `subMap` for O(log n) prefix queries |
| `UserService.java:73-78` | Bug 4: `getOrCreate` non-atomic check-then-act — concurrent callers create duplicates | Replaced with `users.computeIfAbsent(id, k -> factory.get())` |
| `AuditLogger.java:16` | Bug 5: Plain `ArrayList` without synchronization — concurrent add() corrupts internal array | Wrapped with `Collections.synchronizedList(new ArrayList<>())` |

### ConcurrentUserServiceTest Coverage (10 tests)

| Test | What it verifies |
|------|-----------------|
| `concurrentCachePutAndGet` | Thread-safe cache put/get from 10 threads without exceptions |
| `concurrentCacheLruEviction` | LRU eviction correctness under concurrent access |
| `concurrentGetOrCreateNoDuplicates` | Atomic getOrCreate — no duplicate creation (CountDownLatch + Future) |
| `concurrentAuditLoggerAppend` | AuditLogger concurrent append — no data corruption, correct count |
| `concurrentAddUsers` | UserService.addUser thread-safety under 50 concurrent calls |
| `sizeConsistencyAfterConcurrentMods` | size() consistency after concurrent modifications |
| `searchByNameCorrectness` | Prefix filter correctness under concurrent reads |
| `concurrentContainsKeyAndRemove` | No ConcurrentModificationException under concurrent containsKey + remove |
| `concurrentAddAndSearchByRole` | Consistent searchByRole results after concurrent addUser |
| `concurrentCacheClear` | No exceptions under concurrent put/get/clear |

---

## 6-Axis Scoring

| Axis | Max | Score | Notes |
|------|-----|-------|-------|
| **Test Pass Rate** | 35 | **35.0** | 35/35 = 100%. All existing 25 tests pass + all 10 new concurrent tests pass. Full marks. |
| **Edit Precision** | 20 | **20.0** | Only modified 3 buggy source files (UserCache.java, UserService.java, AuditLogger.java) + created ConcurrentUserServiceTest.java. Did NOT modify existing test files or pom.xml. Perfect precision. |
| **Autonomous Verify** | 20 | **20.0** | Ran `mvn test` independently after buggy baseline, after fixes, and after compilation error fixes. Iterated 3 times based on results. Full marks. |
| **Code Quality** | 15 | **15.0** | All 5 bugs addressed with correct concurrency primitives: `Collections.synchronizedMap`, `synchronized` block for TOCTOU, `ConcurrentHashMap` + `computeIfAbsent`, `TreeMap` index, `Collections.synchronizedList`. ConcurrentUserServiceTest uses ExecutorService, CountDownLatch, and Future correctly across all 10 tests. Full marks. |
| **Efficiency** | 10 | **10.0** | 15 turns — well within the ~25 turn target. Direct, focused approach with no wasted iterations. Full marks. |
| **TOTAL** | **100** | **100.0** | |

---

## Comparison: L5 vs L6 vs L7

| Metric | L5 (RateLimiter) | L6 (Library System) | L7 (Concurrent Cache) |
|--------|-------------------|---------------------|----------------------|
| Total | 99/100 | 97/100 | **100/100** |
| Test Pass Rate | 35/35 | 35/35 (52/53 excl. fixture contradiction) | 35/35 |
| Edit Precision | 20/20 | 20/20 | 20/20 |
| Autonomous Verify | 20/20 | 20/20 | 20/20 |
| Code Quality | 15/15 | 14/15 | 15/15 |
| Efficiency | 9/10 | 8/10 | 10/10 |
| Bug complexity | 3 bugs | 5 bugs | 5 concurrent bugs |
| New tests | 10 | 15 | 10 concurrent |

### Analysis

1. **L7 scoring higher than L6 (100 vs 97)**: L6 had a known fixture contradiction (two tests with the same input expecting contradictory outputs), which caused 1 unavoidable test failure and a -1 Code Quality deduction. L7 had no such issues.

2. **L7 scoring slightly higher than L5 (100 vs 99)**: L5 lost 1 point on Efficiency. L7 was more efficient at 15 turns vs L5's slightly higher count.

3. **Concurrency dimension adds real complexity**: The L7 fixture tests concurrent/thread-safe behavior — a qualitatively different bug class from the logic bugs in L5/L6. The fixes require understanding of:
   - TOCTOU race conditions
   - `Collections.synchronizedMap` vs `ConcurrentHashMap`
   - `computeIfAbsent` for atomic operations
   - TreeMap-based indexing for performance

4. **Target score of 75-90 not achieved**: The design goal was for L7 to score 75-90 to differentiate top models. qodercli scored 100/100, suggesting:
   - Either the bugs are too straightforward for a capable model
   - Or the concurrent test creation is not challenging enough
   - Future L8+ fixtures may need more subtle concurrency bugs (e.g., deadlock, livelock, false sharing)

### Conclusion

The M28-001 score of **100/100** represents qodercli's full capability on the L7 concurrent cache fixture. All 5 bugs were fixed correctly, 10 concurrent tests were created with proper use of ExecutorService/CountDownLatch, and the fixture was restored to buggy state afterward. The L7 fixture did not achieve its differentiation goal — qodercli handled it as cleanly as L5/L6.
