# L5: Fix All Bugs + Create Missing Tests

The task management system has several bugs and missing test coverage.

## Instructions

1. Run `mvn test` to see current failures.

2. Fix all bugs so existing tests pass. The bugs are spread across these files:
   - `src/main/java/com/example/TaskRepository.java`
   - `src/main/java/com/example/TaskValidator.java`
   - `src/main/java/com/example/TaskService.java`

3. Create `src/test/java/com/example/TaskValidatorTest.java` — `TaskValidator` has
   no test coverage. Write **at least 10 tests** covering:
   - null task → throws IllegalArgumentException
   - valid task → no exception
   - null title → throws IllegalArgumentException
   - blank title → throws IllegalArgumentException
   - null priority → throws IllegalArgumentException
   - past due date → throws IllegalArgumentException
   - future due date → no exception
   - null due date → no exception (optional due date is allowed)
   - `isValidPriority()` returns true for valid priorities
   - `isValidDueDate()` returns true for future/null dates

4. Run `mvn test` again to confirm all tests pass (including your new ones).

## Constraints

- Do **not** modify existing test files (`TaskRepositoryTest.java`, `TaskServiceTest.java`)
- Do **not** modify `pom.xml`
- All 39 tests (24 existing + 15 new) must pass at the end
