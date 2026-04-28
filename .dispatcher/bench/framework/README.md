# Kairo-Code Benchmark Framework

可复用的 code agent 评测体系，支持多 executor 对比，token 节约（脚本化）。

## 目录结构

```
.dispatcher/bench/
├── framework/
│   ├── README.md           # 本文件
│   ├── SCORING.md          # 五轴评分标准
│   └── run-benchmark.sh    # 自动化 runner（一条命令跑完）
├── fixtures/
│   ├── l1-bug-fix/         # L1: 3 bugs, 18 tests（现有 fixtures/）
│   └── l2-event-dispatcher/ # L2: 实现 EventDispatcher, 17 tests
├── tasks/
│   ├── fix-all.md          # L1 任务描述
│   ├── l2-implement.md     # L2 任务描述
│   └── l3-implement.md     # L3 任务描述（TurnMetricsCollector）
└── results/                # benchmark 报告
```

## 快速使用

### 前置条件

```bash
# kairo-code executor 需要
export KAIRO_CODE_API_KEY="your-zhipu-api-key"

# 确认 JAR 存在（只需首次构建）
cd /path/to/kairo-code
mvn package -pl kairo-code-cli -DskipTests -q
```

### 运行 benchmark

```bash
BENCH=.dispatcher/bench/framework/run-benchmark.sh

# L1: Bug Fix（3 bugs, 18 tests）
$BENCH --executor kairo-code --level l1

# L2: Feature Implementation（EventDispatcher, 17 tests）
$BENCH --executor kairo-code --level l2

# L3: Self-Improvement（TurnMetricsCollector on kairo-code itself）
# L3 需要手动设置 working-dir 为 kairo-code 项目根，不走 runner
```

### 跑完后还原 fixture

Runner 脚本自动还原，也可手动：
```bash
cd /path/to/kairo-code
git checkout .dispatcher/bench/fixtures/l1-bug-fix/src/
git checkout .dispatcher/bench/fixtures/l2-event-dispatcher/src/
```

## Benchmark 层级

| Level | 描述 | 测试数 | 典型 duration |
|-------|------|--------|--------------|
| L1 | Bug Fix（3 孤立 bug）| 18 | 60s |
| L2 | Feature Impl（实现 EventDispatcher 骨架）| 17 | ~2min |
| L3 | Self-Improvement（多文件 kairo-code 功能）| 6+ | ~5min |

## 评分（详见 SCORING.md）

100 分 = 测试通过率(35) + 编辑精准度(20) + 自主验证(20) + 代码质量(15) + 效率(10)

## 与 Claude Code 对标

| 能力 | Claude Code 预期 L2 | kairo-code 目标 |
|------|--------------------|--------------:|
| Test Pass Rate | 17/17 | 17/17 |
| Edit Precision | 20/20 | 20/20 |
| Autonomous Verify | 20/20 | 20/20（via hook）|
| Code Quality | 14/15 | 12/15 |
| Efficiency | 8/10 | 6/10 |
| **Total** | **~85** | **≥75** |

## 扩展到 L4：SWE-bench 子集

下一步：从 SWE-bench Lite 中选 10 个 Java issue，用同样的 runner 框架评测。
SWE-bench 是业界标准，Claude Code 约 49-55%。
