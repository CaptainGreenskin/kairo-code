# Changelog

All notable changes to Kairo Code are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project
adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- **General-flow Resume** — stopping an ordinary agent run now surfaces a
  "Resume" button that continues from where it left off (the backend lands the
  session in a resumable `FAILED_PLANNING` phase and keeps full conversation
  history). The resumable flag is sticky across WebSocket rebinds and cleared on
  the next message / resume. Plan-flow resume is unchanged; the button is hidden
  in experts mode.
- **Workspace write boundary** — file WRITE tools whose resolved target escapes
  the session's working directory are escalated to human approval (reusing the
  existing approval flow) instead of writing silently. Covers single-path tools
  (`write`/`edit`) and `batch_write`'s `files` array. Implemented upstream in
  `kairo-core`'s `ToolPermissionResolver` and wired via `CodeAgentFactory`.

### Changed

- **Plan confirmation is explicit** — removed the keyword-triggered 5s auto-build
  countdown; confirming a plan is now an explicit "Approve and Build" click only.

### Fixed

- **Stop now terminates in-flight bash** — `BashTool` cancels the underlying
  sandbox process on cancellation, so hitting Stop no longer lets a long-running
  command keep executing in the background (upstream `kairo-tools`).
- **Stop leaves the running state immediately** — the UI no longer stays stuck in
  the "running" state after Stop when the backend emits no terminal event.
- **New chat clears stale experts canvas** — opening a new chat resets the
  experts canvas and plan phase instead of inheriting the previous session's.

## [0.2.0-SNAPSHOT] — 2026-05-23

Full-stack Kairo SPI integration milestone. Kairo Code stops being a thin
shell over `kairo-core` and becomes a real consumer of seven upstream
modules. Three upstream contributions were pushed back during this work
(see "Upstream contributions" below).

### Phase A — Stabilization

- **Session checkpoint preserves tool history** — `CheckpointWriterHook`
  used to drop every `ToolUseContent` on the floor when building the
  assistant message, and never recorded `tool_result` at all. Both are
  now persisted via the existing `Msg.Builder.addContent(...)` path plus
  a new `@HookHandler(HookPhase.TOOL_RESULT)` handler. Without this fix
  `:resume`, snapshot replay, and any evolution consumer that walked the
  checkpoint saw `<think>`-only assistants and nothing else.
- **`KairoCodeMain.acpServer` is package-private** so `KairoCodeAcpServerTest`
  can compile against it without reflection.
- **Expert team / swarm / team commands work** — `ReplLoop` now wires a
  `SwarmCoordinator` via `ExpertTeamFactory.create(config, modelProvider, 3)`.
  Before this, `:expert` / `:team` / `:swarm` all reported "kairo-expert-team
  not on classpath" even when the jar was resolved.

### Phase B — Governance baseline (the README's "Same Models. Governable." promise)

- **PII redaction** wired through `kairo-security-pii`'s `PiiRedactionPolicy`
  on a `DefaultGuardrailChain`, attached at session build. POST_MODEL +
  POST_TOOL phases redact email / phone / SSN / API keys / JWT / Chinese ID
  and CN phone / IPv4 / IBAN / credit cards before they leave the model. Set
  `KAIRO_PII_REDACTION=off` to disable for debugging.
- **Observability** via `kairo-observability` + Micrometer. A
  `SimpleMeterRegistry` is built at REPL start, `AgentMetrics` registers
  Micrometer counters / timers for `kairo.agent.calls.total` /
  `kairo.agent.call.duration`, and gauges for `kairo.agents.active|running|idle`.
  `:metrics` was extended to print the kairo.* meters alongside per-turn data.
- **Cron** integrated through `kairo-cron`. New `:cron` command with
  `list / add <m h dom mon dow> <prompt> / delete <id> / start / stop`. Tasks
  persist under `~/.kairo-code/cron/` and survive restarts. Fire callback
  currently logs only — `M-A4`-related agent-bridge wiring is the follow-on.

### Phase C — Self-built → upstream migration

- **Plugin system rewritten on `kairo-plugin`**. The 240-line self-built
  `PluginRegistry` + `PluginManifest` (yaml-based, local-only) was deleted.
  Replaced with a `DefaultPluginManager` factory wired to all upstream source
  fetchers (LocalPath / GitHub / Npm / GitUrl / GitSubdir). New `:plugin install
  <source>` syntax: `github:owner/repo[#ref]`, `npm:pkg[@ver]`, `git:<url>[#ref]`,
  `path:./local`. The Claude-Code-compatible `plugin.json` schema replaces the
  old `plugin.yaml` format.
- **Evolution gains the upstream curator**. `kairo-evolution`'s
  `LifecycleCuratorDaemon` is wired with a `FileSkillTelemetryStore` under
  `~/.kairo-code/curator/`. New `:evolve curator [start|stop|status|run]`
  manages the non-destructive `ACTIVE → STALE → ARCHIVED` skill lifecycle.
  The self-built `ReflectionPipeline` + `LearnedLessonStore` are kept — they
  handle the strike-3 lesson-generation flow which is a different concern
  from skill quality curation.

### Phase D — Capability expansion

- **LSP integration** via `kairo-lsp`. `DefaultLspService` is wired with the
  built-in language server registry; subprocesses are lazy — only spawn when
  a tool first calls `currentDiagnostics(path)`. New `:lsp [status |
  diagnostics <file> | shutdown]` command.

### Phase E — Documentation

- CHANGELOG updated to cover the M4 → 0.2.0-SNAPSHOT gap (M3 was the last
  recorded entry — five months of M4 ~ M-Curator / M-UserModel / M-Plugin /
  M-McpServer / M-SubSkill / M-SessionSearch / M-KairoMd / M-SkillVis work
  was historically captured only in memory files).

### Upstream contributions (kairo)

This milestone followed the principle: when an integration uncovers a
generic capability gap, fix it upstream rather than patching kairo-code.

1. **`kairo-core/OpenAISseSubscriber`**: emit aggregated `ModelResponse` on
   `finish_reason` even when the provider omits the trailing `data: [DONE]`
   marker. MiniMax M2 and Zhipu GLM both do this, and without the fix every
   non-streaming consumer of those providers silently dropped `tool_calls`.
   Includes 2 regression tests (`toolCallDelta_emitsOnFinishReason_…`,
   `emitFinalResponse_isIdempotent_…`).
2. **`kairo-core/CodeAgentFactory.buildModelProvider` made public** so CLI
   bootstrap (e.g. `ReplLoop`) can build auxiliary subsystems — Expert Team
   coordinator, evaluation harnesses — that need the same provider wiring as
   the session agent without going through a full `createSession`.
3. **`kairo-lsp/BuiltInServers` adds `JDT_LS`** for Eclipse JDT Language
   Server with Maven / Gradle / `.project` workspace markers. Without this
   any kairo agent that touches Java files (kairo-code obviously included)
   has no built-in route to a Java LSP. Includes regression test
   `jdtlsResolvesJavaFileFromMavenWorkspace`.

### Open follow-ups

See task IDs in the dispatch tracker for these:

- **M-A4** — MiniMax M2 tool-execution observability gap. Files get created
  (tool runs), but `has_tool_calls=false` in trace and the ModelResponse
  reaching `PostReasoning` lacks `ToolUseContent`. Likely a hidden tool-
  dispatch path that bypasses the standard `OpenAIResponseParser`.
- **M-A6** — `kairo-code-cli` surefire parallel deadlock under multi-core
  load (`<parallel>methods</parallel>` + `<forkCount>1.5C</forkCount>`).
  All test classes pass individually; the parallel pool hangs at scale.
- **M-B4** (done) — PII redaction extended to `_streaming_result` (the streaming
  bridge's tool-result snapshot in `ToolUseContent` args). See `CheckpointWriterHook:252`.
- **M-D1'** — wire LSP `snapshotBaseline → notifyChange → diagnosticsSince`
  into `WriteTool` / `EditTool` so tool results report "did this edit
  introduce new compile errors?".
- **M-D2'** — true multi-model fallback at the `ModelProvider` SPI layer
  (note: `kairo-gateway` is multi-Channel orchestration, not multi-Model).
- **M-D3** — `kairo-event-stream-sse` for `kairo-code-server` web UI.

## [0.1.0-M3] — 2026-04-26

Third milestone — kairo-code grows a `task` tool that spawns sub-agents in
isolated git worktrees, then prompts the user to merge / discard / keep the
child's changes. This is also the **first real consumer of the upstream kairo
Workspace SPI** (`io.kairo.api.workspace`); until now the SPI had only its
default `LocalDirectoryWorkspaceProvider`.

### Added

- `task` tool (`io.kairo.code.core.task.TaskTool`, `@Tool(name="task")`) — the
  parent agent calls it with `description` + `prompt` (+ optional
  `isolation: worktree|none`) to delegate a focused sub-goal to a child
  session. Returns an XML `<task_result task_id=… outcome=… files_changed=…>`
  envelope wrapping the child's final message, with metadata mirrored on the
  `ToolResult`.
- `WorktreeWorkspaceProvider` + `WorktreeLifecycle` (`kairo-code-core`) —
  acquire spawns a worktree under
  `~/.kairo-code/worktrees/<repo-fingerprint>/<task-id>/`, records the parent
  HEAD SHA in a `.base` sidecar, and supports squash-`merge` / `discard` /
  `keep`. Falls back to `NONE` isolation transparently when the parent dir is
  not a git repo, so read-only sub-tasks still work.
- `ConsoleWorktreeMergePrompter` (`kairo-code-cli/task/`) — JLine-backed
  three-way prompt (`[m]erge / [d]iscard / [k]eep`) using the same
  `BufferedReader` / `PrintWriter` channels as `ConsoleApprovalHandler`.
  Reactor-cancellable: dispose interrupts the prompting thread and the
  prompter resolves to `DISCARD` (the safest default per the
  `WorktreeMergePrompter` contract).
- `ReplChildSessionSpawner` (`kairo-code-cli/task/`) — builds children
  through `CodeAgentFactory.createSession` with `SessionOptions.asChildSession()`,
  overrides `CodeAgentConfig.workingDir` to the worktree path, and shares the
  parent's approval handler so the user sees a single approval flow.
- `AgentEventPrinter` gained a `prefix` constructor — children stream with a
  dim `[task:<id>] ` prefix so parent and child output are visually distinct.

### Changed

- `CodeAgentFactory.SessionOptions` extended with
  `withTaskTool(TaskToolDependencies)` and `asChildSession()`. The factory
  registers `TaskTool` only when deps are wired **and** the session is not a
  child — recursion is out of scope for M3 and the factory enforces it.
- `ReplLoop` builds a `TaskToolDependencies` bundle at startup
  (provider rooted at `config.workingDir()`, lifecycle dir
  `~/.kairo-code/worktrees`) and threads it into both the initial session
  and the `ReplContext` rebuild lambda, so `:clear`, `:model`, and `:skill`
  preserve the wiring.

### Tests

- 108/108 green via `mvn clean verify` (kairo-code-core 44 + kairo-code-cli 64).
- New suites: `TaskToolTest` (5 cases — merge, discard, no-op skips prompt,
  missing deps, blank description) and `TaskToolIT` (2 cases — factory
  registers `task` for parent only, full e2e merge of a child write into the
  parent).

### Demo

See [`docs/m3-task-tool-demo.md`](docs/m3-task-tool-demo.md) for a reproducible
REPL walkthrough.

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
