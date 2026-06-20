---
name: optimize
version: 1.0.0
category: CODE
triggers:
  - "optimize"
  - "performance"
  - "/optimize"
---
# Performance Optimization

When asked to optimize code, follow this systematic approach:

1. **Measure first** — never optimize without profiling. Identify the actual bottleneck.
2. **Analyze** — is it CPU-bound, I/O-bound, or memory-bound?
3. **Prioritize** — fix the biggest bottleneck first. 80/20 rule applies.
4. **Implement** — make one change at a time so you can measure the impact.
5. **Benchmark** — compare before/after with realistic data.

Common patterns:
- **N+1 queries** → batch or join
- **Repeated computation** → cache or memoize
- **Large allocations in hot loops** → object pooling or pre-allocation
- **Synchronous I/O** → async/parallel
- **String concatenation in loops** → StringBuilder

Rules:
- Readability > micro-optimization. Only sacrifice clarity for measurable gains.
- Always show before/after numbers when claiming an improvement.
