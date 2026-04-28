# L8 Fix-All: Rate Limiter System

The rate limiter system has several bugs — some concurrency-related,
some algorithmic, some logic errors.

## Instructions

1. Run `mvn test` to see current failures.

2. Fix all bugs. The issues are in:
   - `src/main/java/com/example/RateLimiter.java`
   - `src/main/java/com/example/PriorityRequestQueue.java`
   - `src/main/java/com/example/RequestProcessor.java`

3. Create `src/test/java/com/example/RateLimiterConcurrentTest.java` —
   RateLimiter has no concurrent test coverage. Write at least 8 tests covering:
   - Thread-safe concurrent allowAndRecord operations
   - Rate limit correctness under concurrent bursts
   - Sliding window accuracy under concurrent requests
   - No race conditions in window data structures
   - Use CountDownLatch or ExecutorService in at least 2 tests

4. Run `mvn test` again to confirm all tests pass (including your new ones).

**Do not modify existing test files or pom.xml.**
All 45+ tests (37 existing + ≥8 new) must pass at the end.

## Bug Hints

The bugs fall into three categories:

- **Concurrency**: Double-checked locking without proper volatile semantics
- **Algorithm**: Inverted comparison in heap sift-up, inverted predicate in sliding window
- **Logic**: Double-counting in statistics, wrong ordering of check-vs-record
