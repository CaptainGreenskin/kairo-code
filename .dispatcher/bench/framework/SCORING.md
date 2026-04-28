# Kairo-Code Benchmark Scoring Rubric

## 评分维度（满分 100）

| 轴 | 权重 | 描述 |
|----|------|------|
| **Test Pass Rate** | 35 | 通过测试数 / 总测试数 × 35 |
| **Edit Precision** | 20 | 首次 edit 成功率（无 old_string 报错） |
| **Autonomous Verify** | 20 | agent 是否主动运行 mvn test（无 hook 注入） |
| **Code Quality** | 15 | 代码整洁度：无 hack、无多余复杂度（人工评估） |
| **Efficiency** | 10 | tool call 效率：每个文件的 tool calls 数（越少越好） |

## 评分标准

### Test Pass Rate（自动）
```
score = (passed / total) * 35
```

### Edit Precision（从日志自动提取）
- 0 edit 失败：20 分
- 1 次失败后修正：12 分
- 2+ 次失败：5 分
- edit 完全失败（任务未完成）：0 分

### Autonomous Verify（从日志自动提取）
- agent 在编辑后自主运行 mvn test：20 分
- 需要 hook 注入才运行：10 分
- 未运行 mvn test：0 分

### Code Quality（人工，0-15）
- 15：简洁、惯用、无多余代码
- 10：功能正确但有冗余或风格问题
- 5：能用但结构混乱
- 0：完全 hack（绕过测试、hardcode 等）

### Efficiency（从日志自动提取）
- ≤ 5 tool calls/file：10 分
- 6-10：7 分
- 11-20：4 分
- \>20：1 分

## 总分等级

| 分数 | 等级 |
|------|------|
| 90-100 | ⭐⭐⭐ 优秀 |
| 75-89  | ⭐⭐ 良好 |
| 60-74  | ⭐ 及格 |
| <60    | ✗ 不合格 |

## 难度级别

| Level | 描述 | 典型任务 | 预期 Claude Code |
|-------|------|----------|-----------------|
| L1    | Bug Fix（3 bugs, 18 tests）| 修复孤立 bug | ~95/100 |
| L2    | Feature Impl（多文件骨架实现）| 实现 EventDispatcher（17 tests）| ~85/100 |
| L3    | Self-Improvement（真实代码库）| kairo-code 新功能，300-500 行 | ~75/100 |
