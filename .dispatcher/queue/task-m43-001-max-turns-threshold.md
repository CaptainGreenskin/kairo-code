---
Status: DONE
Priority: P0
Project: kairo-code
Module: kairo-code-core
Executor: qodercli
Title: M43 — MaxTurnsGuardHook 阈值提升 (warnAt 15→20, forceAt 20→30)
---

# M43-001：MaxTurnsGuardHook 阈值提升

## Background

M42 benchmark 显示：PostBatchEditVerifyHook（M40 修复后正式生效）导致每次文件编辑后额外触发 `mvn test`，
消耗更多 turns。L7（3文件）和 L10（5文件）都在 turn 20 被 MaxTurnsGuardHook forceAt 强制提交，
test file 还没创建就结束了。

M41 vs M42 对比：
- M41（PostBatchEditVerifyHook 未生效）：L7 ~72（创建了测试文件）
- M42（PostBatchEditVerifyHook 生效）：L7 ~35（未创建测试文件）—— **regression**

Turn 预算：
- L7：3 文件 × ~5 turns = 15 turns 修 bug + 5 turns 创建测试 = 20 total → forceAt=20 恰好卡住
- L10：5 文件 × ~5 turns = 25 turns 修 bug → forceAt=20 根本不够

## Task

### 1. 修改 `MaxTurnsGuardHook.java`

文件：`kairo-code-core/src/main/java/io/kairo/code/core/hook/MaxTurnsGuardHook.java`

修改 `MaxTurnsGuardHook(TurnMetricsCollector metrics)` 无参构造函数的默认值：

```java
// BEFORE:
public MaxTurnsGuardHook(TurnMetricsCollector metrics) {
    this(metrics, 15, 20);
}

// AFTER:
public MaxTurnsGuardHook(TurnMetricsCollector metrics) {
    this(metrics, 20, 30);
}
```

同时更新 Javadoc 注释：
```java
// BEFORE:
 *   <li><b>warn</b> (default 15 turns): injects a soft wrap-up hint</li>
 *   <li><b>force</b> (default 20 turns): injects a hard stop with commit instruction</li>

// AFTER:
 *   <li><b>warn</b> (default 20 turns): injects a soft wrap-up hint</li>
 *   <li><b>force</b> (default 30 turns): injects a hard stop with commit instruction</li>
```

### 2. 更新 `MaxTurnsGuardHookTest.java`

文件：`kairo-code-core/src/test/java/io/kairo/code/core/hook/MaxTurnsGuardHookTest.java`

找到所有使用 `warnAt=15`、`forceAt=20` 硬编码值的默认构造函数测试，
用 explicit 构造函数 `new MaxTurnsGuardHook(metrics, 15, 20)` 替代，
这样默认值变更不会影响现有测试逻辑。

或者：更新测试中对 default 值的验证，改为期待 warnAt=20, forceAt=30。

**注意**：保持测试行为一致——如有用 `new MaxTurnsGuardHook(metrics)` 无参构造的测试，
改成 `new MaxTurnsGuardHook(metrics, 15, 20)` 保持原有 assert 逻辑不变。

### 3. 验证

```bash
cd /Users/liulihan/IdeaProjects/sre/claude/kairo-code
mvn clean verify -q
```

BUILD SUCCESS + all tests pass。

### 4. Commit

```
feat(hook): raise MaxTurnsGuardHook defaults to warnAt=20, forceAt=30

PostBatchEditVerifyHook now fires correctly (fixed in M40), causing each
file edit to trigger an mvn-test verification turn. L7 (3 files) and
L10 (5 files) were hitting the old forceAt=20 limit before creating
required test files, regressing scores from ~72/68 to ~35/38.

New defaults give adequate budget: L7 needs ~20 turns, L10 ~30 turns.
```

写入结果：`.dispatcher/bench/results/` 不需要单独结果文件，M43 结果跟 benchmark rerun 一起。

## 成功标准

- `mvn clean verify` BUILD SUCCESS
- `MaxTurnsGuardHookTest` 全部通过
- Commit 写入 kairo-code 仓库
