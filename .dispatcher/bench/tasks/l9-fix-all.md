# L9: Fix Inventory Cache Consistency Bugs

The inventory management system has several bugs related to cache consistency
and cross-class protocol violations. Products are stored in `InventoryStore`,
cached in `InventoryCache` with TTL expiry, and prices are calculated by
`PriceCalculator` based on stock levels. `InventoryService` orchestrates the
operations.

## Instructions

1. Run `mvn test` to see current failures.

2. Fix all bugs. The issues span multiple files:
   - `src/main/java/com/example/InventoryStore.java`
   - `src/main/java/com/example/InventoryCache.java`
   - `src/main/java/com/example/PriceCalculator.java`
   - `src/main/java/com/example/InventoryService.java`

   Hint: some bugs are NOT visible from a single file — they arise from
   the interaction between classes. Read how each class is used before
   deciding where to make changes.

3. Create `src/test/java/com/example/InventoryCacheConsistencyTest.java` —
   the system has no tests verifying cross-class cache consistency. Write at
   least 8 tests covering:
   - Cache invalidation after stock updates
   - TTL boundary behavior (exactly-expired entries)
   - Price calculation using fresh vs stale data
   - Event ordering correctness in restock operations
   - Deleted product visibility in cache
   - At least 2 tests that verify state through a different class than the one modified

4. Run `mvn test` again to confirm all tests pass (including your new ones).

## Constraints

- Do **not** modify existing test files
  (`InventoryStoreTest.java`, `InventoryCacheTest.java`,
  `PriceCalculatorTest.java`, `InventoryServiceTest.java`)
- Do **not** modify `pom.xml`
- All 53+ tests (45 existing + >=8 new) must pass at the end
