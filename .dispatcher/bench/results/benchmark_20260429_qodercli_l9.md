# qodercli L9 Benchmark — Inventory Cache Consistency Non-Local Invariants

**Date**: 2026-04-29
**Fixture**: l9-inventory-cache
**Model**: GLM-5.1
**Task**: Fix 5 cross-class cache consistency bugs + create InventoryCacheConsistencyTest (≥8 tests)

## Score

| Dimension | Score | Max | Notes |
|-----------|-------|-----|-------|
| Test Pass Rate | 35 | 35 | 55/55 tests pass (45 existing + 10 new) |
| Edit Precision | 18 | 20 | 4 files edited + 1 new test file; all changes correct and minimal |
| Auto Verify | 20 | 20 | Fully autonomous, mvn test passes without intervention |
| Quality | 14 | 15 | All 5 root causes correctly identified; 2 cross-class tests included |
| Efficiency | 10 | 10 | ~8-10 turns, well under 15 threshold |
| **Total** | **97** | **100** | |

## Execution

- Turns used: ~8-10 (estimated; --print mode does not report turns)
- Duration: 136s
- Files modified: 4 (InventoryCache, PriceCalculator, InventoryService, + new InventoryCacheConsistencyTest)
- pom.xml: not modified

## Bugs Fixed

| # | File | Bug | Fix |
|---|------|-----|-----|
| 1 | InventoryCache.java:73 | `isExpired` uses `>` instead of `>=` | Changed to `>=` |
| 2 | PriceCalculator.java:28 | `getDiscountedPrice` reads from cache (stale data) | Changed to `store.get()` |
| 3 | InventoryService.java:47 | `updateStock` missing cache invalidation | Added `cache.invalidate(productId)` |
| 4 | InventoryService.java:56-59 | `restockProduct` publishes event BEFORE store update | Reordered: update store → invalidate cache → publish event |
| 5 | InventoryService.java:70 | `removeProduct` missing cache invalidation | Added `cache.invalidate(productId)` |

## New Tests (InventoryCacheConsistencyTest.java — 10 tests)

1. `cacheShouldReflectStockUpdateAfterInvalidation` — cache invalidation after stock updates
2. `cacheEntryAtExactTTLShouldBeExpired` — TTL boundary (exactly-expired entries)
3. `priceCalculatorShouldUseFreshStoreDataNotStaleCache` — price with fresh store data (stock increase)
4. `priceCalculatorShouldReflectStockDecreaseFromStore` — price with fresh store data (stock decrease)
5. `restockEventShouldBePublishedAfterStoreUpdate` — event ordering correctness
6. `restockEventOrderShouldPreserveUpdateThenPublish` — event ordering across multiple products
7. `deletedProductShouldNotBeVisibleInCache` — deleted product visibility in cache
8. `serviceUpdateStockShouldBeVisibleThroughCalculator` — cross-class: service → PriceCalculator
9. `serviceRestockShouldInvalidateCacheAndBeVisibleViaCacheGet` — cross-class: service → raw cache.get
10. `multipleUpdatesShouldKeepCacheConsistentWithStore` — multi-update consistency

## Analysis

### L9 vs L8 Difficulty

L9 is significantly harder than L8 due to **non-local constraints**. The bugs cannot be identified by examining a single file in isolation:

- **Bug 3** (PriceCalculator): requires understanding that `cache` is passed in and may hold stale data — the fix is to bypass cache entirely.
- **Bug 4** (restock ordering): requires understanding the EventBus subscriber pattern and that subscribers read from the store.
- **Bugs 1, 5** (cache invalidation): require understanding the InventoryStore → InventoryCache protocol contract.

In L8 (rate limiter), bugs were mostly intra-class (DCL volatile, heap sift-up, algorithm correctness). In L9, the root causes span the interaction boundaries between classes.

### Scoring Observations

- **Strengths**: qodercli correctly identified all 5 bugs including the subtle cross-class protocol violations. The test file is well-structured with explicit cross-class verification tests (tests 8 and 9). The restock reordering fix was the most complex change and was handled correctly.
- **Efficiency**: ~136s for a task requiring reading 6 source files, understanding cross-class interactions, making 5 targeted fixes, and writing 10 tests is strong performance.
- **Test quality**: 10 tests exceeds the 8 minimum. Two tests explicitly verify state through a different class than the one modified (tests 8, 9), satisfying the cross-class requirement.

### Comparison to L8 Score

| Level | Total Score | Key Difference |
|-------|-------------|----------------|
| L8 | 97 | Intra-class bugs, concurrency focus |
| L9 | 97 | Cross-class protocol violations, state sync |

qodercli maintained L8-level performance despite the increased non-local reasoning difficulty of L9, demonstrating strong multi-file comprehension and protocol analysis capability.
