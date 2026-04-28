# QoderCLI L5 Benchmark Result

**Date**: 2026-04-28
**Fixture**: l5-task-manager (5 bugs + missing TaskValidatorTest.java)
**Task**: Fix all bugs + create TaskValidatorTest.java (≥15 tests) → all 39 tests must pass

---

## 1. Execution Summary

- **Mode**: `qodercli -p` (print mode, non-interactive)
- **Permission**: `--permission-mode bypass_permissions`
- **Tool calls**: Edit (3 files) + Write (1 file) — single pass, no iteration needed
- **Execution turns**: ~3 turns (read buggy code → fix + create → verify)

### Execution log excerpt

```
All done. 40 tests pass (10 + 14 + 16), BUILD SUCCESS.
```

---

## 2. Test Results

| Suite | Tests | Status |
|-------|-------|--------|
| TaskRepositoryTest | 10 | PASS |
| TaskServiceTest | 14 | PASS |
| TaskValidatorTest (created) | 16 | PASS |
| **Total** | **40** | **BUILD SUCCESS** |

TaskValidatorTest covers:
- Valid task acceptance (1 test)
- Null task rejection (1 test)
- Null/blank/empty title rejection (3 tests)
- Null due date acceptance (1 test)
- Future due date acceptance (1 test)
- Past due date rejection (1 test)
- Today due date acceptance (1 test)
- isValidPriority true/false (2 tests)
- isValidDueDate true/false for various dates (4 tests)
- All priority levels acceptance (1 test)

---

## 3. Bugs Fixed

| # | File | Bug | Fix |
|---|------|-----|-----|
| 1 | TaskRepository:33 | `findByStatus` compared enum to String `.name()` (always false) | `status.equals(task.getStatus())` |
| 2 | TaskRepository:40 | `findAllSortedByPriority` sorted ascending (LOW first) | Added `.reversed()` for descending |
| 3 | TaskValidator:14 | `validate` missed null titles (only checked blank) | `title == null || title.isBlank()` |
| 4 | TaskService:28-32 | `completeTask` didn't reject already-completed tasks | Added check + `IllegalStateException` |
| 5 | TaskService:47 | `getOverdueTasks` used `isAfter` instead of `isBefore` | Changed to `isBefore` |

---

## 4. 6-Axis Scoring

| Dimension | Max | Score | Rationale |
|-----------|-----|-------|-----------|
| **Test Pass Rate** | 35 | **35** | 40/39 tests pass (exceeds minimum, 16 new tests created) |
| **Edit Precision** | 20 | **20** | Only 3 buggy source files modified; TaskRepositoryTest, TaskServiceTest, pom.xml untouched |
| **Autonomous Verify** | 20 | **20** | Ran mvn test, confirmed BUILD SUCCESS with 40 tests |
| **Code Quality** | 15 | **15** | All fixes correct and minimal; TaskValidatorTest covers all required scenarios + edge cases |
| **Efficiency** | 10 | **9** | Single-pass execution in print mode; no wasted iterations. ~3 turns (within 25-turn budget) |
| **Total** | **100** | **99** | |

---

## 5. Comparison with L4 (96/100)

| Metric | L4 (qodercli) | L5 (qodercli) | Delta |
|--------|---------------|---------------|-------|
| Total score | 96 | 99 | +3 |
| Tests | 33 pass (bug fix only) | 40 pass (bug fix + new tests) | +7 tests |
| Bugs fixed | 5 | 5 | same |
| New test files | 0 | 1 (TaskValidatorTest) | +1 |
| New test coverage | 0 | 16 tests | +16 |
| Edit precision | perfect | perfect | same |
| Autonomous verify | yes | yes | same |

### Analysis

Adding the "create missing tests" dimension **did not reduce** qodercli's score — in fact, it slightly improved it (96→99). Key observations:

1. **Bug fix quality unchanged**: All 5 bugs fixed correctly in both L4 and L5, with identical fixes.
2. **Test creation competence**: qodercli produced 16 high-quality tests for TaskValidatorTest (exceeding the ≥15 requirement), covering all specified scenarios plus edge cases (today's date, all priority levels).
3. **No regression**: Existing test files (TaskRepositoryTest, TaskServiceTest) were not modified, maintaining edit precision.
4. **Efficiency**: Print mode executed in a single pass without iteration, showing that the agent understood both the bug fixes and test requirements simultaneously.

The L5 benchmark proves qodercli can handle **multi-dimensional tasks** (debug + test authoring) without sacrificing quality on either dimension.
