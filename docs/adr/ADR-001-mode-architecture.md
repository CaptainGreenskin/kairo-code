# ADR-001: Mode architecture — Agent / Experts / Team / Subagent

- **Status:** Accepted
- **Date:** 2026-05-26
- **Deciders:** liulihan
- **Related code:** `AgentService.java`, `CodeAgentFactory.java`, `SwarmCoordinator.java`, `ExpertTeamTool.java`
- **Related task:** #57 (revert #55), #58 (this ADR), future M-Subagent / M-Team / M-Experts-Upgrade

## Context

kairo-code currently exposes two session modes in the web UI dropdown:

1. **Agent** — single conversational agent with a tool registry (default).
2. **Experts** — qoder-inspired batch workflow that internally fans out work to a fixed roster of expert roles using `SwarmCoordinator` + `ExpertTeamCoordinator`.

A recent change (#55) wired `ExpertTeamTool` as a model-facing tool inside **Agent** mode so the LLM could spontaneously dispatch sub-tasks to the expert team during a normal chat. The change shipped (commit `050c677a`) with passing tests but on reflection introduced an architectural smell:

- The `expert_team` tool internally runs a multi-minute, multi-LLM-call batch.
- A user in Agent mode reasonably expects tool calls to be sub-second to a few seconds.
- A spontaneous `expert_team` invocation looks like a hang and bypasses the user's deliberate mode choice.
- It also collides with qoder's own positioning (which we deliberately mirror): *"Agent mode is more efficient for simple, well-defined edits; Experts mode is for opinionated multi-role planning."* The modes are a user-facing use-case split, not an agent-private dispatch decision.

Meanwhile, Claude Code offers two distinct multi-agent primitives we have **not** yet implemented:

- **Subagent** (Task tool): lightweight, on-demand spawn of an isolated child agent with a tailored system prompt and a curated tool subset. Returns a single message back to the parent. Used for parallelism, context isolation, and specialized scans.
- **Team mode** (`TeamCreate`): long-lived multi-agent topology with peer-to-peer `SendMessage`, a shared `TaskList`, and explicit team-lead orchestration.

We need a coherent story that explains how all four primitives relate and what belongs where, so future sessions stop re-litigating this.

## Decision

Adopt a **four-mode layered architecture**:

```
┌────────────────────────────────────────────────────────────────────┐
│ Experts mode  ── opinionated preset of Team mode                   │  ← qoder lineage
│ (fixed 8-role lineup + Canvas + self-evolution)                    │
├────────────────────────────────────────────────────────────────────┤
│ Team mode  ── long-lived multi-agent + P2P + shared TaskList       │  ← Claude Code TeamCreate lineage
│ (orchestrator + dynamically composed roster)                       │
├────────────────────────────────────────────────────────────────────┤
│ Subagent (Task tool)  ── on-demand isolated child agent spawn      │  ← Claude Code Task tool lineage
│ (one-shot, returns single result to parent)                        │
├────────────────────────────────────────────────────────────────────┤
│ Agent mode  ── single conversational agent                         │  ← today's baseline
│ (the only mode that "owns the chat loop"; the others extend it)    │
└────────────────────────────────────────────────────────────────────┘
```

### Use-case split (binding)

| Need | Use |
|------|-----|
| Single-issue chat, well-defined edits, fast tool calls | **Agent** |
| Parallelism + context isolation for a self-contained subtask | **Subagent** (called from Agent) |
| Long-running collaboration across multiple specialists with shared task state | **Team** |
| Out-of-the-box multi-role planning with a fixed expert lineup | **Experts** (= Team preset) |

### Rules of composition

1. **Agent mode does not auto-invoke Team/Experts.** The user picks Experts (or, in the future, Team) explicitly from the session-mode dropdown. The LLM never spontaneously hands work to a multi-minute batch from inside a tool-result loop.
2. **Subagent is the only tool-call-shaped multi-agent primitive.** Subagents are designed to finish on a tool-call latency budget (seconds to low minutes) with a single string result; that's the contract the tool-result loop assumes.
3. **Experts is the opinionated preset; Team is the open framework.** Once Team mode exists, Experts becomes a configured instance of it (fixed role registry + Canvas projection), not a parallel implementation.
4. **`SwarmCoordinator` stays bound to Experts/Team session payloads, not to the Agent-mode tool registry.** It is an out-of-band, session-level orchestrator.

### Implementation order

1. **Now (this session):** Revert #55. Remove `expert_team` from Agent-mode `ToolRegistry`. Keep `SessionOptions.withSwarmCoordinator(...)` as plumbing for the future Team payload — the field is harmless and avoids touching the record again.
2. **Next (M-Subagent):** Build the Subagent primitive as a model-facing tool (`subagent` or `task`) — Claude Task-style: spawn isolated child, restricted tool set, return single result. This is the right thing to put in Agent mode.
3. **Then (M-Team):** Add Team mode as a new session mode in `AgentService` (`TeamSessionPayload`-style). P2P `SendMessage`, shared `TaskList`. `SwarmCoordinator` plugs in here.
4. **Then (M-Experts-Upgrade):** Refactor today's Experts mode to be a configured preset of Team mode — fixed `ExpertRoleRegistry`, Canvas projection, self-evolution toggle.

## Consequences

### Positive

- Users get predictable latency in Agent mode; mode boundaries match user intent.
- The architectural relationship between qoder-lineage (Experts) and Claude-lineage (Team, Subagent) primitives is documented once, not re-decided every session.
- Each upcoming primitive has a clear seam to land in (`SessionMode` payload, `ToolRegistry`, `SwarmCoordinator` injection) without re-shuffling siblings.

### Negative / accepted trade-offs

- We pay one revert (the #55 commit) and lose ~20 lines of test code. Worth it — the wiring would be load-bearing wrong if left in.
- Until M-Subagent ships, Agent mode has no built-in way to parallelize or isolate context within a single chat. Acceptable; chat-level parallelism via Experts/Team mode covers the immediate user need.
- We carry one unused setter (`SessionOptions.withSwarmCoordinator`) until M-Team lands. Cheap.

### Non-goals

- Auto-routing between modes ("dispatcher mode"). Out of scope; users pick.
- Nested teams (Team inside Team, Subagent inside Subagent). Out of scope, mirrors Claude Code's guard that child sessions never get the Task/Team tools.

## Alternatives considered

- **Keep #55 (Agent owns `expert_team` tool).** Rejected: smuggles a multi-minute batch into a tool-call loop; conflicts with the user-chosen mode model.
- **Drop Experts entirely, only ship Team + Subagent.** Rejected: Experts is qoder's most-recognized differentiator and a known onboarding wedge for users coming from that ecosystem; we keep it as a preset.
- **Make Subagent the umbrella primitive, layer Team and Experts on top.** Rejected as primary framing: Subagent is one-shot by design; Team's long-lived peer protocol does not reduce cleanly to repeated subagent spawns.

## References

- qoder Experts mode docs — `https://docs.qoder.com/zh/user-guide/quest/experts-mode`
- Claude Code `TeamCreate` and Task tool — internal `claude-code-best/` reference repo
- Memory: `feedback_kairo_vision.md`, `feedback_borrow_from_claude_code.md`, `project_kairo_code_expert_team.md`
