# Session Payload Architecture

## Overview

The session layer uses a sealed interface `SessionPayload` with two implementations:

```
SessionPayload (sealed)
├── AgentSessionPayload  — single-agent ReAct loop
└── TeamSessionPayload   — multi-expert fan-out with fallback
```

Package: `io.kairo.code.service.agent`

## AgentSessionPayload Lifecycle

`AgentSessionPayload` owns the complete message-handling lifecycle:

- **Phase state machine**: IDLE → PLANNING → (PLAN_PENDING → refinement queue) → IDLE/COMPLETED
- **Concurrency control**: CAS on `runningState` + `AgentConcurrencyController` slot acquisition
- **Agent execution**: `agent.call()` subscription with contextWrite (thinking-delta consumer), bounded-elastic scheduler
- **Cancellation**: `stop()` disposes current run, clears refinement queue, resets `runningState`, and calls `agent.interrupt()`
- **Credential rebuild**: `rebuildAgent(fresh)` with running-state precondition guard

## SessionPhase State Machine

```
IDLE ──sendMessage──▸ PLANNING ──exitPlanMode hook──▸ PLAN_PENDING
                                                          │
                                   confirmBuild ◀─────────┘
                                        │
                                        ▼
                                    EXECUTING ──done──▸ COMPLETED
                                        │
                                     stop()/error
                                        │
                                        ▼
                                 FAILED_EXECUTION

PLANNING ──error──▸ FAILED_PLANNING (retryable, worktree clean)
FAILED_EXECUTION ──revert──▸ IDLE (revert required before retry)
```

## Service vs Payload Boundary

| Concern | Owner |
|---------|-------|
| Session entry creation & destruction | AgentService |
| Mode normalization & routing | AgentService |
| Credential staleness detection | AgentService |
| Message handling (full lifecycle) | SessionPayload |
| Phase state machine | AgentSessionPayload |
| Plan refinement queuing | AgentSessionPayload |
| Agent reference management | AgentSessionPayload |
| Concurrency slot acquire/release | AgentSessionPayload |
| Expert fan-out / triage | TeamSessionPayload |
| Demoted fallback delegation | TeamSessionPayload → AgentSessionPayload |

## AgentRuntimeContext

A record (`AgentRuntimeContext`) bundles shared runtime dependencies injected at construction:

- `sessionId` — unique session identifier
- `sharedSink` — `Sinks.Many<AgentEvent>` multicast event sink (autoCancel=false, shared across reconnects)
- `runningState` — `AtomicBoolean` CAS guard for mutual exclusion
- `phaseRef` — `AtomicReference<SessionPhase>` shared with hooks
- `persistPhase` — `Consumer<SessionPhase>` callback for disk persistence (crash recovery)
- `concurrency` — `AgentConcurrencyController` three-layer slot controller (global / session / depth)

## Backpressure Model

- **Rejected requests** (SESSION_BUSY, REVERT_REQUIRED, REFINEMENT_QUEUE_FULL): returned as cold `Flux.just(error)` — never enters sharedSink
- **Normal flow**: `agent.call()` events emitted to sharedSink; subscribers receive via `sharedSink.asFlux()`
- **Sink type**: multicast replay + onBackpressureBuffer (preserved from prior architecture)

## Mode Architecture (v2.3)

Two modes: `agent` (single-agent ReAct) and `experts` (multi-expert team).

The legacy `"chat"` mode string is normalized to `"agent"` at session creation (`AgentService`) for backward compatibility with persisted sessions.

## Plan Refinement Queue

When the phase is `PLAN_PENDING`, user messages are enqueued into a bounded `ConcurrentLinkedDeque` (max 5). Messages are drained one-at-a-time under a `ReentrantLock` on `boundedElastic`, ensuring serialized LLM calls. The queue is cleared on `stop()`.
