# Cross-Executor Benchmark Report

**Timestamp:** 20260428
**Task:** Fix 3 Java bugs (RateLimiter, Cache, StringUtils)
**Total tests:** 18
**kairo-code version:** 0.2.0-SNAPSHOT (M12 improvements applied)
**Model:** GLM-5.1

## Summary

| Metric | kairo-code | qodercli |
|--------|-----------|----------|
| Exit code | 0 (但未修复) | pending |
| Duration | ~22 min | pending |
| Tests passed | 0/18 | pending |
| Tool calls / steps | 9 (3 edit + 6 bash) | pending |
| Edit success | ❌ edits failed silently | pending |

## Verdict

| Criteria | kairo-code | qodercli |
|----------|-----------|---------|
| Tests fixed | ❌ 0/18 | pending |
| Files modified | ❌ 0 | pending |
| Speed | ❌ 22 min | pending |

## Root Cause Analysis

kairo-code 调用了 3 次 edit 工具（每个 buggy 文件各 1 次），但文件内容未变化。
GLM-5.1 日志：`实际文件内容与 read 工具显示的不同` — 说明 edit 的 old_string 匹配失败。

**已知问题：**
- edit 工具要求 `old_string` 精确匹配文件内容
- GLM-5.1 在生成 old_string 时可能引入空格/缩进差异
- 需要在 system prompt 中加入 edit 工具使用指南

## Next Steps (M13 follow-up)

1. **M13-006**：system prompt 中加入 edit 工具使用规范（先 read 再 edit，确认 old_string 精确匹配）
2. **M13-007**：对 edit 失败给出明确错误消息，引导 agent 重试

## Logs

- **kairo-code log:** `.dispatcher/logs/kairo-code-task-m13-001-benchmark-run-20260428-203821.log`
