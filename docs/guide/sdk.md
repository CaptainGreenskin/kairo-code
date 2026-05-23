# Java SDK

Embed Kairo Code in any JVM app — IDE plugin, CI script, eval harness — without
spawning the CLI.

## Maven

```xml
<dependency>
    <groupId>io.kairo.code</groupId>
    <artifactId>kairo-code-core</artifactId>
    <version>0.2.0-SNAPSHOT</version>
</dependency>
```

That's it. `kairo-code-core` brings in the full Kairo SPI (agent, tools, hooks,
plugin, skills, security-pii, evolution, lsp, observability) transitively.
No Spring required.

## One-shot

```java
import io.kairo.code.KairoCodeClient;

KairoCodeClient client = KairoCodeClient.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .model("gpt-4o")
        .workingDir(java.nio.file.Path.of("/my/project"))
        .build();

String summary = client.task("explain the public API of FooService").block();
System.out.println(summary);
```

`block()` is a Reactor convenience — use `.subscribe(...)` /
`.toFuture()` to integrate with your runtime instead.

## Multi-turn

```java
import io.kairo.code.KairoCodeSession;

try (KairoCodeSession session = client.openSession()) {
    String r1 = session.send("look at FooService").block();
    String r2 = session.send("now add a test for the edge case").block();
}
```

History persists across `send()` calls within a session. Each `openSession()`
returns a fresh conversation — same client, fresh history.

## Other providers

The builder lets you swap any OpenAI-compatible endpoint:

```java
KairoCodeClient minimax = KairoCodeClient.builder()
        .apiKey(System.getenv("MINIMAX_API_KEY"))
        .baseUrl("https://api.minimaxi.com")
        .chatPath("/v1/chat/completions")
        .model("MiniMax-M2")
        .build();
```

For Anthropic native (not OpenAI-compatible) the underlying
`CodeAgentFactory.buildModelProvider` routes through `AnthropicProvider`
automatically when `baseUrl` matches `*anthropic*`.

## Advanced control

For full access to hooks, snapshots, interruption, custom tool registries —
reach into the underlying machinery:

```java
try (KairoCodeSession session = client.openSession()) {
    // Direct Agent — interrupt mid-call
    new Thread(() -> {
        try { Thread.sleep(2000); } catch (InterruptedException e) {}
        session.interrupt();
    }).start();

    String partial = session.send("write a long essay").block();

    // Full CodeAgentSession — tools, mcp registry, metrics
    var stats = session.underlying().toolUsageTracker();
    System.out.println("tool calls: " + stats.totalCalls());
}
```

When you outgrow the SDK's defaults, drop down to
`io.kairo.code.core.CodeAgentFactory.createSession(config, SessionOptions)`
directly — same module, no compatibility break.

## Threading

`KairoCodeClient` is safe to share across threads (immutable after build).
Each `task()` / `openSession()` call creates a fresh session, so concurrent
calls run in parallel without locking.

## Lifecycle

`KairoCodeSession` implements `AutoCloseable` — use `try-with-resources` so
hook registrations and any spawned worktrees release on the way out.

`KairoCodeClient` has no shutdown hook in 0.2 — its only state is the
ModelProvider (HTTP client) which JVM exit GCs. Future versions may add
`client.close()` if we add resource pools.
