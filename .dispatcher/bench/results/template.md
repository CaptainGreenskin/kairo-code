# Cross-Executor Benchmark Report

**Timestamp:** {{TIMESTAMP}}
**Task:** Fix 3 Java bugs (RateLimiter, Cache, StringUtils)
**Total tests:** 18

## Summary

| Metric | kairo-code | qodercli |
|--------|-----------|----------|
| Exit code | {{KAIRO_EXIT}} | {{QODER_EXIT}} |
| Duration | {{KAIRO_DURATION}} | {{QODER_DURATION}} |
| Tests passed | {{KAIRO_TESTS_PASSED}}/{{KAIRO_TESTS_TOTAL}} | {{QODER_TESTS_PASSED}}/{{QODER_TESTS_TOTAL}} |
| Tool calls / steps | {{KAIRO_STEPS}} | {{QODER_STEPS}} |
| Total tokens | {{KAIRO_TOKENS}} | {{QODER_TOKENS}} |
| Est. cost (USD) | {{KAIRO_COST}} | N/A |

## Verdict

| Criteria | Winner |
|----------|--------|
| Tests fixed | _(fill manually)_ |
| Speed | _(fill manually)_ |
| Token efficiency | _(fill manually)_ |

## Logs

- **kairo-code log:** {{KAIRO_LOG}}
- **qodercli log:** {{QODER_LOG}}

## Notes

- kairo-code runs with `--verbose --show-usage` for metrics on stderr.
- qodercli runs with `--dangerously-skip-permissions` (headless).
- Timeout per agent: 300s (configurable via `BENCH_TIMEOUT`).
