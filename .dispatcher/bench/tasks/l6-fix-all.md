# L6: Fix All Bugs + Create Missing Tests

The library lending system has several bugs and missing test coverage.

## Instructions

1. Run `mvn test` to see current failures.

2. Fix all bugs so existing tests pass. The bugs are spread across these files:
   - `src/main/java/com/example/Loan.java`
   - `src/main/java/com/example/LoanPolicy.java`
   - `src/main/java/com/example/LibraryService.java`

3. Create `src/test/java/com/example/MemberValidatorTest.java` — `MemberValidator` has
   no test coverage. Write **at least 10 tests** covering:
   - null member → throws IllegalArgumentException
   - valid member → no exception
   - invalid email formats (no @, no domain, blank) → throws or returns false
   - valid email → no exception / returns true
   - null membership type → throws or returns false
   - all valid membership types (BASIC, PREMIUM) → no exception / returns true

4. Run `mvn test` again to confirm all tests pass (including your new ones).

## Constraints

- Do **not** modify existing test files (`LoanTest.java`, `LoanPolicyTest.java`, `LibraryServiceTest.java`)
- Do **not** modify `pom.xml`
- All 48+ tests (38 existing + ≥10 new) must pass at the end
