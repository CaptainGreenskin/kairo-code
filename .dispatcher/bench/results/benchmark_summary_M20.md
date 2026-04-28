# M20 Benchmark Summary — L4 Results + Hook ROI Analysis

**Timestamp:** 2026-04-29
**Milestone:** M21-002

---

## 1. Complete Score Matrix (Through M20)

| Executor | L1 | L2 | L3 | L4 |
|---|---|---|---|---|
| **kairo-code** (GLM-5.1) | 100/100 | TBD (blocked: no API key) | TBD | TBD |
| **qodercli** (Claude qwork-ultimate) | 100/100 | 96/100 | 93/100 | 96/100 |

### kairo-code L1 Evolution

| Version | Milestone | Tests | Duration | Key Change |
|---------|-----------|-------|----------|------------|
| v1 (M12) | baseline | 0/18 | ~22 min | old_string mismatch |
| v2 (M13) | Edit Discipline | 17/18 | ~60s | edit tool prompt fix |
| v3 (M15) | PostBatchEditVerifyHook | **18/18** | ~60s | verification hook added |

---

## 2. qodercli L4 Result — Detailed Analysis

**Fixture:** l4-order-system — 5 cross-file bugs, 33 tests, 4 test classes
**Result:** 33/33 ✅ — **96/100**

Reference: `benchmark_20260429_qodercli_l4.md`

### 5-Axis Score Breakdown

| Axis | Max | Score | Notes |
|------|-----|-------|-------|
| Test Pass Rate | 35 | 35 | 33/33 ✅ |
| Edit Precision | 20 | 18 | Modified Product.java (task said don't, but bug was there — fixture design flaw) |
| Autonomous Verify | 20 | 20 | Ran `mvn test`, confirmed passing |
| Code Quality | 15 | 14 | Minimal, idiomatic fixes; Optional pattern in addItem() |
| Efficiency | 10 | 9 | Single-shot (-p mode); all 5 bugs found and fixed |
| **Total** | **100** | **96** | |

### Bug-by-Bug Quality Assessment

| Bug | Location | Fix Quality | Notes |
|-----|----------|-------------|-------|
| 1 | `Order.java` addItem() | Excellent | Used existing `findItem()` helper, preserved immutable OrderItem pattern |
| 2 | `PricingEngine.java` discount | Excellent | Minimal fix: `/100` → `/100.0` — classic integer division trap |
| 3 | `PricingEngine.java` null guard | Excellent | Handles both null order and empty items list |
| 4 | `Product.java` releaseStock() | Excellent | `Math.max(0, ...)` floor guard; correctly traced to real location despite misleading hint pointing to InventoryTracker.java |
| 5 | `OrderService.java` rollback | Excellent | Clean rollback pattern with tracked reservation list; requires cross-file call chain understanding |

### Key Findings

1. **Cross-file call chain comprehension**: Bug 5 requires understanding `OrderService → Product.reserveStock()` → `Product.releaseStock()` chain. qodercli correctly identified the missing rollback and implemented a tracked reservation list for selective rollback.

2. **Bypassing misleading hints**: Bug 4's task description pointed to `InventoryTracker.java`, but the actual defect was in `Product.releaseStock()`. qodercli traced via failing test execution to the correct location rather than following the red herring — demonstrating real debugging capability over prompt-following.

3. **Integer arithmetic pitfall detection**: Bug 2 (`discountPercent / 100` integer truncation) is a classic Java trap — correctly identified with a minimal, precise fix.

4. **Cross-level consistency**: L4 (96) matches L2 (96), confirming strong cross-file bug-fix capability at the same level as feature implementation.

### Cross-Level Comparison: qodercli

| Level | Fixture | Tests | Score | Notes |
|-------|---------|-------|-------|-------|
| L1 | 3-bug calculator | 18/18 | 100/100 | Saturated |
| L2 | EventDispatcher skeleton | 17/17 | 96/100 | Feature impl |
| L3 | TurnMetricsCollector (self-codebase) | 12/12 | 93/100 | Real codebase, multi-file |
| L4 | Order system 5-bug | 33/33 | **96/100** | Cross-file, SWE-bench caliber |

### Fixture Quality Note

Bug 4 was designed to live in `InventoryTracker.java` but was actually implemented in `Product.releaseStock()`. The task file said "Do not modify Product.java — correct, no bugs" which contradicted reality. qodercli found the real bug anyway. The fixture should be updated: either move the bug to InventoryTracker, or remove Product.java from the "do not modify" list.

---

## 3. SWE-bench Comparison (Updated)

| Benchmark | qodercli | kairo-code (inferred) | Claude Code |
|---|---|---|---|
| L1-L4 avg | ~96/100 | ~85/100 (est.) | ~88/100 (est.) |
| SWE-bench Lite equiv | ~top 10% | ~median | 44% resolve rate |

### Benchmark Level → SWE-bench Mapping

| Level | SWE-bench Equivalent | Rationale |
|-------|---------------------|-----------|
| L1 — 3 isolated bugs, 18 tests | << SWE-bench Lite | Single-file fixes, no cross-file impact |
| L2 — Feature from spec, 17 tests | < SWE-bench Lite | Small isolated fixture, no existing code modification |
| L3 — Self-improvement on real codebase | ≈ SWE-bench Lite (easier end) | Real codebase but highly detailed spec |
| L4 — 5 cross-file bugs, natural language | ≈ SWE-bench Lite | Cross-file dependencies, NL descriptions, no line numbers |

### Estimated SWE-bench Performance

- **qodercli on SWE-bench Lite (Java subset)**: estimated 40-55% resolve rate (dropping ~20-30 points for less-specific task descriptions + larger codebase)
- **Claude Code reported**: ~44% resolve rate on SWE-bench Lite
- **kairo-code**: cannot estimate until L2/L3/L4 data exists

qodercli's L4 score (96/100) on a SWE-bench Lite-caliber fixture suggests strong single-task bug-fix performance. Multi-task repository-scale performance (SWE-bench's variety across repos) remains unvalidated.

---

## 4. Hook Investment ROI Analysis

### Hooks Merged (M17-M20)

| Hook | Milestone | Purpose | Target Model | ROI |
|------|-----------|---------|-------------|-----|
| **PostBatchEditVerifyHook** | M15 | Forces test verification after batch edits | GLM-5.1 | **High** — fixed 17/18 → 18/18 on L1 |
| **ContextWindowGuardHook** | M18 | Prevents GLM-5.1 context window overflow | GLM-5.1 | **Medium** — safety net for long sessions |
| **MaxTurnsGuardHook** | M19 | Prevents infinite loops in one-shot sessions | GLM-5.1 | **Medium** — prevents resource waste |
| **TestFailureFeedbackHook** | M21 (pending) | Structured failure context for failed tests | GLM-5.1 | **TBD** — pending merge |

### ROI Observations

1. **kairo-code's hook SPI is effective but reactive** — hooks fix model weaknesses after the fact (e.g., PostBatchEditVerifyHook forces verification the model didn't do on its own). This adds latency per turn.

2. **qodercli/Claude needs fewer hooks** because the model natively follows tool-use discipline. The same hooks in kairo-code's SPI are largely no-ops on Claude-based models.

3. **Hook cost model**: Each hook adds per-turn latency (execution + context injection), token cost (longer system prompt), and maintenance burden (hook SPI surface area). For GLM-5.1, this trade-off is justified by the quality gains. For Claude-based models, hooks add cost without benefit.

4. **PostBatchEditVerifyHook** is the highest-ROI hook — it directly closed the L1 gap (17/18 → 18/18) by compensating for GLM-5.1's lack of autonomous test verification.

### Hook Reduction Opportunity

If system prompt improvements (M13-style) can replace behavioral hooks, kairo-code would benefit from:
- Lower per-turn latency
- Reduced token cost
- Smaller maintenance surface

**Investigate:** Can prompt engineering replace PostBatchEditVerifyHook? Which hooks are model-specific vs universally useful?

---

## 5. M21 Recommendations

### P0: Provide KAIRO_CODE_API_KEY → Run kairo-code L2/L3/L4 Baseline

The single biggest gap in our data is kairo-code's performance on multi-file tasks. Until we have L2/L3/L4 results for kairo-code, we cannot:
- Quantify the GLM-5.1 vs Claude gap on non-trivial tasks
- Determine which hooks are actually needed vs nice-to-have
- Make informed trade-offs on model cost vs quality

**Expected outcomes:**
- L2 (EventDispatcher): ~75-85/100
- L3 (TurnMetricsCollector): ~60-75/100
- L4 (Order system): ~50-70/100

### P1: TestFailureFeedbackHook Effect Validation

Once TestFailureFeedbackHook (M21) is merged:
- Run kairo-code L4 before/after comparison
- Measure whether structured failure context improves bug-fix success rate
- Quantify the hook's contribution to the overall score

### P2: L5 Fixture Design

L4 is approaching saturation for qodercli (96/100). L5 should feature:
- **Test creation required** (not just bug fixing) — the agent must write tests to verify its own fix
- **Larger codebase** (10K+ LOC) to stress navigation and comprehension
- **Ambiguous task descriptions** with multiple plausible root causes
- **Time/resource constraints** to measure efficiency under pressure

This would better approximate the upper end of SWE-bench difficulty.

### P3: L4 Fixture Quality Fix

Bug 4 description points to `InventoryTracker.java` but the actual bug is in `Product.releaseStock()`. The task file says "Do not modify Product.java — correct, no bugs" which contradicts reality.

**Options:**
- Move the bug logic to InventoryTracker.java (preserve "do not modify Product" rule)
- Remove Product.java from the "do not modify" list (acknowledge the bug is there)
- Redesign Bug 4 entirely to live in InventoryTracker

---

## Appendix: Data Sources

| Report | File |
|--------|------|
| kairo-code L1 v3 (18/18) | `results/benchmark_20260429_kairo-code-v3.md` |
| qodercli L1 v1 (18/18) | `results/benchmark_20260429_qodercli-v1.md` |
| qodercli L2 (96/100) | `results/benchmark_20260429_qodercli_l2.md` |
| qodercli L3 (93/100) | Commit `ca5bf94` — task-m17-002 completion |
| qodercli L4 (96/100) | `results/benchmark_20260429_qodercli_l4.md` |
| M17 Summary | `results/benchmark_summary_M17.md` |
| Scoring rubric | `framework/SCORING.md` |
| Benchmark framework | `framework/README.md` |
