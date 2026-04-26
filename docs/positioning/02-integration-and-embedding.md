# 02 · 集成 / Embed —— Kairo 是库，不是终端产品

> Cursor / Claude Code / 云 sandbox 都是**终端产品**（你下载它、用它）。
> Kairo Code 同时是**终端 CLI** **和库** —— 你可以把它**嵌进**你的 Jenkins / Spring Boot / IDE 插件 / 自研平台。这是框架红利，闭源 SaaS 给不了。

---

## 30 秒答

> "Cursor 是个工具，你只能用它。Kairo Code 是工具 + 库 —— 你可以拿它当工具用，也可以把它**嵌进你自己的产品**。Jenkins 里跑 PR 自动 review、Spring Boot 应用里加个 AI 运维助手、自研 Code Review 平台底层换成 Kairo —— 这些**Cursor / Claude Code 完全做不到**，因为它们是封闭 SaaS。"

---

## 4 个具体集成场景

### Headless / CI 模式

CI 里跑 code agent 是企业刚需：
- PR 提交 → 触发 Kairo Code → 自动 review、补单测、写 commit message
- 测试失败 → 触发 Kairo Code → 自动诊断 + 建议修复
- 升级 Spring Boot 大版本 → 批量跑 Kairo Code → 自动改 import / @Deprecated

```bash
# 已支持的单次任务模式（M1 起）
java -jar kairo-code-cli.jar --task "review PR #123 changes" --quiet
```

**对比**：
- Claude Code CLI 也支持 headless，但**模型锁死 Claude**、调用走 anthropic.com（合规问题，见 [01](01-compliance-and-sovereignty.md)）。
- Cursor 是 IDE，没有 headless 模式。
- 云 sandbox 不能集成进你自己的 CI（要它的 SaaS 控制台）。

### Spring Boot Starter Embed

现有 Spring Boot 应用想加个"ChatGPT 风格"的运维助手 / 客服 / 自动 PR reviewer，Kairo 直接 import：

```java
// 🌱 远期 API 示例 —— 0.2.0 GA 后
@Configuration
@EnableKairoCodeAgent
public class MyAppConfig {
    @Bean
    public CodeAgent reviewerAgent(KairoCodeBuilder builder) {
        return builder.withSkills("code-review", "security-scan").build();
    }
}
```

业务代码：
```java
@Autowired CodeAgent reviewer;
@PostMapping("/pr/review")
public ReviewResult review(@RequestBody PullRequest pr) {
    return reviewer.call("review this PR: " + pr.diff());
}
```

**对比**：Cursor / Claude Code 没法 embed 进你的 Java 应用 —— 它们是终端产品。

### JVM 生态库直接复用

你公司有 30 年 JVM 沉淀：JDBC drivers、Kafka client、内部系统 SDK、Maven 私有仓库的几百个 jar。

Kairo 的 tool 可以**直接 `import` 这些 jar 当工具**：

```java
@Tool(name = "query_internal_user_db")
public List<User> queryUsers(String filter) {
    // 直接用公司内部 JDBC + DAO，不需要 wrapper
    return userDao.findByFilter(filter);
}
```

**对比**：Claude Code（Node）要写 RPC wrapper 才能调你公司的 Java 内部系统 —— 多一层、多一个故障点、多一份审计负担。

### 全 SPI 可定制（不只是 Hook）

Cursor / Claude Code 给你 hooks / config / skills 可改。
Kairo Code 给你**核心组件可换**：

| SPI | 你能换成什么 |
|---|---|
| `ToolExecutor` | 拦截、注入、改写、限流、审计 |
| `MemoryStore` | 写到你的 Postgres / Redis / 企业知识库 / Elasticsearch |
| `ApprovalHandler` | 接公司审批流（OA / Jira approval / Slack bot） |
| `ModelProvider` | 接自部署 vLLM、内网 LLM 网关、A/B 路由器 |
| `SkillStore` | 团队共享 skill 库、自动从知识库同步 |
| `WorkspaceProvider` | git worktree、远程 git server、ephemeral container |

这是 Kairo 上游 31 模块 SPI 治理纪律的红利 —— 每个扩展点有正式合约 + 版本兼容性保证（japicmp）。

---

## 当前状态

| 角度 | 状态 |
|---|---|
| Headless 单次任务模式（`--task`） | ✅ M1 起 |
| Hook SPI 上游 | ✅ kairo-core `@PreToolCall` / `@PostToolCall` 等 |
| Hook REPL 命令暴露（`:hooks list/reload`） | 🚧 M4（2026-06） |
| MCP client（接 GitHub MCP / filesystem MCP / DB MCP） | 🚧 M4（2026-06） |
| 用户 drop `~/.kairo-code/skills/*.md` 自动加载 | 🚧 M5（2026-07） |
| Spring Boot Starter (`@EnableKairoCodeAgent`) | 🌱 远期 0.2.0+ |
| `kairo-code-server` Spring Boot 服务模块 | 🌱 占位符模块已存在，未实现 |
| 自定义 ToolExecutor / MemoryStore 文档 + cookbook | 🌱 远期 |

---

## 一句话决断

**框架 vs 终端产品的差距不是 feature 多寡，是市场可达性。** Kairo Code 当 CLI 用是 1 个用户场景，当库 embed 是 N 个，每个 N 都是 Cursor / Claude Code 进不去的领域。这是长尾红利。
