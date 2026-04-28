# Implement TurnMetricsCollector + /metrics Command

Working directory: kairo-code project root.

## Goal

Add per-turn metrics tracking to kairo-code and expose it via a new `/metrics` REPL command.

## What to implement

### 1. `TurnMetricsCollector.java`

File: `kairo-code-core/src/main/java/io/kairo/code/core/stats/TurnMetricsCollector.java`

Track per-turn data using existing hook SPI (`@OnToolResult`, `@io.kairo.api.hook.PostReasoning` or similar).

```java
public final class TurnMetricsCollector {
    // Fields per turn: turnNumber, toolCallCount, successCount, totalDurationMillis
    // Session aggregates updated after each turn

    // Must expose:
    public int totalTurns();
    public int totalToolCalls();
    public double avgToolCallsPerTurn();
    public long totalDurationMillis();
    public long avgDurationPerTurnMillis();
    public List<TurnSnapshot> turnSnapshots();  // immutable list, one per completed turn
    public TurnSnapshot lastTurn();              // null if no turns yet

    // TurnSnapshot is an immutable value record:
    record TurnSnapshot(int turnNumber, int toolCalls, int successes, long durationMillis) {}
}
```

Use `@OnToolResult` to count tool calls per turn. You need a way to detect "turn boundary"
(when the model stops calling tools and produces a text response). Look at how
`ToolUsageTracker` works — build on the same pattern.

### 2. `MetricsCommand.java`

File: `kairo-code-cli/src/main/java/io/kairo/code/cli/commands/MetricsCommand.java`

```java
public class MetricsCommand implements SlashCommand {
    // name(): "metrics"
    // description(): "Show per-turn metrics: tool calls, duration, totals"
    // execute(): print formatted table like StatsCommand does
}
```

Output format (reference `StatsCommand.java`):
```
Turn Metrics
──────────────────────────────────────────
Turn    Tools  Success  Duration(ms)
──────────────────────────────────────────
   1        3      100          4521
   2        5       80          8234
──────────────────────────────────────────
Totals: 2 turns, 8 tool calls, avg 6377ms/turn
```

### 3. Wire up

- `CodeAgentFactory.java`: register `TurnMetricsCollector` as a hook (non-REPL sessions)
- `CodeAgentSession.java`: expose `turnMetricsCollector()` getter (like `toolUsageTracker()`)
- `CommandRegistry` registration in `ReplLoop.java` or wherever StatsCommand is registered

### 4. Tests

- `TurnMetricsCollectorTest.java` — unit tests for the collector:
  - empty state returns zeros
  - one turn with 3 tool calls recorded correctly
  - avgToolCallsPerTurn computed correctly
  - turnSnapshots returns immutable list
  - at least 4 test methods

- `MetricsCommandTest.java` — unit test for command output:
  - empty state prints "No metrics yet" or similar
  - one turn: output contains turn number and tool count
  - at least 2 test methods

## Rules

- Follow existing patterns in `ToolUsageTracker` + `StatsCommand` exactly.
- Do NOT modify test files you didn't create.
- Run `mvn clean verify` when done — all existing tests must still pass, plus new ones green.
- Commit: `feat(core/cli): add TurnMetricsCollector and /metrics command`
