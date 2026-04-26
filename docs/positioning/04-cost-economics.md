# 04 · 成本 / 经济性 —— BYO 模型让单位成本断崖式下降

> 这层是 **CFO / 平台团队 / Long-running 任务大户** 听得懂的语言。
> 销售场景常被低估 —— 个人开发者不在乎 $20/月，企业月烧 token 几十万的时候**这层比合规更先打动 budget owner**。

---

## 30 秒答

> "Cursor / Claude Code 用 Anthropic / OpenAI 的旗舰模型，token 单价高、计费在第三方手上。Kairo Code 让你**自己选模型** —— DeepSeek-Coder 比 Claude 便宜 15-20×、Qwen-Coder 接国内云便宜 30×、自部署 vLLM 跑开源 LLM 是**只付 GPU 租用费**。Long-running 任务、批量 PR review、CI 集成场景一年省 6 位数不夸张。"

---

## 4 个角度

### 模型单价对比（2026-04 公开价）

> 数字会变，写文档时核对最新；但**量级关系**长期稳定。

| 模型 | Input $/1M | Output $/1M | 相对 Claude Sonnet |
|---|---|---|---|
| Claude Sonnet 4.6 | ~3 | ~15 | 1× |
| GPT-4o | ~2.5 | ~10 | ~0.7× |
| DeepSeek-V3.x | ~0.27 | ~1.1 | **~10× 便宜** |
| Qwen3-Coder（阿里云） | ~0.5 | ~2 | **~7× 便宜** |
| 自部署 Llama-3 / DeepSeek-Coder（GPU 租金折算） | 边际接近 0 | 边际接近 0 | **~50× 便宜** |

**Kairo Code 的位置**：模型选择权交给你，配置 `ANTHROPIC_BASE_URL` 即可指向任意 OpenAI 兼容 endpoint。

### Long-running 场景的复利效应

单次对话 ~10K token 看不出差距。但 code agent 的真实场景：
- 1 次"大重构" task = 50-200 个 tool call = 200K-1M token
- 1 次 batch PR review = 100 PR × 30K token/PR = 3M token
- 1 次升级跑全 codebase = 10-50M token

```
任务体量 1M token：
  Claude Sonnet:   $3 input + $15 output = ~$18
  DeepSeek-V3:     $0.27 + $1.10 = ~$1.40  → 省 92%

10M token / 月（中型团队）：
  Claude:    ~$180/月  =  ~$2160/年
  DeepSeek:  ~$14/月   =  ~$168/年
  差额 ~$2000/年 / 团队 / 模型
```

100 团队 × $2000 = $200K/年的差额。这是 **CFO 听得懂的话**。

### Token 计费在你的账户

| | Cursor / Claude Code SaaS | Kairo Code |
|---|---|---|
| 计费主体 | 第三方公司 | **你公司云账户** |
| 报销路径 | 个人订阅 → 报销流程 | 已在云资源预算内 |
| 成本可见 | 第三方控制台（不一定开放） | 你的 CloudWatch / 阿里云账单 / 自家 Grafana |
| 量大议价 | Cursor / Anthropic 给折扣有限 | 直接走云厂商 EA / 私有模型零边际 |

企业级采购里，"token 走自家云账户"比"少 20% 单价"还重要 —— 走通了财务流程，量级才能放开。

### 合理混合策略（BYO 红利）

Kairo Code 不强制单模型，可以**多模型混搭**（M5+ 路线图）：

```
- 简单 task         → DeepSeek-V3（便宜、快、够用）
- 复杂决策 task     → Claude Sonnet 4.6（最强 reasoning）
- 工具调用 / 短答  → Haiku 4.5 / Qwen-Turbo（低延迟）
- Self-evolution 反思 → Local Llama-3（隐私 + 零边际）
```

**对比**：Cursor / Claude Code 锁单一模型族，没法做这种成本-质量 trade-off。

---

## 当前状态

| 角度 | 状态 |
|---|---|
| BYO model（任意 OpenAI 兼容 endpoint） | ✅ Kairo 框架已支持 |
| `:cost` 命令显示 token / 美元 | ✅ M1 |
| Per-model / per-tool 成本 breakdown | 🚧 M8（2026-10） |
| `~/.kairo-code/usage.jsonl` 导出 | 🚧 M8 |
| 多模型路由（按 task 类型选） | 🌱 远期 0.2.0+ |
| 与企业云账单对接（成本中心 tag） | 🌱 远期 |

---

## 反向：什么场景**别用 BYO 便宜模型**

诚实说明，避免被反问：

- **首次试用 / 评估**：用 Claude / GPT 旗舰，给 Kairo 最好的发挥效果，等流程跑通再切便宜模型。
- **Mission-critical 决策**：金融决策 / 安全 review / 法律文本 —— 单价不是首要考虑，准确度是。
- **新模型未充分测试**：DeepSeek 新版 ship 后先小规模 A/B，再切大流量。

**Kairo Code 的价值不是"逼你用便宜模型"，而是给你"自由选择模型"的能力**。这点比 Cursor / Claude Code 强。

---

## 一句话决断

**单位成本下降 1 个数量级是产品级红利**，不是 feature 优化。Long-running / batch / CI 场景里，BYO 模型的成本优势是**结构性的**，会随时间扩大不会缩小（开源模型质量逐月接近闭源旗舰）。
