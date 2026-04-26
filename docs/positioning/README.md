# Kairo Code — Positioning & Differentiation

> 这套文档是**定位弹药库**，答的不是"Kairo Code 怎么用"，而是"为什么是 Kairo Code，不是 Cursor / Claude Code / 云 sandbox / 自己写"。
>
> 给你 talk Q&A、HN 评论区、客户电话、README 链接时**能直接抄的答案**。

---

## 30 秒电梯版

> "Code agent 市场是分层的。**TS / 闭源 SaaS / 云 sandbox** 已经吃透了**个人开发者**这层 —— 装得快、生态全、UX 漂亮。Kairo Code 不进那层。我们做的是**企业 Java 团队**这层 —— 银行、电信、政府、国央企 —— 他们的 InfoSec 不让 Node 进生产、代码不能出网、LLM 必须走自家网关、审计要求每次调用可追溯。**这些场景里 Cursor / Claude Code 直接出局**，不是它们不好，是它们**进不去**。"
>
> "我们不靠 UX 赢，靠**框架可扩展性（SPI / 治理）+ Self-Evolution（从失败里学）**。这俩参考竞品都没有。"

---

## 5 个差异化层（按"哪个最锋利"排序）

| # | 层 | 一句话 | 适合谁 |
|---|---|---|---|
| 1 | [合规 / 数据主权](01-compliance-and-sovereignty.md) | 你的代码不出网、LLM 你说了算、每次调用可审计 | 金融 / 电信 / 政府 / 国央企 / GDPR / 信创 |
| 2 | [集成 / Embed](02-integration-and-embedding.md) | Kairo 是**库**不是终端产品 —— 嵌进 Jenkins / Spring Boot / IDE 插件 / 自研平台 | Java 后端团队、SI / 集成商、ISV |
| 3 | [多 Agent / 垂直](03-multi-agent-and-vertical.md) | 团队模式 + 领域专业 agent —— Kairo 赋能他人造垂直 agent | Startup CTO、SaaS 团队（远期）|
| 4 | [成本 / 经济性](04-cost-economics.md) | 接 DeepSeek / Qwen 比 Claude 便宜 15-20×；token 走自家云账户 | CFO、平台团队、Long-running 任务大户 |
| 5 | [治理 / OSS / 长期主义](05-governance-and-oss.md) | Apache 2.0 + japicmp + ADR + 31 模块 SPI —— 5 年存活预期 | 企业架构师、技术决策者、学术 / 教学 |

---

## 按观众选话术（Q&A 索引）

| 你被问到… | 主战场 | 辅助 |
|---|---|---|
| 为什么不是 Cursor / Claude Code？ | 1 (合规) | 5 (开源) |
| 为什么不是 e2b / Daytona / Coder（云 sandbox）？ | 1 (数据驻留) | 2 (集成) |
| 为什么是 Java？TS 不是更适合？ | 5 (语言选择 = 用户画像) | 1 (合规) |
| 为什么不是 GitHub Copilot Workspace？ | 1 (代码出网) | 2 (Embed 灵活度) |
| 为什么我要关注一个 v0.1 项目？ | 5 (治理纪律) | — |
| Kairo 的护城河是什么？ | 1 + 5 | 3 (远期增长) |
| Long-running / batch 场景成本怎么压？ | 4 | 2 (Headless) |

---

## 真实 vs 路线图（诚实声明）

定位文档容易写成空头支票。我们用图标标记每个差异化点的状态：

- ✅ **已交付** —— M3 之前已合入 main，集成测试覆盖
- 🚧 **路线图** —— 0.1.0-M4..M8（2026-06 → 2026-10），Plan 已锁定
- 🌱 **远期** —— 0.2.0 GA 之后（2027+），方向确定但未排期

每个层文档底部都有"当前状态"小节，告诉你**今天能拿出手什么**。

---

## 怎么用这套文档

- **写 talk slide**：按层选 1-2 个，每层 1 张 slide，结尾抛 30 秒电梯版。
- **回 HN 评论 / Reddit 帖子**：找最贴的层，复制其"30 秒答 + 一句话表"，附本目录链接。
- **客户电话**：先问对方画像 → 按"按观众选话术"表抽 1+1 张。
- **README 顶部**：root `README.md` 里"Why Kairo Code"一节直接指过来。

---

## 反向:我们**不**赢的地方（诚实）

避免被反向问出毛病。下面这些场景**直接承认 Cursor / Claude Code 更强**，不要嘴硬：

- 个人开发者尝鲜：Cursor / Claude Code 装得快、UX 抛光、token 包月划算。
- Demo 友好：云 sandbox 0 秒启动、reset 0 成本，演示场景压倒性优势。
- 多语言 polyglot 项目：Claude Code 在 Python / Rust / Go 上更熟。
- 模型最前沿性：我们没有 Anthropic 的 fine-tune 优势，只能用公开 API。

承认这些**会让前面的差异化更可信**，不会显得护短。
