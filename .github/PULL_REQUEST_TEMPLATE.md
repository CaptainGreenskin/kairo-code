<!--
Thanks for the PR! A few things to check before submitting:

- [ ] Targets `main` branch
- [ ] Includes tests for any new behavior
- [ ] Updates CHANGELOG.md if user-visible
- [ ] `mvn spotless:apply` run (or your IDE applies Google Java Format)
- [ ] If touching kairo upstream too, link the companion upstream PR below
-->

## Summary

<!-- 1-3 sentences. What changes, why. The reviewer should be able to
understand the PR from this alone. -->

## Reverse-downstream check

<!-- Does this introduce a generic capability that any Kairo agent would
benefit from? If yes, the work should land in the upstream kairo repo first
(or in this PR alongside a companion upstream PR). See CONTRIBUTING.md.

If this is purely kairo-code-specific (e.g. a REPL command, a CLI flag,
docs), tick "N/A". -->

- [ ] N/A — kairo-code-specific
- [ ] Upstream PR opened: <https://github.com/captaingreenskin/kairo/pull/...>
- [ ] Already on upstream main

## Test plan

<!-- How did you verify it works? List the commands, what you observed,
and if there are flows that the test suite doesn't cover, what you
manually tested. -->

```
mvn test -pl ...
```

## Screenshots / output

<!-- For UI changes, before/after screenshots. For CLI changes, a transcript. -->

## Related issues

<!-- Closes #123 -->
