# M21-M23 Benchmark Summary — Hook ROI Analysis + L5 Baseline Planning

**Timestamp:** 2026-04-29
**Milestone:** M24-002

---

## 1. Hook 成果矩阵

| Hook | Milestone | 目标行为 | 预期分轴影响 |
|------|-----------|---------|------------|
| TestFailureFeedbackHook | M21 | test failure 后注入结构化诊断上下文 | Efficiency +, Autonomous Verify + |
| AutoCommitOnSuccessHook | M22 | BUILD SUCCESS 后提示 commit | Autonomous Verify ++ |
| StaleReadDetectorHook | M22 | 重复读取同一文件时发出警告 | Efficiency + |
| SessionResultWriterHook | M23 | session 结束写入机读结果文件（KAIRO_SESSION_RESULT.json） | 基础设施（非评分） |

### Hook 详解

**TestFailureFeedbackHook (M21)**
- 当 `mvn test` 失败时，自动捕获失败测试的诊断信息（堆栈、期望值、实际值）
- 注入下一轮 agent 上下文，避免 agent 盲目重试
- 目标模型：GLM-5.1（缺乏自主诊断能力）；对 Claude 模型影响较小

**AutoCommitOnSuccessHook (M22)**
- 检测到 BUILD SUCCESS 后，提示 agent 运行 `git commit`
- 弥补 GLM-5.1 不会主动提交代码的行为缺陷
- 对 Claude 模型：部分情况下已自主提交，hook 作为安全网

**StaleReadDetectorHook (M22)**
- 跟踪文件读取历史，重复读取同一文件时发出警告
- 减少 agent 在无效路径上浪费 tool calls
- 对 Efficiency 轴有直接正向影响

**SessionResultWriterHook (M23)**
- session 结束时自动写入 `KAIRO_SESSION_RESULT.json`
- 提供机读格式的任务完成状态、得分、耗时等元数据
- 为 benchmark 自动化提供数据基础设施，不直接影响评分

---

## 2. qodercli benchmark 历史得分

| Level | Total | Test Pass | Edit Precision | Auto Verify | Quality | Efficiency |
|-------|-------|-----------|----------------|-------------|---------|------------|
| L1    | 100   | 35        | 20             | 20          | 15      | 10         |
| L2    | 96    | 35        | 18             | 20          | 14      | 9          |
| L3    | 93    | 33        | 18             | 20          | 13      | 9          |
| L4    | 96    | 35        | 18             | 20          | 14      | 9          |
| L5    | **99** | 35        | 20             | 20          | 15      | 9          |

### 得分趋势说明

- **L1 (100)**: 3-bug calculator，18 tests — 饱和，无提升空间
- **L2 (96)**: EventDispatcher feature implementation，17 tests — 单次实现，minor quality deduction
- **L3 (93)**: TurnMetricsCollector self-improvement，12 tests — 真实代码库，复杂度略高
- **L4 (96)**: Order system 5-bug，33 tests — cross-file 推理能力强
- **L5 (99)**: Task manager 5-bug + create TaskValidatorTest.java，40 tests (24+16 new) — 超出 L4，测试创建无损耗

---

## 3. L5 难度分析

### L5 vs L4 新增维度

| 维度 | L4 | L5 |
|------|----|----|
| 任务类型 | 纯 bug fix | bug fix + 测试创建 |
| 测试文件创建 | 不需要 | 必须创建 `TaskValidatorTest.java` (≥15 tests) |
| 总测试数 | 33 | 39 (24 + 15) |
| 文件数 | 4 main + 4 test | 3 main + 2 test (1 existing + 1 new) |
| 测试类数量 | 4 | 3 (TaskRepositoryTest + TaskServiceTest + TaskValidatorTest) |

### L5 独特挑战

1. **测试创建能力**：agent 必须理解 `TaskValidator` 的业务逻辑，从零编写覆盖边界条件、异常路径、正常路径的测试类。这比纯 bug fix 要求更高的领域理解力。

2. **测试数量要求**：≥15 tests，覆盖 null 输入、边界值、异常类型、优先级验证、日期验证等多维度场景。

3. **双重验证**：不仅修复现有 24 个测试的 bug，还要确保新写的 15 个测试通过——双重正确性要求。

### 预期得分变化

| 场景 | Test Pass | Edit Precision | Auto Verify | Quality | Efficiency | Total |
|------|-----------|----------------|-------------|---------|------------|-------|
| 测试创建成功 | 35 | 18-20 | 20 | 12-14 | 7-9 | **92-98** |
| 测试部分通过 | 25-30 | 15-18 | 10-20 | 10-12 | 5-7 | **65-85** |
| 测试创建失败 | 20-25 | 10-15 | 0-10 | 8-10 | 3-5 | **41-55** |

**关键观察**：L5 引入了 Test Pass Rate 和 Edit Precision 的耦合风险——如果新测试写错，不仅 Test Pass 扣分，Edit Precision 也会因反复修改而下降。这是 L4 纯 bug fix 场景不存在的风险。

---

## 4. Hook ROI 综合分析

### 各 Hook 对评分轴的影响

| Hook | Efficiency | Auto Verify | Quality | 实际 ROI |
|------|-----------|-------------|---------|---------|
| TestFailureFeedbackHook | + | + | — | **中** — GLM-5.1 收益大，Claude 收益小 |
| AutoCommitOnSuccessHook | — | ++ | — | **中** — 行为提示，不直接影响技术得分 |
| StaleReadDetectorHook | + | — | — | **低-中** — 减少无效 tool calls，影响有限 |
| SessionResultWriterHook | — | — | — | **高（基础设施）** — 自动化 benchmark 的前提 |

### ROI 结论

1. **SessionResultWriterHook 是最高 ROI 的基础设施**——它使 benchmark 数据自动采集成为可能，后续所有分析依赖于此。

2. **TestFailureFeedbackHook 对 GLM-5.1 价值显著**——结构化失败诊断可以减少 agent 盲目重试，直接改善 Efficiency 和 Autonomous Verify 得分。但对 Claude 模型影响有限（模型本身具备较强的自主诊断能力）。

3. **AutoCommitOnSuccessHook 是行为引导而非能力提升**——它不改善代码质量或正确性，只确保工作产物被保存。对 benchmark 评分影响间接。

4. **StaleReadDetectorHook 是最轻量的优化**——仅在特定模式（重复读取）下触发，影响面窄但成本低。

### 与 M20 总结的延续分析

M20 总结指出 kairo-code 的 hook SPI 是"reactive"模式——在模型缺陷出现后补救。M21-M23 延续了这一模式：

- **M15 PostBatchEditVerifyHook** → 强迫 GLM-5.1 验证测试（修复 17/18 → 18/18）
- **M21 TestFailureFeedbackHook** → 提供失败诊断上下文（减少重试次数）
- **M22 AutoCommitOnSuccessHook** → 行为引导（确保工作保存）
- **M22 StaleReadDetectorHook** → 效率优化（减少无效操作）

**核心洞察不变**：qodercli/Claude 需要的 hook 远少于 GLM-5.1，因为模型原生具备工具使用纪律和自主验证能力。

---

## 5. M25 规划建议

### P1: 运行 kairo-code L2/L4/L5 benchmark（前提：API key）

**原因**: M20 总结至今，kairo-code 的 L2/L3/L4/L5 数据仍为 TBD。没有 kairo-code 的多级别基线数据，无法量化 GLM-5.1 vs Claude 在非平凡任务上的差距，也无法判断哪些 hook 真正必要。L5 特别有价值——它是首个要求测试创建的级别，能区分纯 bug fix 能力和全面开发能力。

**预期**：
- L2 (EventDispatcher): ~75-85/100
- L4 (Order system): ~50-70/100
- L5 (Task manager + tests): ~40-60/100（测试创建是 GLM-5.1 的薄弱环节）

### P2: 创建 L6 fixture（多文件重构 + API 设计变更）

**原因**: L4 (96) 和 L2 (96) 对 qodercli 已接近饱和，L5 预计也能达到 92+。需要更难的级别来区分顶级模型的能力。L6 应引入：
- API 破坏性变更（方法签名变更、接口扩展）
- 多文件协调重构（至少 5 个文件需要同步修改）
- 向后兼容性约束（不能破坏现有 public API 契约）
- 性能/复杂度要求（例如 O(n²) → O(n log n) 优化）

**预期分数**: qodercli ~80-90/100，kairo-code ~30-50/100

### P3: 创建 TaskCreationHook（检测"缺失文件"模式时提示）

**原因**: L5 引入了"创建缺失测试文件"的要求，这是首次需要 agent 从零创建文件而非修改现有文件。如果 kairo-code/GLM-5.1 在此任务上表现不佳（预计 Test Pass Rate ≤ 60%），则 TaskCreationHook 可以检测到"缺失测试类"模式并引导 agent 创建测试骨架。这延续了 kairo-code 的 reactive hook 策略——在模型能力缺口处提供补偿。

**行为设计**：
- 检测 `mvn test` 输出中的 "Tests run: 0" 或 "No tests found" 模式
- 提示 agent：检测到未覆盖的类，建议创建测试文件
- 提供测试模板骨架（JUnit 5 + 常见断言）

---

## Appendix: Data Sources

| Report | File |
|--------|------|
| qodercli L1 v1 (18/18) | `results/benchmark_20260429_qodercli-v1.md` |
| qodercli L2 (96/100) | `results/benchmark_20260429_qodercli_l2.md` |
| qodercli L3 (93/100) | Commit `ca5bf94` — task-m17-002 completion |
| qodercli L4 (96/100) | `results/benchmark_20260429_qodercli_l4.md` |
| L5 task spec | `tasks/l5-fix-all.md` |
| M20 Summary | `results/benchmark_summary_M20.md` |
| Scoring rubric | `framework/SCORING.md` |
| Benchmark framework | `framework/README.md` |
