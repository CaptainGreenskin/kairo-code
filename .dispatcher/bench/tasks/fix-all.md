# Fix 3 Java Bugs

This Maven project has 3 buggy Java classes. Fix ALL of them so every test passes.

## Instructions

1. Run `mvn test` to see which tests fail.
2. Read each source file under `src/main/java/com/example/` and find the bug.
3. Fix each bug using the Edit tool.
4. Run `mvn test` again to verify ALL tests pass.

## Files to fix

- `src/main/java/com/example/RateLimiter.java` — token bucket rate limiter
- `src/main/java/com/example/Cache.java` — TTL-based cache with expiry
- `src/main/java/com/example/StringUtils.java` — string utility methods

## Rules

- Do NOT modify any test files.
- Do NOT change pom.xml or the package structure.
- Success = `mvn test` exits with code 0 (all 18 tests pass).
