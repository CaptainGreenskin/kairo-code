---
Status: DONE
Priority: P0
Project: kairo-code
Module: benchmark
Executor: claude-code
Title: M43 benchmark rerun — L7/L10（MaxTurnsGuardHook warnAt=20/forceAt=30 验证）
---

# M43-002：kairo-code GLM-5.1 benchmark rerun（L7 + L10）

## Background

M42 显示 MaxTurnsGuardHook(warnAt=15, forceAt=20) 在 PostBatchEditVerifyHook 生效后导致回归：
- L7 M42：~35（未创建测试文件，被 forceAt=20 截断）
- L10 M42：~38（同上）

M43-001 将默认阈值提升至 warnAt=20, forceAt=30，jar 已重新构建（commit `94879b9`）。

预期：
- L7 M43：~90+（3文件需~15 turns + 测试文件~5 turns = 20 turns，在 forceAt=30 内）
- L10 M43：~75+（5文件需~25 turns + 测试文件~8 turns = 33 turns，接近 forceAt=30）

## Task

```bash
export KAIRO_CODE_API_KEY=c539cb5082c84595bdf1ac1017d8c0fc.ZnLCm4eJofMGP4vS
JAR=/Users/liulihan/IdeaProjects/sre/claude/kairo-code/kairo-code-cli/target/kairo-code-cli-0.2.0-SNAPSHOT.jar
BENCH=/Users/liulihan/IdeaProjects/sre/claude/kairo-code/.dispatcher/bench

# L7 run
WORKDIR_L7=/tmp/kairo-bench-m43-l7-$(date +%s)
cp -r $BENCH/fixtures/l7-concurrent-cache $WORKDIR_L7
java -jar $JAR \
  --model GLM-5.1 \
  --base-url https://open.bigmodel.cn/api/coding/paas/v4 \
  --chat-path /chat/completions \
  --working-dir $WORKDIR_L7 \
  --task-file $BENCH/tasks/l7-fix-all.md \
  --timeout 1200 \
  --show-usage 2>&1

cd $WORKDIR_L7 && mvn test 2>&1 | grep -E "Tests run|BUILD" | tail -5
ls $WORKDIR_L7/src/test/java/com/example/ | grep -i concurrent || echo "No concurrent test file"

# L10 run
WORKDIR_L10=/tmp/kairo-bench-m43-l10-$(date +%s)
cp -r $BENCH/fixtures/l10-concurrent-orders $WORKDIR_L10
java -jar $JAR \
  --model GLM-5.1 \
  --base-url https://open.bigmodel.cn/api/coding/paas/v4 \
  --chat-path /chat/completions \
  --working-dir $WORKDIR_L10 \
  --task-file $BENCH/tasks/l10-fix-all.md \
  --timeout 1200 \
  --show-usage 2>&1

cd $WORKDIR_L10 && mvn test 2>&1 | grep -E "Tests run|BUILD" | tail -5
ls $WORKDIR_L10/src/test/java/com/example/ | grep -i concurrent || echo "No concurrent test file"
```

写入结果：`.dispatcher/bench/results/benchmark_20260429_kairo-code-glm_sweep_m43_l7_l10.md`

格式：
- M41/M42/M43 三列对比
- 6-axis 评分（L7 和 L10 各一张表）
- Turn count 分析（PostBatchEditVerifyHook 触发次数 vs MaxTurnsGuardHook 触发点）
- 更新 Cumulative Scoreboard

Commit: `bench: add kairo-code GLM-5.1 M43 rerun — L7/L10 MaxTurnsGuardHook threshold validation`

## 成功标准

- L7：ConcurrentUserServiceTest.java 创建 ✅，现有测试全过，新测试 ≥9/10
- L10：ConcurrentOrderTest.java 创建 ✅（或至少接近），现有测试全过
- L7 分数 > M41 (~72) → 说明阈值提升有效
- L10 分数 > M41 (~68)
