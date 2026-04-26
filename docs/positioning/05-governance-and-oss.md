# 05 · 治理 / OSS / 长期主义 —— 5 年存活预期

> 这层是**架构师视角**。CTO / 技术决策者评审一个工具时，feature 只占一半权重；另一半是"**这玩意 5 年后还在不在**"。
> Cursor / Claude Code 是闭源 SaaS：公司关停 = 工具消失。Kairo 是 Apache 2.0 + 31 模块 SPI 治理 = 可 fork 永生。

也包含**为什么是 Java**的回答（语言选择 = 长期技术押注的一部分）。

---

## 30 秒答

> "选工具不只是选 feature，是选**未来 5 年的依赖**。Cursor / Claude Code 是闭源 SaaS —— Anthropic / Cursor 关停或涨价 10× 你毫无办法。Kairo 是 Apache 2.0 + 31 模块 SPI + japicmp 版本兼容性检查 + Architecture Decision Records —— 你可以 fork、可以审计、可以预测下一版会不会 break。**这套治理纪律在 Java 圈有 30 年沉淀**（OSGi / Spring / Java Module System），TS 那边没有等价物。**换语言就丢这个护城河**。"

---

## 3 个角度

### 开源 vs 闭源 SaaS —— 5 年风险

企业架构师评审矩阵典型条目：

| 风险 | Cursor / Claude Code | Kairo Code |
|---|---|---|
| 公司关停 | 工具消失，迁移成本 100% | Fork 自维护，迁移成本 0 |
| 涨价 10× | 接受或迁出 | 价格由你的模型 API 决定 |
| 数据政策变更 | 你的代码可能被用于训练 | 自部署，零数据外泄 |
| 漏洞响应 | 等厂商修 | 自己 patch + 上报上游 |
| 长期 roadmap 透明度 | 厂商内部，不可预测 | GitHub Issues / Plan 文档公开 |
| 合规审计 | 黑箱，问卷答不了 | 源码可审 |

5 年前 Atom 编辑器关停、3 年前 Codeship CI 被 CloudBees 整合改路线、2 年前 Adobe 关 Mixamo —— 闭源工具风险不是抽象的，是**每年都在发生**。

### SPI 治理纪律 —— 不是营销话术

Kairo 上游有 31 个 SPI 模块，每个 SPI 都遵守：
- **Stability annotations**：`@Stable` / `@Experimental` / `@Internal`，明确告诉你能不能依赖
- **japicmp 二进制兼容性检查**：CI 上跑，0.x 内 minor 版本不破坏 `@Stable` API
- **Architecture Decision Records (ADR)**：每个重大设计决策有 markdown 留底，不是口口相传
- **Conventional Commits + 自动 changelog**：你能从 commit 历史看出每个版本的变更类型

**对比**：
- Cursor / Claude Code 没有公开 SPI（它们不是框架）。
- 大多数 OSS code agent（如 Aider）是单仓库脚本，没有版本兼容性合约。
- Kairo Code 用 Kairo 的 SPI = 继承这套治理。

**对架构师意义**：
> "我能预测明年这个项目长什么样。`@Stable` 标记的 API 我可以放心写代码，不用担心 2026-12 升级版本要重写。"

### 为什么是 Java —— 语言选择 = 用户画像

> 这是**最常被问**的问题，单独写一篇 [why-java](#为什么是-java详细版) 在下面。

**短版**：Code agent 市场分层：
- **个人开发者**层 → TS / Node 生态赢得彻底（Cursor / Claude Code / Aider），我们不进。
- **企业 Java 团队**层 → 银行 / 电信 / 政府 / 大厂后端，他们的 InfoSec 不让 Node 进生产、内部 SDLC 都是 JVM、有 30 年 Java 库沉淀。**这层 TS code agent 进不去**。

选 Java 不是技术偏好，是**市场选择**。

---

## 为什么是 Java（详细版）

### Q: TS / Python 不是更适合 AI agent 吗？

**短答**：因为我们的目标用户**他们的 InfoSec 不让用 TS / Python**。

**长答**：

1. **个人开发者层已饱和** —— Cursor / Claude Code / Aider 在 TS 生态赢得很彻底，我们不进去。
2. **企业 Java 团队层是空的** —— 金融 / 电信 / 政府 / 大厂后端想用 code agent 但卡在合规：Node 在生产环境受限、Python 内部审计成本高、新 runtime 进 SDLC 要 6 个月评审。他们的代码、CI、内部插件平台都是 JVM。给他们一个 Java code agent，**0 摩擦**接入。
3. **SPI / japicmp / 治理纪律** 在 Java 生态有 30 年沉淀（OSGi / Spring / Java Module System），TS 没有等价物。**换语言就丢护城河**。
4. **Self-Evolution（从失败中学）** 是产品差异化，跟语言无关 —— Java 不构成天花板。

我们**不假装**：
- Java 在 CLI 启动 / 分发 / hook 脚本上比 Node 强 —— 它不强。
- 我们用 GraalVM native-image 把启动压到 100ms（🌱 远期）。
- Maven Central + Homebrew formula 把安装压到 30 秒（🚧 M8 路线图）。
- Hook 脚本支持 .sh / .kt / .java 三种（🚧 M4），不强迫你写 Java。

承认短板、补短板，但不换主语言。

---

## 当前状态

| 角度 | 状态 |
|---|---|
| Apache 2.0 license | ✅ |
| 上游 31 模块 SPI + `@Stable` 标记 | ✅ Kairo 上游已有 |
| japicmp 二进制兼容性 CI | ✅ 上游已配 |
| Architecture Decision Records | ✅ 上游 `docs/decisions/` |
| Conventional Commits | ✅ |
| 公开 12-month plan + 季度复盘 | ✅ `~/.claude/plans/valiant-honking-coral.md`（私人，🌱 计划公开） |
| Maven Central 发布 | 🚧 M8（0.2.0 GA）—— 当前用 GitHub Packages |
| Homebrew formula | 🌱 远期 |
| GraalVM native-image | 🌱 远期 |

---

## 反向：什么场景**别选 Kairo**

诚实分流，避免错配用户：

- **个人开发者尝鲜** → 用 Cursor / Claude Code，UX 好得多。
- **多语言 polyglot 重度场景**（Python + Rust + Go 多）→ TS 通用 agent 更熟。
- **2 周内要交付的小项目**，没合规 / 集成需求 → 任何 SaaS code agent 都比 Kairo 快。
- **不愿在内部维护 Java 工具栈** → 选 SaaS。

**Kairo 不是普世解，是企业 Java 团队 + 合规场景 + 长期主义这个交集的最优解**。

---

## 一句话决断

**OSS 治理纪律是慢变量，但是结构性变量**。3 年前没人在乎 SPI 稳定性，今天你看 LangChain / LlamaIndex 用户的痛苦发推 —— 没治理的框架，迭代速度反过来变成用户负担。Kairo 押对了这件事。
