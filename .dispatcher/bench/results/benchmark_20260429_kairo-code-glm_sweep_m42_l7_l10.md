# kairo-code GLM-5.1 Benchmark — M42（L7/L10 1200s 重跑）

**Date**: 2026-04-29  
**Executor**: kairo-code (GLM-5.1)  
**Jar**: kairo-code-cli-0.2.0-SNAPSHOT.jar (M41 之后构建，PostBatchEditVerifyHook 修复已生效)  
**Changes vs M41**: timeout 900s → 1200s; all M40 hooks active (PostBatchEditVerifyHook NOW fires)

---

## M42 Results

| Level | Tests Pass | New Test File | Build | Timeout | Score |
|-------|-----------|---------------|-------|---------|-------|
| L7 | 25/25 existing ✅ | not created ❌ | SUCCESS | **yes (1200s)** | **~35/100** |
| L10 | 41/41 existing ✅ | not created ❌ | SUCCESS | **yes (1200s)** | **~38/100** |

> **REGRESSION from M41**: L7 M41 scored ~72 (created test file, 900s). M42 1200s is *worse*.

---

## Root Cause: MaxTurnsGuardHook × PostBatchEditVerifyHook Interaction

### M41 (900s, PostBatchEditVerifyHook broken → not firing):
- Agent edits 3 files without `mvn test` between edits
- ~8-10 turns total → PRE_COMPLETE fires → test file created ✅

### M42 (1200s, PostBatchEditVerifyHook fixed → NOW firing):
- Agent edits file → PostBatchEditVerifyHook injects "run mvn test" reminder → agent runs mvn test → +2 turns per file
- 3 files × ~5 turns each = ~15 turns just for bug fixes
- MaxTurnsGuardHook warnAt=15 fires at turn 15 → "Start wrapping up"
- MaxTurnsGuardHook forceAt=20 fires at turn 20 → "STOP. Commit now"
- Agent commits bug fixes but never reaches ConcurrentUserServiceTest.java creation
- PRE_COMPLETE never fires (agent forced to commit early)

### Turn budget arithmetic:
- L7: 3 files × ~5 turns = 15 turns for bug fixes + 5 turns for test file = 20 total
  → MaxTurnsGuardHook force fires at turn 20 — cuts off test file creation
- L10: 5 files × ~5 turns = 25 turns for bug fixes alone → exceeds forceAt=20 by 5 turns

### Why timeout still hit despite MaxTurnsGuardHook forcing commit:
- Agent complies with "STOP. Commit" but then the outer 1200s wall also hits
- The turn-20 force doesn't guarantee fast completion — agent may still take many more seconds

---

## L7 M41 vs M42 Comparison

| Metric | M41 (900s) | M42 (1200s) |
|--------|-----------|-------------|
| Existing tests | 25/25 ✅ | 25/25 ✅ |
| New test file | **created (10 tests, 1 error)** | **NOT created** |
| PostBatchEditVerifyHook | broken (never fires) | working (fires ~3x) |
| MaxTurnsGuardHook | warnAt=15, forceAt=20 | warnAt=15, forceAt=20 (same) |
| Score | ~72 | ~35 |

**Conclusion**: Fixing PostBatchEditVerifyHook introduced a turn-budget regression. The hook is correct behavior but the thresholds need adjustment.

---

## L10 M41 vs M42 Comparison

| Metric | M41 (900s) | M42 (1200s) |
|--------|-----------|-------------|
| Existing tests | 41/41 ✅ | 41/41 ✅ |
| New test file | NOT created | NOT created |
| Bug fix quality | good (no regressions) | good (no regressions) |
| Score | ~68 | ~38 |

Both timed out, but M42 got a lower score because:
- With PostBatchEditVerifyHook firing, agent used more turns on bug fixes
- MaxTurnsGuardHook force at turn 20 cut off task before even attempting test file

---

## Fix: MaxTurnsGuardHook Threshold Increase (M43)

Current: `warnAt=15, forceAt=20`  
Proposed: `warnAt=20, forceAt=30`

**Rationale**:
- PostBatchEditVerifyHook is correct behavior and should stay
- Each file edit now correctly triggers `mvn test` verification
- L7 (3 files): needs ~15 turns for bug fixes + ~5 for test file = 20 total → forceAt=30 gives buffer
- L10 (5 files): needs ~25 turns for bug fixes + ~8 for test file = 33 total → may still need 35+

**Additional consideration**:
- MaxTurnsGuard purpose: prevent infinite loops, not cut short complex tasks
- With timeout=1200s as the outer wall, inner turn limit of 30 is still safe

---

## Cumulative GLM-5.1 Scoreboard

| Level | Best Score | Milestone | Notes |
|-------|-----------|-----------|-------|
| L2 | 99 | M33 | ✅ |
| L5 | ~100 | M39 | ✅ backtick regex fixed |
| L7 | ~72 | M41 | Best run; M42 regressed due to hook interaction |
| L8 | 48 | M37 | GLM-5.1 capability wall |
| L9 | 100 | M38 | ✅ PRE_COMPLETE hook |
| L10 | ~68 | M41 | Best run; M42 regressed due to hook interaction |

---

## Recommended M43 Actions

1. **MaxTurnsGuardHook: raise defaults to warnAt=20, forceAt=30**
   - Update `MaxTurnsGuardHook` constructor defaults
   - Update `MaxTurnsGuardHookTest` with new thresholds
   - `mvn clean verify` to confirm

2. **Rebuild jar + L7 rerun (1200s)**
   - Expected: ~90+ (bug fixes in ~15 turns, test file in turns 16-22)

3. **Rebuild jar + L10 rerun (1200s)**
   - Expected: ~75+ (5 files still tight, but forceAt=30 gives room)
