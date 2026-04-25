---
name: test-writer
version: 1.0.0
category: CODE
triggers:
  - "write tests"
  - "add tests"
  - "/test"
---
# Test Writer

When asked to write or extend tests, follow this discipline:

1. **Read the SUT first.** Don't write tests against an imagined API.
2. **Match the project's existing test style.** Look at sibling tests for naming, framework, fixture conventions.
3. **One assertion per concern.** Multiple `assertThat` per test is fine; multiple unrelated concerns is not.
4. **Cover the contract, not the implementation.** Test inputs/outputs and observable side effects, not private helpers.
5. **Edge cases worth writing:** empty input, null, boundary values, concurrent calls if the SUT is shared, error paths.
6. **Avoid mocks where a real object works.** Mock only at I/O boundaries (network, filesystem you don't own, the model provider).
7. **Run the tests.** Don't claim success without execution evidence.

If the SUT lacks testability seams, say so before adding workarounds.
