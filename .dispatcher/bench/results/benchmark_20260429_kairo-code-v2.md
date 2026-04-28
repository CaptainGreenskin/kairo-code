# Cross-Executor Benchmark Report

**Timestamp:** 20260429
**Task:** Fix 3 Java bugs (RateLimiter, Cache, StringUtils)
**Total tests:** 18
**kairo-code version:** 0.2.0-SNAPSHOT (M13 improvements applied: Read Efficiency + Edit Tool Discipline)
**Model:** GLM-5.1
**Run mode:** Direct (`--working-dir` pointed at fixtures, bypassing dispatcher worktree)

## Summary

| Metric | M13 baseline (v1) | M14 post-fix (v2) |
|--------|-------------------|-------------------|
| Tests passed | 0/18 | **17/18** |
| Edit success | 0/3 | **3/3 attempted** |
| Files fixed correctly | 0/3 | 2/3 (RateLimiter ✓, StringUtils ✓, Cache ~) |
| Duration | ~22 min | ~60 sec |
| Model output | Plan + 9 tool calls, edits failed | 1 message + 3 edits, mostly correct |

## Per-File Results

### ✅ RateLimiter.java — FIXED
- Bug: token refill rate was `(elapsed / 1000.0) / 1000.0` (double-divided by 1000)
- Fix: removed one division
- Tests: 4/4 pass

### ✅ StringUtils.java — FIXED
- Bug: `str.trim()` called before null check → NPE on null input
- Fix: moved null check before trim()
- Tests: 10/10 pass

### ⚠️ Cache.java — PARTIALLY FIXED
- Original bug: TTL condition `< entry.expiry` inverted (valid entries rejected)
- Model fix: changed to `> entry.expiry` — correct concept, but returns null WITHOUT
  removing the expired entry from the map
- Side effect: `sizeAfterExpiry` test fails (`expected: 1 but was: 2`)
- Root cause: model missed that the early-return (`>`) bypasses the `isExpired()` branch
  which does `map.remove()`. Fix should remove the inline check and rely solely on
  `isExpired()` which handles both detection and cleanup.
- Tests: 3/4 pass (sizeAfterExpiry fails)

## Edit Tool Discipline Impact

v1 (pre-M13-006): 0/3 edits succeeded — old_string matching failed silently  
v2 (post-M13-006): 3/3 edits were applied — Edit Tool Discipline worked

The M13-006 fix (`## Edit Tool Discipline` in system-prompt.md) confirmed effective.
GLM-5.1 now correctly reads before editing and uses exact old_string content.

## Remaining Issues

### 1. Early Exit Pattern (partially mitigated)
v1: Agent stopped after 2 tool calls (plan-only trap)  
v2: Agent made all 3 edits in one fast pass (~60s), BUT skipped verification:
- Did NOT run `mvn test` after edits to confirm fixes
- Did NOT detect the Cache.sizeAfterExpiry failure

Discipline prefix says "verify" but agent skipped the final verification step.

### 2. Cache Fix Incomplete
Nuanced: model understood TTL inversion but missed map cleanup side effect.
A verify step (`mvn test` after edits) would have caught this and triggered retry.

## Verdict

| Criteria | v1 (M12) | v2 (M13 fix) | Delta |
|----------|----------|--------------|-------|
| Tests fixed | 0/18 | **17/18** | +17 |
| Edit success rate | 0% | 100% | +100% |
| Time | 22 min | 60 sec | -22x |

**M13-006 Edit Tool Discipline is confirmed effective.**  
Primary remaining gap: agent skips post-edit verification (`mvn test` after edits).

## Next Steps (M15)

1. **PostVerifyHook** — inject "run mvn test now" after N edits with no subsequent bash
2. **Cache fix** — discipline prompt should force verify+retry loop
3. Aim: 18/18 with self-verification
