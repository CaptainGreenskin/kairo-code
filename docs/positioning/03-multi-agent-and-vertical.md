# 03 · 多 Agent / 垂直领域 —— 框架赋能他人造

> 本层主要是**远期增长故事**，不是 0.1.x 卖点。
> 用于 talk 远期愿景、investor pitch、长期路线图问答。**销售当下不要把这层放头版**，会显得空。

---

## 30 秒答

> "Cursor / Claude Code 是**终端代码 agent**，做通用编程任务。Kairo Code 既是终端，也是**框架** —— 任何团队可以基于 Kairo 造**自己领域的 agent**：金融监管报告 agent、SAP ABAP 二开 agent、Spring Boot v2→v3 升级 agent、SRE 故障排查 agent。**Cursor 是终端产品，做不到这件事**；Kairo 是赋能他人造垂直 agent 的基础设施。"

---

## 2 个角度

### 多 Agent 编排 / 团队模式

单 agent 在复杂任务上有上限：context 太长、tool 太多、决策链太深。

多 agent 模式（Team / Swarm / Sub-agent）让每个 agent 专一：

```
PM agent  — 拆 ticket、写 acceptance criteria
  ├─ Backend agent  — 改 Spring controller / service
  ├─ Frontend agent — 改 React component
  └─ Reviewer agent — Plan Mode 跑，只审不写
```

**已交付（M3）**：单层 sub-agent + worktree 隔离 ([demo](../m3-task-tool-demo.md))
- 父 agent 通过 `task` 工具 spawn 子 session
- 子 session 跑在独立 git worktree（`~/.kairo-code/worktrees/<task-id>/`）
- 完成后用户 prompt：merge / discard / keep
- 这是 Kairo 上游 Workspace SPI 的**第一个真实 consumer**

**🌱 远期（2027+）**：完整 Team 模式
- 多 agent 并行（不只是父 → 子单链）
- Team manifest（YAML 定义角色、tool 子集、skill 子集）
- Inter-agent message bus（不是 ReAct loop 嵌套）
- 共享 memory + 私有 memory 区分

参考实现：`zhikuncode` 的 Team / Swarm / SubAgent 三模式（已有可借鉴的 Java 实现）。

### 垂直领域 Agent —— Kairo 是基础设施

通用 code agent 在专业领域不够用：
- **金融监管报告**：知道 Basel III / CRD V 字段定义、知道国内一行一局的报送格式 —— Cursor 不知道。
- **SAP ABAP 二开**：知道 ABAP 语法、SAP 模块依赖、传输请求流程 —— Claude Code 训练数据少。
- **Spring Boot v2 → v3 大升级**：知道 `javax.*` → `jakarta.*`、Spring Security 6 重构、MVC 配置变化 —— 通用 agent 经常改错。
- **SRE 故障排查**：知道你公司的内部系统拓扑、Runbook、监控指标语义 —— SaaS agent 永远不知道。

Kairo 作为**框架**让这些团队各自造自己领域的 agent，复用：
- 同一套 ReAct loop
- 同一套 tool / skill / memory SPI
- 同一套治理纪律（审批流 / 审计 / 成本）
- 不同：领域 system prompt、领域 tool 集、领域 skill 库、领域知识库 memory

**对比**：
- Cursor 不能让你造垂直 agent —— 你能改 prompt，但改不了底层。
- Claude Code 同上。
- 自己从零写：要先做 Kairo 框架的活（30 模块 SPI、治理、ReAct、tool 协议）。

---

## 真实世界落地形态

| 团队画像 | 用 Kairo 造什么 | 替代方案为什么不行 |
|---|---|---|
| 大型银行风险管理部 | 监管报告 agent | Cursor 不能本地部署 + 不懂监管字段 |
| SAP 实施咨询公司 | ABAP 二开 agent | Claude Code 训练数据 ABAP 太少 |
| 互联网公司平台部 | Spring Boot 升级 agent | 通用 agent 改错率高，要懂内部 BOM 约束 |
| 大厂 SRE 团队 | 故障排查 + Runbook 自动化 agent | 需要嵌进内部监控系统，不能 SaaS |
| 律师事务所 IT 部 | 合同 / 文档对比 agent（非代码） | Cursor 是 IDE 工具，文档场景不适用 |

---

## 当前状态

| 角度 | 状态 |
|---|---|
| 单层 sub-agent + worktree 隔离 | ✅ M3（2026-04-26） |
| 子 agent 不能递归（递归留给未来） | ✅ M3 enforced —— `CodeAgentFactory` 拒绝在子会话注册 `task` |
| Self-Evolution slice（垂直 agent 的"学习"基础） | 🚧 M7（2026-09 → 10）—— 5 轮 benchmark blog post |
| 多 agent 并行 / Team manifest | 🌱 远期 2027+ |
| 垂直 agent 模板库 / cookbook | 🌱 远期，等 0.2.0 beta 用户反馈 |
| 基于 Kairo 的第三方垂直 agent 项目 | 🌱 远期 —— 这是**最强信号**，比上游 SPI consumer 数量更说明问题 |

---

## 一句话决断

**这层是 Kairo 的天花板**，但不是地板。当前别拿来当主卖点（容易显空），用来作为"5 年后愿景 / why bet on Kairo"的回答。M7 self-evolution benchmark 跑出来后，这层会从画饼变成可信故事。
