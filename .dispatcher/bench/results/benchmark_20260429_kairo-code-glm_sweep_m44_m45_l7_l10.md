# kairo-code GLM-5.1 Benchmark — M44/M45（L7/L10 Bug Fix 轮）

**Date**: 2026-04-29  
**Executor**: kairo-code (GLM-5.1)  
**Changes**:
- M44 (commit `b00a076`): `PostBatchEditVerifyHook` — read/grep turns no longer count as idle
- M45 (commit `e7e3024`): `UnfulfilledInstructionHook` — re-inject same missing file up to MAX_INJECTIONS (remove per-file dedup)

---

## M44 Results

| Level | Tests Pass | New Test File | Build | Timeout | Score |
|-------|-----------|---------------|-------|---------|-------|
| L7 | **66/66** (25 existing ✅, 41 new ✅) | **ConcurrentUserServiceTest.java** ✅ | SUCCESS | no (6 iters, 19972 tokens) | **~97/100** |
| L10 M44a | 41/41 ✅ | not created ❌ | SUCCESS | no (7 iters, 24337 tokens) | ~40 |
| L10 M44b | 41/41 ✅ | not created ❌ | SUCCESS | no (13 iters, 116786 tokens) | ~40 |

### Root Cause of L10 M44 Failure

`UnfulfilledInstructionHook` injected ONE reminder at iteration 1 (agent's planning response had no tool calls). Agent received the reminder, worked on bug fixes in iterations 2-7. When the session ended at iteration 7 (agent's final END_TURN response), PRE_COMPLETE fired again but `injectedFiles.contains(path)` = TRUE → hook skipped → session ended without second injection.

The `injectedFiles` set was designed to cycle through multiple missing files (A→B→C→D). Side effect: if a single file is never created, it only gets one injection.

---

## M45 Results

| Level | Tests Pass | New Test File | Build | Timeout | Score |
|-------|-----------|---------------|-------|---------|-------|
| L10 | **51/51** (41 existing ✅, 10 new ✅) | **ConcurrentOrderTest.java** ✅ | SUCCESS | no (12 iters, 128249 tokens) | **~92/100** |

---

## L7 M44 — 6-Axis Score

| Axis | Max | Score | Notes |
|------|-----|-------|-------|
| Test Pass Rate | 35 | 35 | 66/66 = 100% |
| Edit Precision | 20 | 18 | Fixed 3 files, created test; clean code |
| Auto Verify | 20 | 20 | BUILD SUCCESS, 0 failures |
| Code Quality | 15 | 13 | Concurrent fixes correct, 41-test suite comprehensive |
| Efficiency | 10 | 11 | 6 iterations, 19972 tokens — highly efficient |
| **Total** | **100** | **~97** | |

---

## L10 M45 — 6-Axis Score

| Axis | Max | Score | Notes |
|------|-----|-------|-------|
| Test Pass Rate | 35 | 32 | 51/51 = 100% but only 10/≥8 new tests (10 > min) |
| Edit Precision | 20 | 18 | Fixed 5 files, created test; BUILD SUCCESS |
| Auto Verify | 20 | 20 | BUILD SUCCESS, 0 failures |
| Code Quality | 15 | 12 | 5-file concurrent fix correct; test covers key scenarios |
| Efficiency | 10 | 10 | 12 iters, 128K tokens (5 files = more complex) |
| **Total** | **100** | **~92** | |

---

## Root Cause Analysis: Why M42/M43 Regressed Then M44/M45 Fixed

### Bug chain (from M40 to M45):

1. **M40 (commit `26f4133`)**: Fixed `PostBatchEditVerifyHook` tool names (`write_file` → `write`)
   - Hook started ACTUALLY firing for the first time since M13
   - Unintended consequence: `read` tool calls between edits counted as "idle turns" → premature injection
   
2. **M42/M43**: Hook now fires aggressively → agent runs `mvn test` after every read → turns exploded → MaxTurnsGuardHook hit at turn 20 → test file never created (regression)

3. **M44 (commit `b00a076`)**: Fixed `PostBatchEditVerifyHook` to only count truly idle turns (zero tool calls)
   - L7 immediately jumped to 97/100 in 6 iterations
   - L10 still failed because UnfulfilledInstructionHook dedup prevented re-injection

4. **M45 (commit `e7e3024`)**: Fixed `UnfulfilledInstructionHook` per-file dedup
   - Agent now gets up to 3 injection chances per missing file
   - L10 completed successfully: 51/51 pass, BUILD SUCCESS

---

## Cumulative GLM-5.1 Scoreboard

| Level | Best Score | Milestone | Notes |
|-------|-----------|-----------|-------|
| L2 | 99 | M33 | ✅ |
| L5 | ~100 | M39 | ✅ backtick regex fixed |
| L7 | **97** | M44 | ✅ 6 iters, 66/66, PostBatchEditVerifyHook idle fix |
| L8 | 48 | M37 | GLM-5.1 capability wall (HashMap/ConcurrentHashMap) |
| L9 | 100 | M38 | ✅ PRE_COMPLETE hook |
| L10 | **92** | M45 | ✅ 12 iters, 51/51, UnfulfilledInstructionHook re-inject fix |

---

## Recommended M46 Actions

1. **L7 stability run**: One more run to confirm M44 result is stable (6 iters is fast)
2. **L8 analysis**: Accept as capability wall; document separately
3. **L10 stability run**: Confirm M45 result (12 iters with 5-file concurrency)
4. **Defects4J feasibility**: With L7/L10 now both ≥90, consider Defects4J eval per project_defects4j_trigger.md memory
