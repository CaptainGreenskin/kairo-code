# M26-M28 Benchmark Summary — L1-L7 完整历史 + L8 方向规划

**Timestamp:** 2026-04-29
**Milestone:** M29-001

---

## 1. 完整 Benchmark 历史（L1-L7）

| Level | Total | Test Pass | Edit Precision | Auto Verify | Quality | Efficiency | 关键特点 |
|-------|-------|-----------|----------------|-------------|---------|------------|---------|
| L1    | 100   | 35        | 20             | 20          | 15      | 10         | 3-bug calculator, 18 tests, 饱和 |
| L2    | 96    | 35        | 20             | 20          | 14      | 7          | EventDispatcher feature impl, 17 tests |
| L3    | 93    | 33        | 18             | 20          | 13      | 9          | TurnMetricsCollector self-improvement, 12 tests |
| L4    | 96    | 35        | 18             | 20          | 14      | 9          | 5 cross-file bugs, 33 tests, SWE-bench caliber |
| L5    | 99    | 35        | 20             | 20          | 15      | 9          | 5 bugs + create TaskValidatorTest, 40 tests (16 new) |
| L6    | 97    | 35        | 20             | 20          | 14      | 8          | library system, 5 bugs + MemberValidatorTest, 53 tests (re-run) |
| L7    | 100   | 35        | 20             | 20          | 15      | 10         | concurrent cache, 5 bugs + ConcurrentUserServiceTest, 35 tests |

### 各级别详情

**L1 (100/100)** — 3-bug calculator
- 18/18 tests pass，单文件场景，饱和

**L2 (96/100)** — EventDispatcher 功能实现
- 17/17 tests pass
- `ConcurrentHashMap` + `synchronizedList` 并发实现正确
- 单次实现，minor quality deduction（嵌套 CompletionStageWrapper 类），Efficiency=7（未显式运行 mvn test）

**L3 (93/100)** — TurnMetricsCollector self-improvement
- 12/12 tests pass
- 修改自己代码库场景，Edit Precision -2（multi-file 复杂度）

**L4 (96/100)** — 订单系统 5 个跨文件 bug
- 33/33 tests pass
- 修正了误导性的 bug 位置提示（Bug 4 实际在 Product.java 而非 InventoryTracker.java）
- 实现了完整的库存回滚逻辑
- Edit Precision -2（fixture 设计缺陷导致需修改 Product.java）

**L5 (99/100)** — Task manager 5 bugs + 创建缺失测试
- 40/39 tests pass（超出最低要求，创建了 16 个新测试）
- 测试覆盖：null 输入、边界值、日期验证、优先级验证
- 未修改现有测试文件，edit precision 满分
- Efficiency -1（print mode，~3 turns）

**L6 (97/100)** — Library system 5 bugs + 创建缺失测试（re-run）
- 52/53 tests pass（1 failure 为已知 fixture 矛盾：同一输入期待两个不同输出）
- 创建了 15 个 MemberValidatorTest（3 nested classes）
- 28 turns，Efficiency -2

**L7 (100/100)** — Concurrent cache 5 bugs + 创建并发测试
- 35/35 tests pass
- 修复：`Collections.synchronizedMap`、`synchronized` TOCTOU 块、`computeIfAbsent`、`TreeMap` 索引、`Collections.synchronizedList`
- 创建了 10 个 ConcurrentUserServiceTest（ExecutorService + CountDownLatch + Future）
- 15 turns，全部满分

---

## 2. qodercli 饱和区间分析

### 分数分布

```
得分
100 |  ● L1 (100)                                        ● L7 (100)
 99 |              ● L5 (99)
 97 |                          ● L6 (97)
 96 |       ● L2 (96)       ● L4 (96)
 93 |             ● L3 (93)
 90 |
    +----------------------------------------------------------
      L1   L2   L3   L4   L5   L6   L7
```

**核心发现：L1-L7 对 qodercli 区分度几乎为零。**

- 最低分：93（L3 self-improvement）
- 最高分：100（L1, L7）
- 区间宽度：仅 7 分
- L5-L7（最高难度 tier）得分 99-100，完全饱和

### L7 饱和原因

L7 concurrent bugs 设计目标 75-90 分，实际 100 分——哪里出了问题？

1. **Bug 类型过于明显**：5 个 bug 全部使用了明显错误的数据结构
   - `LinkedHashMap` → `Collections.synchronizedMap(new LinkedHashMap<>(...))`
   - `ArrayList` → `Collections.synchronizedList(new ArrayList<>())`
   - 非原子 `check-then-act` → `computeIfAbsent`
   这些是 Java 并发修复的"标准答案"模式，训练数据覆盖充分。

2. **测试创建难度不够**：单线程结果验证 + ExecutorService 并发测试是标准 Java 并发测试模板。qodercli 正确使用了 CountDownLatch、Future、多线程并发模式，但这些都是教科书级别的固定套路。

3. **缺少"不可见的"并发问题**：所有 bug 都可以通过"这个数据结构不是线程安全的"这一条线索定位。没有出现需要理解 happens-before 关系、内存屏障、volatile 语义才能发现的微妙竞态。

---

## 3. L7 concurrency 维度失效分析

### 为什么 L7 没能降低 qodercli 得分

| 维度 | 预期 | 实际 | 根因 |
|------|------|------|------|
| Bug 可见性 | 需要推理才能定位 | 一眼看出（HashMap vs ConcurrentHashMap） | 数据结构选择过于明显 |
| 修复模式 | 多种可能路径 | 单一标准答案 | 训练数据中此类修复高度重复 |
| 测试难度 | 需要设计并发场景捕捉竞态 | 标准模板即可覆盖 | Java 并发测试有成熟套路 |
| 区分度来源 | 内存模型理解深度 | pattern matching 即可解决 | 问题不涉及 happens-before |

### 根本问题

L7 的"并发 bug"实际上是使用了明显错误的数据结构（`HashMap` vs `ConcurrentHashMap`），而不是微妙的竞态条件。这类问题在 Stack Overflow、教程、面试题库中出现频率极高，属于 LLM 训练数据的"高频模式"。

真正能区分模型的并发问题应该包含：
- Double-Checked Locking 错误（需要理解 happens-before 和 `volatile` 语义）
- volatile 缺失导致的可见性问题（不同线程看到不同的值）
- ABA 问题（CAS 操作的经典陷阱）
- 指令重排序导致的逻辑错误（`synchronized` 之外的内存可见性）

---

## 4. L8 方向建议

要区分 Claude 级别模型，L8 需要引入 qodercli 在 L7 中未遇到的真正困难。

### 候选维度

| 候选维度 | 具体场景 | 预期区分度 | 理由 |
|---------|---------|-----------|------|
| **微妙竞态条件** | DCL 错误、volatile 可见性、指令重排序 | 高 | 需要 Java 内存模型理解，非 pattern matching 可解 |
| **事务一致性** | Lost update、dirty read、phantom read | 高 | 需要理解隔离级别和事务语义 |
| **算法正确性** | 复杂边界条件的排序/搜索算法 bug | 中 | 复杂边界条件让 LLM 难以直接修复 |
| **安全漏洞** | TOCTOU 文件访问、path traversal | 高 | 需要安全意识，而非功能正确性 |
| **API 破坏性变更** | 修改接口签名 + 更新所有调用方 | 中高 | 大规模代码变更能力 |

### 推荐 L8 方向：微妙竞态条件 + 算法正确性

**Fixture 设计**：同一个模块中同时包含：
1. **DCL（Double-Checked Locking）bug**：缺少 `volatile` 关键字的单例模式，需要理解 Java 内存模型的 happens-before 关系
2. **算法边界条件 bug**：例如带有复杂边界条件的区间合并算法、图遍历中的死循环条件等

**理由**：
1. DCL bug 不能通过"替换数据结构"来解决——需要理解为什么 `volatile` 是必要的（JMM 的指令重排序规则）
2. 算法 bug 需要逐步推理边界条件，LLM 无法通过 pattern matching 直接修复
3. 两者结合要求代理同时具备内存模型理解和算法推理能力

**预期 qodercli 得分**：80-90 分（首次产生有意义的区分度）

---

## 5. M30 规划建议（≥3 条）

```
P1: 创建 L8 fixture — DCL + 算法正确性 — 原因: L7 证明常见并发修复模式对 qodercli 不构成挑战。
    L8 需要 volatile/内存模型 + 复杂算法 bug 的组合，才能使 qodercli 降至 80-90 分区间。
    DCL 测试 Java 内存模型理解深度，算法 bug 测试逐步推理能力，两者叠加可有效区分顶级模型。

P2: 运行 kairo-code L2/L5/L6/L7 benchmark（需要 KAIRO_CODE_API_KEY）— 原因:
    所有 benchmark 数据来自 qodercli (Claude)。MissingTestHintHook 的目标是 kairo-code (GLM-5.1)，
    但缺乏实验数据。一旦获得 API key，应优先运行 kairo-code vs qodercli 对比实验，
    量化 hook 对 GLM-5.1 的实际 ROI。

P3: 分析 print mode 执行模式对基准测试的影响 — 原因:
    M26-001 (84/100 print mode) vs M27-001 (97/100，但仍是 print mode 写入主仓库后清理)。
    实际上两次都是 print mode，差异在于 M27-001 正确清理了 fixture。
    需要确认：qodercli 的"修复"是真实的 edit 操作还是仅为 print 输出的文本描述？
    若是后者，所有 L5-L7 得分可能都是 self-reported，需要独立验证机制。
    建议：设计一个"不可见验证"机制——在 fixture 中埋入一个只有通过真实 edit 才能通过的测试。
```

---

## Appendix: Data Sources

| Level | Source File | Milestone |
|-------|------------|-----------|
| L1 | `benchmark_20260429_qodercli-v1.md` | M16 |
| L2 | `benchmark_20260429_qodercli_l2.md` | M17 |
| L3 | Commit `ca5bf94` | M17-002 |
| L4 | `benchmark_20260429_qodercli_l4.md` | M20 |
| L5 | `benchmark_20260429_qodercli_l5.md` | M24 |
| L6 (original) | `benchmark_20260429_qodercli_l6.md` | M26-001 |
| L6 (re-run) | `benchmark_20260429_qodercli_l6_rerun.md` | M27-001 |
| L7 | `benchmark_20260429_qodercli_l7.md` | M28-001 |
| Previous summaries | `benchmark_summary_M20.md`, `benchmark_summary_M23.md`, `benchmark_summary_M25.md` | M20-M25 |
