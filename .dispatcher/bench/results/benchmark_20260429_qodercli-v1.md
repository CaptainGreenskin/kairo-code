# Cross-Executor Benchmark Report — qodercli v1

**Timestamp:** 20260429
**Task:** Fix 3 Java bugs (RateLimiter, Cache, StringUtils)
**Total tests:** 18
**Executor:** qodercli 0.2.0
**Model:** qwork-ultimate
**Run mode:** Direct (`--workspace` at fixtures)

## Summary

| Metric | kairo-code v3 (M15) | qodercli v1 (M16) |
|--------|---------------------|-------------------|
| Tests passed | **18/18 ✅** | **18/18 ✅** |
| Edit success | 3/3 correct | 3/3 correct |
| Files fixed | 3/3 | 3/3 |
| Duration | ~60 sec | **~56 sec** |
| Output | verbose (multi-step) | concise (single pass) |

## Per-File Results

### ✅ RateLimiter.java — FIXED
- Removed second `/1000.0`; `elapsed / 1000.0` only → 4/4 tests pass

### ✅ Cache.java — FIXED
- Removed inverted inline check `if (System.currentTimeMillis() < entry.expiry) return null;`
- Relies on `isExpired()` + `map.remove()` (same approach as kairo-code v3)
- 4/4 tests pass including `sizeAfterExpiry`

### ✅ StringUtils.java — FIXED
- Reordered: `str == null || str.trim().isEmpty()` → 10/10 tests pass

## Fix Quality Comparison

| File | kairo-code v3 fix | qodercli v1 fix |
|------|------------------|-----------------|
| RateLimiter | removed `/1000.0` | removed `/1000.0` |
| Cache | removed inverted check | removed inverted check |
| StringUtils | moved null check before trim | moved null check before trim |

All fixes identical in approach and correctness.

## Executor Behavior Comparison

| Behavior | kairo-code (GLM-5.1) | qodercli (Claude) |
|----------|---------------------|-------------------|
| Strategy | Multi-step with verification loop | Single-pass, all fixes at once |
| Output style | Verbose tool-call narration | Concise summary |
| Tool calls | ~1 bash + 3 edits | (internal, not visible) |
| Post-edit test | Required PostBatchEditVerifyHook | Ran mvn test autonomously |
| Hook required? | Yes (M15 PostBatchEditVerifyHook) | No hooks needed |

## Verdict

**Both executors achieve 18/18 on this benchmark.**

- qodercli is slightly faster (~56s vs ~60s) and more concise in output
- qodercli runs `mvn test` autonomously without needing hook injection
- kairo-code (GLM-5.1) required M15 PostBatchEditVerifyHook for reliable verification
- For this complexity level (3 isolated bugs, 18 tests), both are equivalent

## Implications

The benchmark at 3-bug difficulty is no longer differentiating. Executor strength prior
"Claude Code > Qoder ≈ Cursor > opencode" holds — qodercli matches kairo-code here.

**For M17:** Need a harder benchmark (5–7 bugs, cross-file interactions, larger codebase)
to distinguish executor capabilities meaningfully.
