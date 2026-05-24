# Plugins

Bundled units of skills + commands + agents + hooks + MCP servers,
distributable via GitHub / NPM / git / filesystem.

Backed by [kairo-plugin](https://github.com/captaingreenskin/kairo) upstream;
reads the Claude Code-compatible `plugin.json` manifest format.

## Install

```
:plugin install github:owner/repo[#ref]      # GitHub archive
:plugin install npm:@scope/package[@version] # NPM tarball
:plugin install git:https://...[#branch]     # arbitrary git
:plugin install path:./my-plugin             # local filesystem
```

After install, `enable` to activate:

```
:plugin list                        # find the id
:plugin enable <id>
:skill reload                       # pick up newly-contributed skills
```

## Authoring

Minimum viable plugin:

```
my-plugin/
├── plugin.json
└── skills/
    └── my-skill.md
```

`plugin.json`:

```json
{
  "name": "my-plugin",
  "version": "0.1.0",
  "description": "Adds my custom skill",
  "author": "@me",
  "license": "MIT"
}
```

The `skills/` directory follows the same format as user skills (see
[Skills](./skills)). Other supported directories: `commands/`, `agents/`,
`hooks/`, `mcp/`, `bin/`, `output-styles/`.

## Subagents (`agents/*.md`)

Each `.md` file in `agents/` declares one subagent — an LLM persona with
its own system prompt, an optional tool whitelist, and an optional model
pin. The filename minus `.md` is the default subagent name; the YAML
frontmatter overrides metadata; the body is the system prompt.

```markdown
---
name: code-reviewer
description: Reviews code diffs and flags issues by severity
model: claude-haiku-4-5-20251001   # optional — null = inherit parent
tools:                              # optional — empty = inherit parent
  - read
  - grep
---
You are a meticulous code reviewer.

Read the diff and report issues by severity:
- BLOCKER: must fix before merge (correctness, security)
- MAJOR: should fix (perf, maintainability)
- MINOR: nit / style
```

Frontmatter fields are all optional:

| Field         | Default               | Purpose                                          |
|---------------|-----------------------|--------------------------------------------------|
| `name`        | filename minus `.md`  | Subagent name; combined with plugin namespace.   |
| `description` | empty string          | Hint to the parent agent when deciding to delegate. |
| `model`       | null (inherit parent) | Model alias to pin — e.g. cheap haiku for triage. |
| `tools`       | `[]` (inherit parent) | Whitelist by tool name; unknown names dropped.   |

Once the plugin is enabled, subagents are registered in the
`SubagentRegistry` under the qualified name `<plugin-namespace>:<name>`
(or just `<name>` if the plugin is unnamespaced). Programs embedding
Kairo can resolve them via `SubagentRegistry.get(qualifiedName)` and
spawn them through the framework's `agent_spawn` tool.

> **kairo-code REPL note (2026-05):** the REPL today uses the `task`
> tool for child sessions (see [m3-task-tool-demo](../m3-task-tool-demo)).
> Plugin-contributed subagents are loaded and registered, but a unified
> wiring that lets the REPL spawn them as first-class personas alongside
> the expert-role mechanism is being designed — track in [ADR-031 (draft)].
> Use the SDK path below if you need to drive a plugin subagent today.
>
> A bundled reference plugin lives at
> [`kairo-code-examples/plugins/sample-subagents`](https://github.com/captaingreenskin/kairo-code/tree/main/kairo-code-examples/plugins/sample-subagents)
> with two ready-to-use subagents (`code-reviewer`, `test-runner`).

```java
// Embedding path: resolve a plugin-contributed subagent and spawn it
SubagentRegistry registry = pluginManager.subagentRegistry();
AgentSpawnTool spawn = new AgentSpawnTool(agentFactory, parentConfig, registry);
ToolResult result = spawn.execute(
    Map.of("subagent_type", "my-plugin:code-reviewer",
           "task", "review the diff in /tmp/pr-42.patch"),
    toolContext).block();
```

## Variable substitution

Use `${KAIRO_PLUGIN_ROOT}` in any plugin file to refer to its install
location:

```json
{
  "mcp": [{
    "command": "node",
    "args": ["${KAIRO_PLUGIN_ROOT}/server.js"]
  }]
}
```

`${CLAUDE_PLUGIN_ROOT}` is also accepted for Claude Code plugin compatibility.

## Storage

Plugins live under:

- `~/.kairo-code/plugins/cache/<sha8>/` — extracted source artifacts
- `~/.kairo-code/plugins/data/<plugin-name>/` — runtime data dirs

`:plugin uninstall <id>` removes both. `:plugin update <id>` re-fetches.

## Lifecycle events

Subscribe via `manager.events()` if you're embedding via the SDK:

```java
manager.events()
    .filter(PluginEvent.Installed.class::isInstance)
    .subscribe(e -> System.out.println("installed: " + e));
```

Useful for IDE plugins that want to refresh their UI when plugins change.
