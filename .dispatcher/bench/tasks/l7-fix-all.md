# L7: Fix Concurrent Bugs + Create Missing Concurrent Tests

The user service system has concurrent access bugs and performance issues.

## Instructions

1. Run `mvn test` to see current test results (single-threaded tests may pass,
   but concurrent behavior is broken).

2. Fix all bugs. The issues are in:
   - `src/main/java/com/example/UserCache.java`
   - `src/main/java/com/example/UserService.java`
   - `src/main/java/com/example/AuditLogger.java`

3. Create `src/test/java/com/example/ConcurrentUserServiceTest.java` —
   Write **at least 8 concurrent tests** covering:
   - Thread-safe cache operations (get/put from multiple threads without exceptions)
   - LRU eviction correctness under concurrent access
   - Atomic getOrCreate (no duplicate creation under concurrent callers)
   - AuditLogger concurrent append correctness (no data corruption)
   - Thread-safety of UserService.addUser under concurrent calls
   - size() consistency after concurrent modifications
   - searchByName correctness (prefix filter works)
   - Use ExecutorService or CountDownLatch in at least one test

4. Run `mvn test` again to confirm all tests pass (including your new ones).

## Constraints

- Do **not** modify existing test files (`UserCacheTest.java`, `UserServiceTest.java`)
- Do **not** modify `pom.xml`
- All 33+ tests (25 existing + ≥8 new) must pass at the end
