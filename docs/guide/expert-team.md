# Expert team

Spawn N worker agents that plan → generate → evaluate in parallel. Use when
a goal naturally splits into independent sub-tasks (research + draft +
review, plan + book + remind, etc).

## REPL workflow

```
kairo-code> :expert plan refactor the auth module
[generates a plan, shows it for review]

kairo-code> :expert confirm
[runs the plan on the worker pool]

kairo-code> :expert status
[shows the last execution result]
```

`:expert <goal>` (no subcommand) plans + executes in one step without the
confirmation gate.

## REPL helpers

```
:swarm         # coordinator status + registered roles
:team          # team summary
:expert roles  # available expert roles
```

## Programmatic use

Built on the upstream
[ExpertTeamComposer](https://github.com/captaingreenskin/kairo/blob/main/kairo-expert-team/src/main/java/io/kairo/expertteam/ExpertTeamComposer.java):

```java
ExpertTeamComposer.Composition c = ExpertTeamComposer.create(
        3,
        () -> myAgentFactory.createSession(myConfig).agent());

Team team = new Team("my-team", c.agents(), c.messageBus());
TeamExecutionRequest req = new TeamExecutionRequest(
        UUID.randomUUID().toString(), "the goal", Map.of(), TeamConfig.defaults());
TeamResult result = c.coordinator().execute(req, team).block();
```

The kairo-code-cli ships with `ExpertTeamFactory` that wires this up using
`CodeAgentFactory` workers automatically — see how `:expert` is implemented
for the reference plumbing.

## Worker count

Default 3 workers. Tune via `agentCount`:

- **1** — sequential, useful for plan-only flows
- **3** (default) — balances parallelism + token cost for typical goals
- **5–10** — large parallel tasks where sub-goals are truly independent
- **>10** — usually a sign you should decompose the goal first

Each worker is a full agent — same hooks, same tools, same observability.
Costs scale linearly with worker count.

## Assistant variant

[kairo-assistant](https://github.com/captaingreenskin/kairo-assistant) ships
an `ExpertTeamTool` that the chat agent invokes when the user gives it a
multi-step goal. Pattern is mirrored here for code-agent use.
