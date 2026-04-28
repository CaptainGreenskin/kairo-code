# M17 Benchmark Summary — L1/L2/L3 Cross-Executor Results

**Timestamp:** 2026-04-29
**Milestone:** M17-003

---

## 1. Score Matrix — Executor × Level

| Executor | L1 (Bug Fix, 18 tests) | L2 (EventDispatcher, 17 tests) | L3 (TurnMetricsCollector) |
|----------|----------------------:|-------------------------------:|--------------------------:|
| **kairo-code** (GLM-5.1) | **18/18** ✅ | — | — |
| **qodercli** (Claude qwork-ultimate) | **18/18** ✅ | **96/100** ⭐⭐⭐ | **93/100** ⭐⭐⭐ |

> kairo-code has not yet been evaluated on L2 or L3. The L1 benchmark is saturated
> (both executors 18/18), so L2/L3 data are needed to differentiate.

### kairo-code L1 Evolution

| Version | Milestone | Tests | Duration | Key Change |
|---------|-----------|-------|----------|------------|
| v1 (M12) | baseline | 0/18 | ~22 min | old_string mismatch |
| v2 (M13) | Edit Discipline | 17/18 | ~60s | edit tool prompt fix |
| v3 (M15) | PostBatchEditVerifyHook | **18/18** | ~60s | verification hook added |

---

## 2. Capability Axis Comparison

### L1 — Bug Fix (both 18/18, saturated)

| Axis | kairo-code (GLM-5.1) | qodercli (Claude) |
|------|---------------------:|------------------:|
| Test Pass Rate | 35/35 | 35/35 |
| Edit Precision | 20/20 | 20/20 |
| Autonomous Verify | 10/20 (needs hook) | 20/20 (native) |
| Code Quality | 14/15 | 14/15 |
| Efficiency | 8/10 | 9/10 |
| **Estimated Total** | **~87** | **~98** |

### L2 — Feature Implementation (qodercli only)

| Axis | qodercli (Claude) | Notes |
|------|------------------:|-------|
| Test Pass Rate | 35/35 | 17/17 passed |
| Edit Precision | 20/20 | no edit errors |
| Autonomous Verify | 20/20 | ran mvn test autonomously |
| Code Quality | 14/15 | clean ConcurrentHashMap impl, minor: nested class |
| Efficiency | 7/10 | single-pass implementation |
| **TOTAL** | **96/100** | ⭐⭐⭐ |

### L3 — Self-Improvement (qodercli only)

| Axis | qodercli (Claude) | Notes |
|------|------------------:|-------|
| Test Pass Rate | 35/35 | 6/6 new tests + all existing tests pass |
| Edit Precision | 18/20 | 8 files modified, 1 minor rework needed |
| Autonomous Verify | 20/20 | ran mvn verify autonomously |
| Code Quality | 14/15 | follows existing patterns (ToolUsageTracker, StatsCommand) |
| Efficiency | 6/10 | multi-turn on real codebase (~500 LOC added) |
| **TOTAL** | **93/100** | ⭐⭐⭐ |

---

## 3. kairo-code vs Claude Code Gap Analysis

Based on the available data, several gaps are evident:

### 3.1 Model-Level Differences

| Factor | kairo-code (GLM-5.1) | qodercli (Claude) |
|--------|---------------------:|------------------:|
| Native edit tool discipline | Requires prompt engineering | Native |
| Autonomous test verification | Requires PostBatchEditVerifyHook (M15) | Built-in behavior |
| Multi-file coordination | Untested at L2/L3 | Strong (96/100 L2, 93/100 L3) |
| Self-improvement (editing own codebase) | Untested | 93/100 |

### 3.2 Architecture Observations

- **kairo-code's hook SPI is effective but reactive** — hooks fix model weaknesses after the
  fact (e.g., PostBatchEditVerifyHook forces verification the model didn't do on its own).
  This adds latency per turn.
- **qodercli/Claude needs fewer hooks** because the model natively follows tool-use
  discipline. The same hooks in kairo-code's SPI are largely no-ops on Claude-based models.
- **Efficiency drops at L3** (7→6/10) even for Claude, because real codebase editing
  inherently requires more reads, edits, and verification cycles. This gap likely widens
  for GLM-5.1.

### 3.3 Inferred Claude Code Baseline

Based on SCORING.md expected values and qodercli results:

| Capability | Claude Code (expected L2) | qodercli (actual L2) | Gap |
|------------|--------------------------:|---------------------:|----:|
| Total | ~85/100 | 96/100 | +11 |

qodercli with qwork-ultimate exceeds the expected Claude Code baseline on L2, suggesting
the qwork model is at or above Claude Code level for these tasks.

---

## 4. Relationship to SWE-bench

### Current L1-L3 Mapping

| Benchmark Level | SWE-bench Equivalent | Rationale |
|----------------|---------------------|-----------|
| **L1** — 3 isolated bugs, 18 tests | << SWE-bench Lite | Trivial: single-file fixes, no cross-file impact. SWE-bench issues typically require understanding PR context, multiple files, and test creation. |
| **L2** — Feature from spec, 17 tests | < SWE-bench Lite | Moderate: requires reading interfaces, implementing from spec, but fixture codebase is small and isolated. No existing code modification needed. |
| **L3** — Self-improvement on real codebase | ≈ SWE-bench Lite (easier end) | Closest match: modifies real codebase (kairo-code), follows existing patterns, adds new files and modifies 8 existing files. However, the task spec is very detailed (file paths, method signatures, output format) compared to SWE-bench's natural language issue descriptions. |

### Key Differences from SWE-bench

1. **Task specificity**: L1-L3 provide detailed file paths, method signatures, and expected
   output. SWE-bench gives only a GitHub issue + PR diff as ground truth.
2. **Test availability**: All L1-L3 fixtures have pre-written tests. SWE-bench requires
   the agent to both fix the issue AND the tests are part of the PR diff.
3. **Codebase size**: kairo-code is ~5K LOC. SWE-bench Java repos range from 10K-500K LOC.

### Estimated SWE-bench Performance

Based on L3 results (93/100 with detailed spec):
- **qodercli on SWE-bench Lite (Java subset)**: estimated 40-55% resolve rate
  (dropping ~20-30 points for less-specific task descriptions + larger codebase)
- **kairo-code on SWE-bench**: cannot estimate until L2/L3 data exists

> Claude Code's reported SWE-bench Lite resolve rate is ~49-55%. qodercli's L2/L3 scores
> are consistent with this range.

---

## 5. M18 Recommendations — Priority Directions for kairo-code

### P0: Run kairo-code on L2 and L3

The single biggest gap in our data is kairo-code's performance on multi-file tasks.
Until we have L2/L3 results for kairo-code, we cannot:
- Quantify the GLM-5.1 vs Claude gap on non-trivial tasks
- Determine which hooks are actually needed vs nice-to-have
- Make informed trade-offs on model cost vs quality

**Tasks:**
- `M18-001`: Run kairo-code L2 (EventDispatcher, 17 tests) — expected ~75-85/100
- `M18-002`: Run kairo-code L3 (TurnMetricsCollector, real codebase) — expected ~60-75/100

### P1: Reduce Hook Dependency

If kairo-code requires hooks for basic behaviors (autonomous verification, edit discipline)
that Claude does natively, each hook adds:
- Per-turn latency (hook execution + context injection)
- Token cost (longer system prompt)
- Maintenance burden (hook SPI surface area)

**Investigate:**
- Can system prompt improvements (M13-style) replace PostBatchEditVerifyHook?
- Which hooks are model-specific vs universally useful?

### P2: Harder Benchmark (L4)

L1 is saturated. Even L2 may not differentiate if both executors score >90.
L4 should feature:
- 5-7 bugs with cross-file dependencies
- A larger codebase (10K+ LOC)
- At least one task requiring test creation (not just fixing)
- Natural language task descriptions (not file-path-specific specs)

This would better approximate SWE-bench difficulty and expose real differences.

### P3: Cost-Quality Analysis

Collect token usage and cost data for kairo-code (GLM-5.1) vs qodercli (Claude) across
L1-L3. If GLM-5.1 achieves 80% of Claude's quality at 30% of the cost, that's a valid
trade-off for many use cases. But we need the data.

---

## Appendix: Data Sources

| Report | File |
|--------|------|
| kairo-code L1 v3 (18/18) | `results/benchmark_20260429_kairo-code-v3.md` |
| qodercli L1 v1 (18/18) | `results/benchmark_20260429_qodercli-v1.md` |
| qodercli L2 (96/100) | `results/benchmark_20260429_qodercli_l2.md` |
| qodercli L3 (93/100) | Commit `ca5bf94` — task-m17-002 completion |
| Cross-executor comparison | `results/benchmark_20260429_comparison.md` |
| Scoring rubric | `framework/SCORING.md` |
| Benchmark framework | `framework/README.md` |
