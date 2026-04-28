# Workspace context

This task touches one of two sibling repos:
- **kairo** — Java SPI library / agent framework
- **kairo-code** — reference code agent built on Kairo

Cross-repo discipline:
- One worktree = one repo. Do not commit edits across both repos in a single task.
- If a kairo SPI change requires a kairo-code consumer update, file it as a
  follow-up task (`Project: kairo-code`, `Depends: <upstream-task-id>`).
- The task brief identifies its target via the `Project:` field — work only
  inside that repo's tree.


---

# kairo-code project context

kairo-code 是 kairo 框架的 dogfood code agent，对标 Claude Code 能力。
Java 21 + Maven 多模块（kairo-code-core / kairo-code-cli / kairo-code-server / kairo-code-examples）。
包结构：`io.kairo.code.core.*` / `io.kairo.code.cli.*`。

## Hard rules

- Work only inside the current worktree directory.
- Do not push, do not modify CI configuration, do not edit `.dispatcher/`.
- `mvn verify` must be green before you consider the task done. Never use `-DskipTests`.
- Stay within the task scope. Do not refactor unrelated code, do not add extra abstractions.
- New behaviour requires new tests. No comment-only files.
- Commit with conventional commit format: `feat(module): ...` / `test(module): ...`

## Architecture rules

- 不新增抽象。用现有 kairo SPI 覆盖需求；没有现成 SPI 才在 kairo-code-core 自己实现。
- 不做向后兼容 shim，孵化阶段直接大改。
- 工具注册入口是 `CodeAgentFactory.createSession`，新工具在这里注册。
- REPL 命令注册入口是 `CommandRegistry`，新命令在这里加。


---

Project: kairo-code
---
Test task for sentinel file creation.
