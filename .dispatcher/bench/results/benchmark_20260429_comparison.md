# Cross-Executor Benchmark Comparison — 20260429

**Fixture:** 3 Java bugs (RateLimiter, Cache, StringUtils), 18 tests
**Date:** 2026-04-29

## Full History

| Version | Executor | Model | Tests | Duration | Key Change |
|---------|----------|-------|-------|----------|------------|
| v1 (M12) | kairo-code | GLM-5.1 | 0/18 | ~22 min | baseline — old_string mismatch |
| v2 (M13) | kairo-code | GLM-5.1 | 17/18 | ~60 sec | Edit Tool Discipline added |
| v3 (M15) | kairo-code | GLM-5.1 | 18/18 ✅ | ~60 sec | PostBatchEditVerifyHook added |
| **v1 (M16)** | **qodercli** | **Claude (qwork-ultimate)** | **18/18 ✅** | **~56 sec** | cross-executor baseline |

## Executor Comparison (same 3-bug fixture)

| Metric | kairo-code (GLM-5.1) | qodercli (Claude) |
|--------|---------------------|-------------------|
| Final score | 18/18 | 18/18 |
| Hooks required | Yes (PostBatchEditVerifyHook) | No |
| Fix approach | multi-step | single-pass |
| Duration | ~60s | ~56s |
| Edit reliability | Needs discipline prompt | Native |

## Analysis

### kairo-code Journey
- M12→M13: Edit Tool Discipline eliminated old_string mismatches (0%→100% edit success)
- M13→M15: PostBatchEditVerifyHook forced post-edit verification (17→18/18)
- Total investment: 15 tasks across 3 milestones

### qodercli
- Achieved 18/18 on first attempt with no special hooks
- Claude-based models handle edit tool correctly by default
- Autonomous test verification without hook injection

## Conclusion

**Both executors equal at this difficulty level.** The 3-bug benchmark is saturated.

kairo-code's improvement journey validated the hook SPI architecture — the hooks are
effective when the model needs guidance. For Claude-based models (qodercli), fewer hooks
are needed due to stronger native tool-use discipline.

## M17 Recommendations

1. **Harder benchmark** (5–7 bugs, cross-file dependencies, larger codebase) to separate executors
2. **kairo-code: reduce hook dependency** — can GLM-5.1 match qodercli without PostBatchEditVerifyHook?
3. **kairo-code cost analysis** — GLM-5.1 pricing vs Claude (qodercli) for equivalent quality
4. **Self-Evolution slice** — benchmark as regression test for M7 self-evolution agent
