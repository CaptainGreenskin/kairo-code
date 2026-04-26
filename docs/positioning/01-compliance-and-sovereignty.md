# 01 · 合规 / 数据主权 / 离线 / 可审计

> **这层是 Kairo Code 最锋利的差异化。**
> 击中"代码不能出网、LLM 必须可控、调用必须可审计"的企业市场 —— Cursor / Claude Code / 云 sandbox **直接出局**，不是技术差，是**合规进不去**。

---

## 30 秒答

> "云 sandbox + Cursor + Claude Code 解决的是'我懒得装'。Kairo Code 解决的是'我**装不了**' —— 公司代码不能离开企业网、依赖在内部 Nexus、LLM 必须走自家网关、每次调用要审计追溯。这些场景里别的方案直接出局。Kairo Code 跑在你自己的 JVM 里，模型走你指定的网关，所有事件可写到你的 Splunk / Datadog。"

---

## 4 个具体角度

### 数据驻留 / 不出网

| | Cursor | Claude Code | 云 sandbox (e2b / Daytona) | Kairo Code |
|---|---|---|---|---|
| 你的代码 | 上传 Cursor 服务器 | 走 anthropic.com | 跑在第三方云 | **本地 / 企业 VPC 内** |
| LLM 调用 | Cursor 转发 | 直连 Anthropic | 第三方云转发 | **直连你指定的 endpoint** |
| 模型选择 | Cursor 控制 | 锁 Claude | sandbox 决定 | **BYO**：Azure OpenAI / Bedrock / 自部署 vLLM / DeepSeek / Qwen / Llama / 文心 |

**关键场景**：
- 银行核心系统代码 —— 不能离开企业内网
- 中国信创要求 —— 不能用美国 SaaS、要走国产模型 + 国产 OS
- 欧盟 GDPR / 数据主权 —— 数据驻留欧盟司法管辖区
- 国防 / 能源 —— Air-gapped 物理隔离环境

### Air-gapped / 全离线

Cursor / Claude Code 必须连 `api.anthropic.com`；云 sandbox 必须连第三方云。

Kairo Code 接**内网部署的开源模型**（Ollama / vLLM / SGLang 跑 DeepSeek-V3 / Qwen-Coder / Llama-3）—— 飞机上 / 涉密网 / 离线机房都能跑。

### 可审计 / 可观测

企业合规问卷常见问题：
- 哪个员工，什么时间，改了哪行代码？
- 调了哪些工具，每个工具的输入 / 输出？
- 消耗了多少 token，模型 ID 是什么？
- 这次调用是否经过人工审批？

Kairo 的所有事件（pre-tool-call / post-tool-call / pre-compact / approval）都通过 hook SPI 发出，可写到 **Splunk / Datadog / OpenTelemetry / 公司自家 SIEM**。Cursor / Claude Code 的 SaaS 模型只给你 token 数，原始事件流拿不到。

### 欧盟 AI Act / GDPR / 信创合规

2026 年欧盟 AI Act 全面执行，企业用 AI 工具要做 conformity assessment：
- **Apache 2.0 开源 + 可审计代码** —— Kairo Code 的合规问卷比闭源 SaaS Cursor / Claude Code 简单 10 倍。
- **模型 + 数据流可由用户完全控制** —— 是 EU AI Act high-risk 场景的硬要求。
- **中国信创**：国产 LLM + 国产 OS（统信 / 麒麟）+ 国产 CPU 全栈认证 —— Cursor / Claude Code 不可能进这条认证链。

---

## 当前状态

| 角度 | 状态 |
|---|---|
| BYO LLM endpoint | ✅ 已交付 —— Kairo 框架支持任意 OpenAI 兼容 endpoint，已在 vLLM / DeepSeek 上测过 |
| 本地 / 企业内网部署 | ✅ 已交付 —— Kairo Code 是 java -jar，跑哪儿都行 |
| Hook 事件可观测 | ✅ 上游 SPI 已有（@PreToolCall / @PostToolCall 等），🚧 REPL 暴露在 M4（2026-06） |
| Air-gapped 部署文档 + 认证案例 | 🚧 路线图 M8（2026-10）—— 0.2.0 beta 时随 quickstart 发布 |
| EU AI Act conformity 模板 | 🌱 远期 —— 1.0 GA 后随企业版本提供 |

---

## 一句话决断

**别人能不能进这个市场，由它的架构决定，不是营销决定。** Cursor 的 SaaS 模型、Claude Code 的 anthropic.com 直连、云 sandbox 的第三方主机 —— 都不是补丁能解决的。这是 Kairo Code 的结构性优势，不会随时间消失。
