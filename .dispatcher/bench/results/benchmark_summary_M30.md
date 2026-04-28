# M29-M30 Benchmark Summary — L1-L8 完整历史 + L9 方向规划

**Timestamp:** 2026-04-29
**Milestone:** M31-001

---

## 1. 完整 Benchmark 历史（L1-L8）

| Level | Total | Test Pass | Edit Precision | Auto Verify | Quality | Efficiency | 关键特点 |
|-------|-------|-----------|----------------|-------------|---------|------------|---------|
| L1    | 100   | 35        | 20             | 20          | 15      | 10         | calculator 3-bug |
| L2    | 96    | 35        | 20             | 20          | 14      | 7          | EventDispatcher |
| L3    | 93    | 33        | 18             | 20          | 13      | 9          | self-improvement |
| L4    | 96    | 35        | 18             | 20          | 14      | 9          | 5 cross-file bugs |
| L5    | 99    | 35        | 20             | 20          | 15      | 9          | 5 bugs + create tests |
| L6    | 97    | 35        | 20             | 20          | 14      | 8          | library system |
| L7    | 100   | 35        | 20             | 20          | 15      | 10         | concurrent cache |
| L8    | 100   | 35        | 20             | 20          | 15      | 10         | DCL + algorithm |

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

**L8 (100/100)** — Rate limiter DCL + algorithm 5 bugs + 创建并发测试
- 45/45 tests pass（37 existing + 8 new RateLimiterConcurrentTest）
- 修复：`volatile ConcurrentHashMap` DCL、`removeIf` 反转、MAX-HEAP sift-up `<`、double-count 移除、check-before-record
- 10 turns，全部满分

---

## 2. 饱和区间深度分析

### 分数分布

```
得分
100 |  ● L1 (100)                                        ● L7 (100)  ● L8 (100)
 99 |              ● L5 (99)
 97 |                          ● L6 (97)
 96 |       ● L2 (96)       ● L4 (96)
 93 |             ● L3 (93)
 90 |
    +----------------------------------------------------------
      L1   L2   L3   L4   L5   L6   L7   L8
```

**核心发现：L1-L8 对 qodercli 区分度几乎为零。**

- 最低分：93（L3 self-improvement）
- 最高分：100（L1, L7, L8）
- 区间宽度：仅 7 分
- L5-L8（最高难度 tier）得分 97-100，完全饱和

### 为什么 L8 比 L7 更快（10 turns vs 15）？

L8 引入了两个 L7 未曾覆盖的 bug 维度：**DCL/volatile 语义**和**算法正确性**，但 qodercli 反而更高效。根本原因：每个 bug 都是**局部可识别的模式**（local pattern matching）。

| Bug | 类型 | 识别线索 | 为什么容易 |
|-----|------|---------|-----------|
| Bug 1: DCL `volatile` 缺失 | 并发安全 | `synchronized` 块 + lazy init = 标准 DCL 模式 | volatile + ConcurrentHashMap 是 Java 并发修复的标准答案，训练数据覆盖充分 |
| Bug 2: `removeIf` 反转 | 算法逻辑 | `t >= windowStart` 保留窗口内数据 | 读 Javadoc 即可发现 `removeIf` 是"移除匹配项"，谓词应反过来 |
| Bug 3: sift-up `>` 比较 | 算法逻辑 | siftDown 用 `<` 但 siftUp 用 `>` | 对比同文件内 siftDown 的实现即可推断方向错误 |
| Bug 4: double-count | 逻辑错误 | `successCount++` 在 try 和 finally 各出现一次 | 逐行追踪计数变量的所有修改点即可发现重复 |
| Bug 5: check-before-record | 语义错误 | `recordRequest` 在 `isAllowed` 之前调用 | 语义明确——"先检查再记录"是常识，顺序错误显而易见 |

**结论**：L8 的所有 bug 都可以通过**单文件内的局部推理**解决——不需要跨文件理解约束、不需要全局状态空间推理、不需要追踪资源生命周期。这些是 qodercli 在 L1-L7 中已经反复证明擅长的模式。

---

## 3. L9 设计原则

L9 必须引入 qodercli 在 L1-L8 中从未遇到的真正难点：**非局部约束（non-local invariants）**

### 候选维度

| 候选维度 | 具体场景 | 为什么难 |
|---------|---------|---------|
| **跨类协议约束** | 两个类之间存在隐性的"调用顺序"协议，违反时行为错误但无异常 | 不能靠读单个文件发现——需要理解 A 类的调用者必须在 B 类的某方法之后调用 |
| **复合状态机** | 状态转移依赖多个字段的组合，部分转移路径被遗漏 | 需要全局推理状态空间，不能逐文件修复 |
| **缓存一致性** | 写入 primary store 后缓存未失效，读取返回陈旧值（非线程安全问题） | 单测难以覆盖，需要精心设计的场景组合才能暴露 |
| **幂等性破坏** | 某操作应幂等但实际上第二次调用副作用不同 | 需要推理调用语义，而非简单的数据结构替换 |
| **资源泄漏** | 异常路径下资源（连接/锁）未释放，导致后续调用死锁 | 需要追踪所有异常路径，包括嵌套 try-catch 中的 early return |

### 推荐 L9 方向：跨类协议约束 + 缓存一致性破坏

**理由**：

1. **跨类协议**：需要读多个文件并理解它们之间的隐性契约，不能逐文件修复。例如：`OrderProcessor.process()` 内部调用 `InventoryManager.reserve()`，但 `reserve()` 要求先调用 `InventoryManager.init()`——这个约束没有通过异常或文档显式表达，只有读懂两个类的交互才能发现。

2. **缓存一致性**：写操作修改 primary 后需要同步失效缓存，遗漏时功能测试可能仍通过（返回陈旧数据），需要精心设计的测试才能暴露。例如：`UserService.updateUser()` 更新了数据库但没清除 `UserCache`，导致后续 `getUser()` 返回旧数据。

**预期 qodercli 得分**：70-85 分（首次产生有意义的区分度）

**L9 fixture 设计建议**：
- 3-4 个 Java 文件，至少 2 个类之间存在隐性协议
- 5 个 bug 中至少 2 个需要跨文件理解才能定位
- 缓存一致性 bug 应设计为"功能测试可能通过但返回陈旧数据"
- 创建 ≥10 个新测试，其中至少包含跨类交互场景的测试

---

## 4. M32 规划建议（≥3 条）

```
P1: 创建 L9 fixture — 跨类协议 + 缓存一致性 — 原因: L8 证明局部模式 bug 对 qodercli 无难度。
    L9 需要 non-local invariant，使 qodercli 必须理解多文件间的隐性约束才能正确修复。
    设计要点: (a) 至少 2 个 bug 需要跨文件推理才能定位; (b) 缓存一致性 bug 应返回陈旧数据
    而非抛出异常; (c) 创建 ≥10 个新测试，包含跨类交互场景。预期得分 70-85。

P2: 运行 kairo-code L2/L5/L6/L7/L8 benchmark（需要 KAIRO_CODE_API_KEY）— 原因:
    qodercli 基准线已完整（L1-L8）。获得 API key 后，最优先的实验是 kairo-code vs qodercli 的系统对比，
    量化 MissingTestHintHook 对 GLM-5.1 的实际 ROI，验证 kairo-code 作为 code agent 的成熟度。

P3: 设计 benchmark 独立验证机制 — 原因:
    所有 L5-L8 结果都是 print mode 下的 self-reported 得分。需要设计一种机制，
    在 qodercli 运行结束后，由独立进程（不依赖 qodercli 的输出）重新执行 mvn test
    并与结果文件中的声称得分对比验证。具体方案：在 benchmark runner 中增加一个
    post-execution validation 阶段，自动运行 mvn test 并解析输出，
    将实际 pass/fail 数与结果文件中的声明对比，产生一致性报告。
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
| L8 | `benchmark_20260429_qodercli_l8.md` | M30-001 |
| Previous summaries | `benchmark_summary_M20.md`, `benchmark_summary_M23.md`, `benchmark_summary_M25.md`, `benchmark_summary_M28.md` | M20-M28 |
