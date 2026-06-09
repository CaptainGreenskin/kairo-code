# Kairo Code

**Java-native Code Agent. Governable by design.**

Kairo Code 是基于 [Kairo](https://github.com/CaptainGreenskin/kairo) 框架构建的 Coding Agent，也是 Kairo 框架的 dogfooding 产品——用自己的框架证明自己的价值。

支持 CLI（交互式 REPL）、Web UI、Docker 一键部署三种使用方式。

---

## Quick Start

### 方式一：从源码运行 CLI

```bash
git clone https://github.com/CaptainGreenskin/kairo-code.git
cd kairo-code
mvn clean package -DskipTests
export KAIRO_CODE_API_KEY=your-api-key
java -jar kairo-code-cli/target/kairo-code-cli-0.2.0-SNAPSHOT.jar
```

不带 `--task` 进入交互式 REPL，带上则直接执行：

```bash
java -jar kairo-code-cli/target/kairo-code-cli-0.2.0-SNAPSHOT.jar \
  --task "fix the failing test in UserServiceTest"
```

### 方式二：npm 安装

```bash
npm install -g @kairo/code
kairo-code
```

### 方式三：Homebrew

```bash
brew tap captaingreenskin/kairo && brew install kairo-code
kairo-code
```

### 方式四：Docker（Web UI + 后端一键启动）

```bash
cp .env.example .env        # 填入 API Key，选择模型提供商
docker compose up -d --build
open http://localhost:3000
```

默认使用 GLM（智谱）作为模型提供商。切换提供商在 `.env` 中修改 `KAIRO_PROVIDER`、`KAIRO_BASE_URL`、`KAIRO_MODEL`。

支持的提供商：`anthropic` / `openai` / `glm` / `qianwen`

> 前置依赖：Java 17+（npm 和 Homebrew 方式需要本机安装 JDK）

---

## 项目结构

```
kairo-code/
├── kairo-code-core/       ← 核心逻辑：Agent 配置、会话管理、进化、MCP、插件
├── kairo-code-cli/        ← CLI 交互式 REPL（31 个 Slash Command）
├── kairo-code-service/    ← Spring Boot 后端服务层
├── kairo-code-server/     ← HTTP API 服务端 + Docker 镜像
├── kairo-code-web/        ← Web 前端（React + Vite）
├── kairo-code-examples/   ← 使用示例
├── kairo-capabilities/    ← 多智能体协调
├── install/               ← npm / Homebrew 分发包
├── docker-compose.yml     ← 一键部署编排
└── Makefile               ← make build / make up / make down
```

---

## Slash Commands（REPL）

| 命令 | 用途 |
|------|------|
| `:help` | 查看全部命令 |
| `:clear` | 清空对话历史，重建 Session |
| `:model <name>` | 切换模型 |
| `:cost` | 查看 token 用量和费用 |
| `:plan [on\|off]` | 切换 Plan Mode（只读审查，禁止 Edit/Write/Bash） |
| `:skill list` | 查看可用 Skill |
| `:skill load <name>` | 加载一个 Skill 到 system prompt |
| `:skill unload <name>` | 卸载 Skill |
| `:snapshot save <key>` | 保存当前会话快照 |
| `:snapshot list` | 列出已保存的快照 |
| `:resume <key>` | 从快照恢复会话 |
| `:session list` | 查看历史 Session |
| `:session resume <id>` | 恢复历史 Session |
| `:memory list` | 查看 Agent 记忆 |
| `:expert <task>` | 启动 Expert Team（plan → generate → evaluate） |
| `:team` | 多智能体团队协调 |
| `:mcp list` | 查看已连接的 MCP 服务器 |
| `:plugin list` | 查看已安装的插件 |
| `:lsp` | 查看 LSP 诊断状态 |
| `:cron` | 查看定时任务 |
| `:compact` | 手动触发上下文压缩 |
| `:metrics` | 查看运行时指标 |
| `:doctor` | 环境诊断 |
| `:exit` | 退出 |

内置 Skill：`code-review`、`test-writer`、`refactor`、`commit-message`

快捷键：`Ctrl+C` 取消当前 Agent 执行（不退出 REPL），`Ctrl+D` 退出。

---

## 核心能力

### 来自 Kairo 框架

Kairo Code 的核心能力全部由 Kairo 框架的 SPI 提供：

- **ReAct 循环** — DefaultReActAgent 驱动的推理-行动循环
- **6 阶段上下文压缩** — Snip → Micro → Collapse → Auto → Partial → CircuitBreaker
- **17+ 内置工具** — 文件读写、Shell 执行、搜索、Git 操作等
- **MCP 协议** — 连接外部工具服务器
- **Hook 治理** — 10 个生命周期点，支持 CONTINUE / MODIFY / SKIP / ABORT / INJECT
- **Skill 系统** — Markdown 格式的可组合技能
- **Plugin 生态** — 兼容 Claude Code 插件格式
- **Session 管理** — 会话持久化、恢复、LRU 池
- **多智能体** — Expert Team（plan/generate/evaluate）+ A2A 协议

### Kairo Code 自有

- **交互式 REPL** — 31 个 Slash Command，Tab 补全
- **Web UI** — React 前端 + WebSocket 实时流式输出
- **Docker 部署** — 一条命令启动完整服务
- **多提供商支持** — Anthropic / OpenAI / GLM / 通义千问
- **Error Recovery** — ErrorClassifier 分类错误 + ErrorRenderer 给出可操作的修复建议
- **自动恢复** — AutoResumeDetector 检测中断的会话并提示恢复
- **进化系统** — 从使用模式中自动提取和注册 Skill
- **LSP 集成** — 编辑后自动运行语言服务器诊断

---

## 错误处理

`ErrorClassifier` 对模型和工具返回的错误进行分类，`ErrorRenderer` 渲染为一行诊断 + 一个可操作的下一步建议（如 `:plan off`、`:clear`、`:model <name>`）。底层重试（限流、服务端错误、prompt 过长）由 Kairo 的 `ErrorRecoveryStrategy` 处理，REPL 只展示最终结果。

---

## 开发

```bash
# 编译全部模块
mvn clean package -DskipTests

# 只编译 CLI
mvn package -pl kairo-code-cli -am -DskipTests

# 启动 Web 开发环境
cd kairo-code-web && npm run dev

# Docker 构建 + 启动
make build && make up

# 查看日志
make logs

# 停止并清理
make clean
```

---

## License

Apache License 2.0
