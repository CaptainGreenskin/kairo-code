# sample-subagents

Reference plugin showing the `agents/*.md` subagent format consumed by
kairo's `SubagentRegistry` + `AgentSpawnTool`.

Two subagents are bundled:

| File                            | Role          | Tool whitelist          | Model pin                       |
|---------------------------------|---------------|-------------------------|---------------------------------|
| `agents/code-reviewer.md`       | Code reviewer | read, grep, glob, diff  | `claude-haiku-4-5-20251001`     |
| `agents/test-runner.md`         | Test triage   | bash, read, grep        | inherit parent                  |

## Install (from a kairo-code checkout)

```
:plugin install path:./kairo-code-examples/plugins/sample-subagents
:plugin enable sample-subagents
```

Once enabled, both subagents are registered as
`sample-subagents:code-reviewer` and `sample-subagents:test-runner` in
the framework's `SubagentRegistry`.

## Driving them from the SDK

The kairo-code REPL routes child sessions through the `task` tool today
(see `docs/m3-task-tool-demo.md`). To drive a plugin-contributed subagent
directly via the framework's `agent_spawn` path, embed via the SDK:

```java
SubagentRegistry registry = pluginManager.subagentRegistry();
AgentSpawnTool spawn = new AgentSpawnTool(agentFactory, parentConfig, registry);

Mono<ToolResult> result = spawn.execute(
    Map.of(
        "subagent_type", "sample-subagents:code-reviewer",
        "task", "review the diff in /tmp/pr-42.patch"),
    toolContext);
```

The tool will:

1. Resolve `sample-subagents:code-reviewer` via the registry.
2. Build a child `AgentConfig` whose `systemPrompt` is the markdown body,
   `modelName` is the frontmatter pin, and `toolRegistry` is filtered to
   just `read`, `grep`, `glob`, `diff` from the parent's registry.
3. Spawn a fresh agent (clean context — no parent conversation inherited)
   and return its final message text.
