# M24-M25 Benchmark Summary — L5/L6 Baseline + MissingTestHintHook ROI

**Timestamp:** 2026-04-28
**Milestone:** M26-002

---

## 1. 新 Hook 矩阵（M25 新增）

| Hook | 目标行为 | 预期受益模型 | 触发模式 |
|------|---------|------------|---------|
| MissingTestHintHook | 检测 `Tests run: 0` / `No tests found`，提示创建缺失测试 | kairo-code (GLM-5.1) | TOOL_RESULT (bash, non-REPL) |

### MissingTestHintHook 详解

- **Milestone**: M25-002
- **触发条件**: Maven 测试输出中出现 `Tests run: 0` 或 `No tests found` 模式
- **注入行为**: 提示 agent 存在未覆盖的类，建议创建对应的测试文件
- **目标场景**: L5/L6 级别 benchmark 中"创建缺失测试"任务
- **对 Claude 模型影响**: 无（qodercli 已自主完成测试创建，L5=99/100）
- **对 GLM-5.1 预期影响**: 弥补其不会主动创建新文件的 reactive 模式缺陷

---

## 2. qodercli Benchmark 完整历史

| Level | Total | Test Pass | Edit Precision | Auto Verify | Quality | Efficiency | 关键特点 |
|-------|-------|-----------|----------------|-------------|---------|------------|---------|
| L1    | 100   | 35        | 20             | 20          | 15      | 10         | 3-bug calculator, 18 tests, 饱和 |
| L2    | 96    | 35        | 20             | 20          | 14      | 7          | EventDispatcher feature impl, 17 tests |
| L3    | 93    | 33        | 18             | 20          | 13      | 9          | TurnMetricsCollector self-improvement, 12 tests |
| L4    | 96    | 35        | 18             | 20          | 14      | 9          | 5 cross-file bugs, 33 tests, SWE-bench caliber |
| L5    | 99    | 35        | 20             | 20          | 15      | 9          | 5 bugs + create TaskValidatorTest, 40 tests (16 new) |
| L6    | **84** | 30        | 12             | 13          | 16      | 5          | library system, 5 bugs + create MemberValidatorTest, 49 tests (print mode issue) |

### 各级别详细得分

**L2 (96/100)** — EventDispatcher 功能实现
- 17/17 tests pass
- `ConcurrentHashMap` + `synchronizedList` 并发实现正确
- 单次实现，minor quality deduction（嵌套 CompletionStageWrapper 类），Efficiency=7（未显式运行 mvn test）

**L4 (96/100)** — 订单系统 5 个跨文件 bug
- 33/33 tests pass
- 修正了误导性的 bug 位置提示（Bug 4 实际在 Product.java 而非 InventoryTracker.java）
- 实现了完整的库存回滚逻辑

**L5 (99/100)** — Task manager 5 bugs + 创建缺失测试
- 40/39 tests pass（超出最低要求，创建了 16 个新测试）
- 测试覆盖：null 输入、边界值、日期验证、优先级验证
- 未修改现有测试文件，edit precision 满分

**L6 (TBD)** — Library system 7 bugs + 创建 MemberValidatorTest
- 38 个现有测试 + ≥10 个新测试 = 48+ 总计
- 涉及领域：图书借阅、LoanPolicy、MemberValidator
- 结果待 M26-001 执行完成后填入

---

## 3. 测试创建维度分析（L5 vs L6）

### 领域差异

| 维度 | L5 — Task Manager | L6 — Library System |
|------|-------------------|---------------------|
| 业务领域 | 任务管理（简单 CRUD） | 图书借阅系统（状态机 + 策略模式） |
| 待测类 | `TaskValidator` — 单输入验证 | `MemberValidator` — 多字段 + 多类型验证 |
| 验证逻辑复杂度 | 低：null/blank 检查 + 日期比较 | 中：email 正则 + enum 校验 + null 守卫 |
| 业务上下文依赖 | 低：Validator 独立运行 | 中：Validator 被 LibraryService 调用 |
| 现有测试文件数 | 2 (TaskRepositoryTest, TaskServiceTest) | 3 (LoanTest, LoanPolicyTest, LibraryServiceTest) |

### 测试覆盖数量对比

| 指标 | L5 | L6 |
|------|----|----|
| 要求最低测试数 | ≥15 | ≥10 |
| qodercli 实际创建 | 16 | TBD |
| 现有测试数 | 24 (10+14) | 38 |
| 总测试数 | 40 | 48+ |

### 测试覆盖质量对比（L5 实测）

L5 TaskValidatorTest 覆盖场景：

| 场景 | 测试数 | 质量评价 |
|------|--------|---------|
| 正常路径（有效 task） | 1 | 基本覆盖 |
| Null 输入 | 1 | Task 级别 null 检查 |
| 标题边界条件 | 3 | null / blank / empty 分别测试 |
| 日期验证 | 4 | null / past / today / future |
| 优先级验证 | 3 | isValidPriority true/false + 全部优先级 |
| 综合覆盖 | 16 | 边界条件 + 异常路径 + 正常路径全覆盖 |

L6 MemberValidatorTest 预期覆盖：

| 场景 | 预期测试数 |
|------|-----------|
| null member | 1 |
| valid member | 1 |
| invalid email (no @, no domain, blank) | 3 |
| valid email | 1-2 |
| null membership type | 1 |
| all valid membership types (BASIC, PREMIUM) | 2 |
| **最低要求** | **≥10** |

### 分析

1. **L6 领域更复杂但测试要求更低**：MemberValidator 有 email 正则和 enum 两个验证维度，但任务只要求 ≥10 tests（vs L5 ≥15）。这可能反映 L6 的区分点不在测试数量，而在 bug 修复复杂度（7 bugs vs 5）。

2. **测试创建质量的关键区分点**：
   - L5: qodercli 超量完成（16 vs ≥15），还增加了 today's date 和 all priority levels 等额外覆盖
   - L6: 需观察是否能同样超量完成，特别是 email 正则的边界情况（特殊字符、子域名等）

3. **领域熟悉度影响有限**：两个领域（task manager / library）都是常见业务模型，不包含特定领域知识。Claude 的训练数据覆盖充分，不应有显著领域差异影响。

---

## 4. Benchmark 难度曲线评估

### qodercli 难度曲线

```
得分
100 |  ● L1 (100)
 99 |        ● L5 (99)
 96 |     ● L2 (96)     ● L4 (96)
 93 |        ● L3 (93)
 90 |
    +--------------------------
      L1   L2   L3   L4   L5
```

### 饱和点分析

**qodercli 在 L1-L5 范围内未出现饱和**，但有平台期特征：

- **L1 (100)**: 饱和——3 bug 单文件场景已达上限，无提升空间
- **L2-L4 (93-96)**: 平台期——cross-file 推理、feature impl、multi-bug fix 都稳定在 93-96 分，波动在 3 分内
- **L5 (99)**: 反常上升——添加"创建测试"维度后反而提分，说明 qodercli 在测试编写方面表现优于纯 bug fix

**结论**：L1-L5 对 qodercli (Claude) 区分度不足。L3 (93) 是唯一明显下降点，但原因可能是 self-improvement 场景（修改自己代码库）的复杂度，而非难度本身。

### L7 设计建议

要区分 Claude 级别模型，L7 需要引入 qodercli 当前分数未覆盖的能力维度：

| 候选维度 | 预期 qodercli 得分 | 区分依据 |
|---------|-------------------|---------|
| **并发/线程安全** | ~75-85 | race condition 检测、死锁分析、线程安全数据结构选择 |
| **性能优化** | ~70-80 | O(n²)→O(n log n) 重构、内存泄漏修复、stream 优化 |
| **多模块重构** | ~75-85 | 跨模块依赖重组、API 破坏性变更 + 消费者迁移 |
| **安全漏洞修复** | ~65-75 | SQL 注入、XSS、权限绕过、密码学误用 |
| **模糊需求理解** | ~70-80 | 含矛盾约束的任务描述、隐性需求推断 |

### 推荐 L7 方向：**并发/线程安全 + 性能优化组合**

理由：
1. L4 已经验证了 cross-file bug fix 能力（96 分），但所有 bug 都是单线程逻辑错误
2. L6 增加了测试创建维度，但 bug 本身仍是单线程的
3. 并发 bug 是 SWE-bench 中最难的一类——需要理解 happens-before 关系、内存模型、锁粒度

---

## 5. M27 规划建议（≥3 条）

```
P1: 运行 qodercli L6 benchmark 并出分 — 原因: M26-001 已在运行，L6 是首个同时包含 "7 bugs + 测试创建" 的级别，
    将揭示测试创建能力是否与 bug 数量正相关。若 L6 ≥ 95，证明 qodercli 在多维度任务上无衰减，
    L7 设计必须引入全新维度（并发/安全）才能产生区分度。

P2: 创建 L7 fixture — 并发 bug 修复 + 性能优化 — 原因: L1-L5 得分区间仅 7 分（93-100），无法区分顶级模型。
    L7 应引入线程安全（如 ConcurrentHashMap 误用、volatile 缺失、竞态条件）和性能（如 O(n²) 循环、
    未关闭资源）维度，预期 qodercli 降至 75-85 分，为其他模型留下区分空间。

P3: 量化 MissingTestHintHook 对 kairo-code 的 ROI — 原因: 当前所有 benchmark 数据来自 qodercli (Claude)，
    MissingTestHintHook 的目标用户是 kairo-code (GLM-5.1)。需要运行 kairo-code L5/L6 before/after 对比实验，
    测量该 hook 是否显著提升 GLM-5.1 的 Test Pass Rate 和 Edit Precision。
    若 GLM-5.1 本身无测试创建能力，hook 仅提示不足以弥补——可能需要更激进的 TaskCreationHook 方案。
```

### 附加建议

**P4: 更新 L4 fixture Bug 4 位置** — L4 report 明确指出 Bug 4 设计在 `InventoryTracker.java` 但实际在 `Product.releaseStock()`，
导致 "Do not modify Product.java" 约束被违反。虽然 qodercli 正确处理了此矛盾（追根溯源而非盲从提示），
但 fixture 设计缺陷影响 benchmark 可比性。

---

## Appendix: Data Sources

| Report | File |
|--------|------|
| qodercli L1 v1 | `results/benchmark_20260429_qodercli-v1.md` |
| qodercli L2 | `results/benchmark_20260429_qodercli_l2.md` |
| qodercli L3 | Commit `ca5bf94` — task-m17-002 |
| qodercli L4 | `results/benchmark_20260429_qodercli_l4.md` |
| qodercli L5 | `results/benchmark_20260429_qodercli_l5.md` |
| qodercli L6 | TBD (M26-001 running) |
| M20 Summary | `results/benchmark_summary_M20.md` |
| M23 Summary | `results/benchmark_summary_M23.md` |
| L6 task spec | `tasks/l6-fix-all.md` |
| MissingTestHintHook | `kairo-code-core/src/main/java/io/kairo/code/core/hook/MissingTestHintHook.java` |
