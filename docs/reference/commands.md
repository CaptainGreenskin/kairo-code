# Commands reference

All 30 REPL slash commands. Type `:help` inside the REPL for the live list.

## Session control

| Command | Purpose |
|---|---|
| `:help` | List all commands with one-line descriptions |
| `:exit` | Quit the REPL (Ctrl+D also works) |
| `:clear` | Drop conversation history (keeps session) |
| `:resume <key>` | Load a saved snapshot into current session |

## Context inspection

| Command | Purpose |
|---|---|
| `:history` | Recent conversation turns |
| `:ctx` | Context window usage + compaction status |
| `:cost` | Token usage + estimated $ for this session |
| `:usage` | Token + iteration counters |
| `:session` | Session summary: duration, turns, tool calls |
| `:stats` | Per-tool call count + success rate + avg duration |
| `:metrics` | Per-turn metrics + kairo.* Micrometer meters |

## Model / mode

| Command | Purpose |
|---|---|
| `:model [name]` | Show or switch the current model |
| `:plan [on\|off\|toggle]` | Plan Mode — blocks write tools |
| `:compact` | Compress conversation history into a summary |

## Capabilities

| Command | Purpose |
|---|---|
| `:skill list\|load <name>\|unload <name>\|info` | Markdown skills loaded into system prompt |
| `:plugin list\|install <source>\|enable\|disable\|info\|reload` | Plugin manager (GitHub / NPM / Git / local) |
| `:hook list` | Auto-registered hooks |
| `:mcp` | MCP client servers |
| `:mcp-server start\|stop\|status` | Run Kairo Code as an MCP server |
| `:lsp status\|diagnostics <file>\|shutdown` | Language Server Protocol diagnostics |

## Persistence

| Command | Purpose |
|---|---|
| `:snapshot save <key>\|list\|delete <key>` | File-backed session snapshots |
| `:memory list\|search <q>\|add\|delete\|info\|clear` | Persistent memory store |
| `:learned list\|approve\|reject\|add\|clear` | Failure-pattern lessons |

## Scheduling

| Command | Purpose |
|---|---|
| `:cron list\|add <m h dom mon dow> <prompt>\|delete <id>\|start\|stop` | Recurring scheduled tasks |

## Multi-agent

| Command | Purpose |
|---|---|
| `:expert <goal>\|plan <goal>\|confirm\|roles\|status` | Launch expert team execution |
| `:team [roles]` | Team status / role list |
| `:swarm` | Swarm coordinator status |

## Diagnostics & lifecycle

| Command | Purpose |
|---|---|
| `:init` | Initialize `.kairo-code/` in current project |
| `:doctor` | Health checks: JDK, git, mvn, .kairo-code state |
| `:evolve [curator start\|stop\|status\|run]` | Self-evolution demo + curator daemon |

## Argument conventions

- Quotes optional unless the argument contains spaces
- Long arguments can span the rest of the line — e.g.
  `:cron add 0 9 * * 1-5 review my open PRs` puts everything after the 5
  cron fields into the prompt
- `:help <command>` is not implemented — see this page or read source

## Adding new commands

Implement `io.kairo.code.cli.SlashCommand`:

```java
public class MyCommand implements SlashCommand {
    @Override public String name() { return "my"; }
    @Override public String description() { return "..."; }
    @Override public void execute(String args, ReplContext ctx) { ... }
}
```

Register in `ReplLoop.createCommandRegistry()`. See existing commands in
`kairo-code-cli/src/main/java/io/kairo/code/cli/commands/` for examples.
