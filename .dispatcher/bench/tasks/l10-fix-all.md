# L10: Fix Concurrent Order Processing Bugs

The order processing system has several concurrency and thread-safety bugs.
Under concurrent load, orders may be double-reserved, payment counts may be wrong,
and the ledger may show inconsistent state.

## Instructions

1. Run `mvn test` to see current failures (there may be none — the bugs only manifest under concurrent load).

2. Fix all bugs. The issues are in:
   - `src/main/java/com/example/OrderQueue.java`
   - `src/main/java/com/example/OrderProcessor.java`
   - `src/main/java/com/example/PaymentLedger.java`
   - `src/main/java/com/example/InventoryReserver.java`
   - `src/main/java/com/example/OrderService.java`

   Hint: these bugs do NOT fail in single-threaded tests — they require concurrent access to trigger.
   Read how each class interacts under load before deciding where to fix.

3. Create `src/test/java/com/example/ConcurrentOrderTest.java` with at least 8 tests that:
   - Use multiple threads (ExecutorService / CompletableFuture)
   - Verify that the fixed code is safe under concurrent access
   - Cover: capacity enforcement, count accuracy, ledger consistency, reservation exclusivity, order completion ordering

4. Run `mvn test` to confirm all tests pass (41 existing + ≥8 new).

## Constraints

- Do NOT modify existing test files
- Do NOT modify `pom.xml`
- All 49+ tests must pass
