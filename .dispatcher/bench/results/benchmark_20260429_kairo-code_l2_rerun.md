# kairo-code L2 Re-benchmark 结果（修复后验证）

**日期**: 2026-04-29  
**Fixture**: l2-event-dispatcher  
**Executor**: kairo-code (GLM-5.1)  
**Jar**: kairo-code-cli-0.2.0-SNAPSHOT.jar  

---

## 对比：修复前 vs 修复后

| 指标 | M17-001（修复前） | M33-001（修复后） | 变化 |
|------|----------------|----------------|------|
| write/edit 工具调用次数 | 0 | ≥1（文件已修改） | ✅ 有效 |
| 文件修改数 | 0 | 1 | +1 |
| 测试通过数 | 0/17 | **17/17** | +17 |
| 迭代轮数 | 11 | **1** | -10 |
| 耗时 | ~120s | **11s** | -109s |
| 综合得分 | 8/100 | **99/100** | +91 |

---

## 修复效果评估

**修复措施（M32-002）**：
1. `system-prompt.md` 明确禁止 bash 写文件，要求使用 write/edit 工具
2. `NoWriteDetectedHook`：连续 4 轮无 write/edit 调用时注入纠正提示
3. `CodeAgentFactory` 注册 NoWriteDetectedHook

**结果**：修复完全有效。GLM-5.1 在 1 次迭代内完成了完整实现，文件被正确修改。

---

## 工具调用分析

- **write_file / edit_file 调用**：有效（文件被修改，iterations=1 排除 bash 多步写入）
- **bash 文件写入**：未发生（单次迭代完成，无多步 shell 写入迹象）
- **NoWriteDetectedHook 触发**：未触发（第 1 轮即完成，无需纠正）
- **KAIRO_SESSION_RESULT.json**：`finalState=COMPLETED, iterations=1, durationSeconds=11`

---

## 实现质量

GLM-5.1 在单轮内完整实现了 `EventDispatcher`：

```java
private final Map<Class<?>, List<EventHandler<?>>> handlers = new HashMap<>();

// subscribe: computeIfAbsent
// unsubscribe: Iterator 按引用 == 移除第一个匹配
// dispatch: 同步，遇异常抛 EventDeliveryException
// dispatchAsync: 每个 handler 独立 executor，allOf 收集结果
// subscriberCount / clearAll / registeredTypes: 完整实现
```

所有 17 个测试用例通过，包括：
- 基础 subscribe/dispatch
- unsubscribe by reference
- 异步 dispatch（独立异常不影响其他 handler）
- EventDeliveryException wrapping
- subscriberCount / clearAll

---

## 五轴评分

| 轴 | 满分 | 得分 | 说明 |
|----|------|------|------|
| Test Pass Rate | 35 | 35 | 17/17 |
| Edit Precision | 20 | 20 | 精确修改目标文件，无多余改动 |
| Auto Verify | 20 | 20 | `mvn test` BUILD SUCCESS |
| Code Quality | 15 | 14 | 干净 Java，泛型正确；dispatchAsync 稍繁琐 |
| Efficiency | 10 | 10 | 1 iteration，11s |
| **总分** | **100** | **99** | |

---

## 结论

M32-002 修复**完全有效**：

- 修复前（M17-001）：0 文件修改，0/17 测试，8/100
- 修复后（M33-001）：1 文件修改，17/17 测试，**99/100**

分差 +91 分。GLM-5.1 的 tool-use gap 已消除。可以继续进行 L9 benchmark。
