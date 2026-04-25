# Changelog

All notable changes to Kairo Code are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project
adheres to [Semantic Versioning](https://semver.org/).

## [0.1.0-M2] — 2026-04-25

Second milestone — interactive REPL gains the controls needed for a 30-minute
coding session: switch into read-only review mode, pull in markdown skills,
save/restore session state, and surface model/tool failures with actionable
guidance.

### Added

- `:plan [on|off|toggle]` — toggle Plan Mode at the `DefaultToolExecutor`. While
  on, write tools (Edit / Write / Bash) are blocked and surface a
  `PlanModeViolationException` rendered with a hint to run `:plan off`.
- `:skill list` / `:skill loaded` / `:skill load <name>` / `:skill unload <name>`
  — manage skills loaded into the system prompt. Four built-ins ship on the
  classpath under `skills/`: `code-review`, `test-writer`, `refactor`,
  `commit-message`. Loading/unloading rebuilds the session via a
  snapshot/restore round-trip so conversation history is preserved.
- `:snapshot save <key>` / `:snapshot list` / `:snapshot delete <key>` and
  `:resume <key>` — file-backed session persistence under
  `~/.kairo-code/snapshots/<key>.json` via `JsonFileSnapshotStore`.
- `ErrorRenderer` — categorize throwables surfaced from `agent.call()` into
  user-facing messages keyed off `ApiErrorType`, kairo's typed exceptions
  (`ModelTimeoutException`, `ModelRateLimitException`,
  `PlanModeViolationException`, `AgentInterruptedException`), and network
  causes (`ConnectException`, `UnknownHostException`, `IOException`).
  Wired into `ReplLoop.executeAgentCall`. Lower layers
  (`ErrorRecoveryStrategy` in `kairo-core`) continue to handle transient
  retries; this layer surfaces the final outcome.

### Changed

- `ReplLoop` now bootstraps a `SkillRegistry` (with built-in skills loaded from
  the classpath) and a `SnapshotStore` (rooted at `~/.kairo-code/snapshots/`),
  passing both into `ReplContext` so commands can reach them without
  re-threading state.
- `ReplContext` exposes `reloadSkills()` and `restoreFromSnapshot()` —
  both rebuild the session via `CodeAgentFactory.createSession`, so the
  approval handler and hooks configured at startup are preserved.

### Tests

- 64/64 green via `mvn clean verify`.
- New suites: `ErrorRendererTest` (15), `SnapshotResumeCommandTest` (12),
  `SkillCommandTest` (11), plus 5 new plan-mode cases in `SlashCommandsTest`.

## [0.1.0-M1] — 2026-04 (earlier)

Initial REPL milestone.

### Added

- `:help`, `:clear`, `:model <name>`, `:cost`, `:exit` slash commands.
- JLine 3 line editor with history under `~/.kairo-code/history` and
  tab-completion of slash commands.
- `StreamingAgentRunner` — Reactor-based agent invocation with Ctrl+C
  cancellation via `Disposable.dispose()` + `Agent.interrupt()`.
- `ConsoleApprovalHandler` wired to JLine's terminal I/O for tool
  permission prompts.
- `CodeAgentFactory.createSession(config, options)` returning a
  `CodeAgentSession` (agent + tool executor + tool registry + loaded
  skills).
