# Hooks

User-defined shell commands or Java listeners that fire at lifecycle points
in the agent loop. Useful for shell escapes (clear screen on
prompt-submit, post a Slack message when a task completes) or for custom
auditing / observability.

## Lifecycle phases

10 fire points, in order:

| Phase | When |
|---|---|
| `SESSION_START` | Agent session begins |
| `PRE_REASONING` | Before each model call |
| `POST_REASONING` | After model response arrives |
| `PRE_ACTING` | Before tool execution |
| `POST_ACTING` | After tool result available |
| `PRE_COMPACT` | Before context compaction runs |
| `POST_COMPACT` | After compaction completes |
| `TOOL_RESULT` | After each tool produces a result |
| `PRE_COMPLETE` | Model returned final answer (no tool call) |
| `SESSION_END` | Session ends (success or failure) |

Plus the extended set: `USER_PROMPT_SUBMIT`, `PERMISSION_REQUEST`,
`SUBAGENT_START`, `WORKTREE_CREATE`, etc. — see `HookPhase` enum in
[kairo-api](https://github.com/captaingreenskin/kairo).

## Shell hooks (config-driven)

Drop a file at `~/.kairo-code/hooks.json`:

```json
{
  "user-prompt-submit": [
    { "command": "clear" }
  ],
  "session-end": [
    { "command": "osascript -e 'display notification \"task done\" with title \"kairo-code\"'" }
  ],
  "post-tool-execute": [
    { "command": "git add -A 2>/dev/null", "matcher": "write" }
  ]
}
```

`matcher` is optional — without it the hook fires for every event at that
phase. With it, fires only when the tool name (or other event field) matches.

## Java hooks (programmatic)

If you're embedding via the SDK:

```java
public class MyHook {
    @HookHandler(HookPhase.POST_REASONING)
    public HookResult<PostReasoningEvent> onResponse(PostReasoningEvent e) {
        System.err.println("model returned " + e.response().contents().size() + " content items");
        return HookResult.proceed(e);
    }
}

CodeAgentFactory.SessionOptions opts = CodeAgentFactory.SessionOptions.empty()
        .withHooks(List.of(new MyHook()));
```

Return `HookResult.proceed(e)` to continue, `HookResult.modify(modifiedEvent)`
to swap in a different event, or `HookResult.abort(reason)` to short-circuit.

## Inspection

`:hook` lists all auto-registered + user-configured hooks. Useful when
debugging "why did this thing fire?".
