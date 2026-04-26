# Kairo Code — Java Code Agent

## 项目定位

kairo-code 是基于 Kairo 框架构建的 Java 代码 Agent，**双重身份**：
1. Kairo 框架的 **dogfooding 应用** —— 证明 Kairo SPI 能支撑真实生产级 Agent。
2. **企业 Java 团队的 code agent** —— 在 Cursor / Claude Code / 云 sandbox **进不去**的市场（合规、内网、信创、Embed、长期治理）做差异化竞争。

**在 UX 抛光层不与 Claude Code 竞争**（个人开发者那层 TS 生态已饱和，不进）。
**在合规 / Embed / 治理 / BYO-LLM 经济性这几条 axis 上是竞品** —— 详见
[`docs/positioning/`](docs/positioning/README.md)。

**不是什么**：Cursor / Claude Code 的 UX 竞品；多模型适配器大全；插件市场；TUI 渲染秀。
**是什么**：(a) Kairo SPI 正确用法参考实现；(b) 企业 Java 团队的合规/可嵌入 code agent；
(c) M7 起 Self-Evolution 的孵化容器（从失败里学）。

---

## 模块结构

```
kairo-code-core/     ← Agent 配置、系统提示词、上下文策略
kairo-code-cli/      ← JLine REPL、斜杠命令、流式输出
kairo-code-server/   ← Spring Boot 入口（占位符，待完善）
kairo-code-examples/ ← 示例用法
```

### 核心类速查

| 类 | 模块 | 职责 |
|----|------|------|
| `CodeAgentFactory` | core | 创建配置好的 Agent，注册工具和权限 |
| `CodeAgentSession` | core | 管理 Agent 会话生命周期 |
| `ConsoleApprovalHandler` | core | 交互式权限确认 |
| `WorktreeWorkspaceProvider` | core | Git worktree 工作空间集成 |
| `TaskTool` | core | 任务/子任务执行 |
| `KairoCodeMain` | cli | 主入口，支持 `--task` 单次模式和交互 REPL |
| `ReplLoop` | cli | REPL 主循环 |
| `StreamingAgentRunner` | cli | 流式响应渲染 |
| `CommandRegistry` | cli | 斜杠命令注册表 |

---

## 构建和运行

```bash
# 先构建 Kairo 本地依赖（kairo-code 依赖 kairo 本地 SNAPSHOT）
cd /Users/liulihan/IdeaProjects/sre/claude/kairo
mvn clean install -DskipTests

# 构建 kairo-code
cd /Users/liulihan/IdeaProjects/sre/claude/kairo-code
mvn clean package -DskipTests

# 单次任务模式（适合 Agent 自动化）
java -jar kairo-code-cli/target/kairo-code-cli-*.jar \
    --task "实现 MemorySPI 的 Redis 后端"

# 交互 REPL 模式
java -jar kairo-code-cli/target/kairo-code-cli-*.jar
```

### 测试命令

```bash
# 运行所有测试
mvn test

# 运行单个模块
mvn test -pl kairo-code-core

# 格式修复
mvn spotless:apply
```

---

## REPL 斜杠命令

| 命令 | 功能 |
|-----|------|
| `:help` | 列出所有命令 |
| `:clear` | 清除会话历史 |
| `:model <name>` | 切换模型 |
| `:cost` | 显示 token 和费用 |
| `:plan [on\|off]` | 切换计划模式 |
| `:skill list` | 查看可用技能 |
| `:skill load <name>` | 加载技能 |
| `:snapshot save <key>` | 保存会话快照 |
| `:resume <key>` | 恢复快照 |
| `:exit` | 退出 |

快捷键：`Ctrl+C` 取消当前轮次，`Ctrl+D` 退出

---

## 内置技能

```
code-review     ← 代码审查
test-writer     ← 测试生成
refactor        ← 重构建议
commit-message  ← Commit 消息生成
```

技能位于 `kairo-code-cli/src/main/resources/skills/`，Markdown 格式定义。

---

## 与 Kairo 框架的关系

kairo-code 使用以下 Kairo SPI：

```java
// CodeAgentFactory 中注册的核心 SPI
AgentFactory          ← 创建 Agent 实例
WorkspaceProvider     ← WorktreeWorkspaceProvider 实现
ToolRegistry          ← 注册内置工具
SkillRegistry         ← 注册内置技能
```

**重要约束**：
- 如果某个需求需要修改 `kairo-api` 的 SPI，不要在这里改，去 Kairo 仓库提 issue
- kairo-code 应该只使用 Kairo 的 SPI，不绕过框架直接调用模型 API
- 这是验证 Kairo SPI 是否合理的最重要标准

---

## 代码规范

- 同 Kairo 主项目：Java 17+，Google Java Format (AOSP)，Project Reactor
- 包路径：`io.kairo.code.*`
- 测试：`*Test.java` 单元测试，`*IT.java` 集成测试

---

## 安全边界

### 可以自主修改
- `kairo-code-core/` — Agent 配置和实现
- `kairo-code-cli/` — REPL 和命令
- `kairo-code-examples/` — 示例代码
- 测试文件
- `src/main/resources/skills/` — 技能定义

### 需要人工审批
- 升级 Kairo 依赖版本（可能引入 breaking change）
- 修改 `pom.xml` 依赖结构
- 新增外部依赖（非 Kairo 生态）

---

## 开发工作流

1. 确认 Kairo 本地 SNAPSHOT 已安装：`cd ../kairo && mvn install -DskipTests`
2. 切 feature 分支：`git checkout -b feature/issue-{号}`
3. 实现功能，使用 Kairo SPI 而非绕过
4. `mvn spotless:apply && mvn test`
5. Commit 并创建 PR

### Commit 消息格式

```
feat(cli): 添加 :history 斜杠命令
fix(core): 修复 WorktreeWorkspaceProvider 清理逻辑
test(core): 补充 CodeAgentSession 超时测试
```

---

## 快照存储位置

```
~/.kairo-code/snapshots/<key>.json
```

---

## 遇到歧义时

- **需要新的 Kairo SPI**：在 kairo 仓库创建 issue，这里暂时用临时实现
- **框架能力不够**：这正是需要记录的信息，在 PR 描述中说明哪个 SPI 需要增强
- **测试环境问题**：在 issue 下留评论，跳过该任务
