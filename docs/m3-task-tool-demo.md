# M3 Demo — `task` Tool with Worktree Isolation

**Milestone**: 0.1.0-M3 (2026-04-26)
**Audience**: future Devoxx / SpringOne talk; engineers evaluating kairo-code
**Length target**: a 90-second screen recording fits the whole script below.

This walkthrough shows the parent agent delegating a focused sub-goal to a
child agent. The child writes a file in a fresh git worktree, the user is
prompted to merge / discard / keep, and the merge brings the child's commit
back into the parent as a staged squash.

It also demonstrates the **upstream Workspace SPI's first real consumer** —
until M3 the SPI's only implementation was the default
`LocalDirectoryWorkspaceProvider`.

---

## What you'll see

1. A parent kairo-code REPL session in a tiny git repo.
2. The parent calls the `task` tool to spawn a child agent.
3. The child agent runs in `~/.kairo-code/worktrees/<repo>/<task-id>/`,
   visibly prefixed `[task:t-xxxxxxxx] ` in the output.
4. When the child finishes, the prompt:
   `Sub-task t-xxxxxxxx finished: <description>` followed by
   `[m]erge / [d]iscard / [k]eep > `.
5. After choosing `m`, the new file is staged in the parent (`git status`
   shows it ready to commit) and the worktree directory is gone.

---

## Prerequisites

- `git` on `$PATH`.
- `kairo-code` built locally — `mvn -pl kairo-code-cli -am package` in the
  repo root, then `java -jar kairo-code-cli/target/kairo-code-cli-*.jar`.
- An API key for whichever model you've configured (`KAIRO_OPENAI_API_KEY`,
  etc.). The demo flow is model-agnostic; any model that follows tool-call
  instructions works.

---

## Step 1 — set up a sample repo

```bash
$ mkdir /tmp/m3-demo && cd /tmp/m3-demo
$ git init -q -b main
$ git config user.email demo@kairo.io
$ git config user.name "M3 Demo"
$ printf "# m3 demo\n" > README.md
$ git add README.md && git commit -q -m "init"
$ git log --oneline
abc1234 init
```

## Step 2 — launch kairo-code in the repo

```bash
$ kairo-code
Kairo Code v0.1.0 — Same Models. Governable.
Type your request, or :help for commands. :exit to quit.

kairo-code>
```

## Step 3 — ask the parent to use the `task` tool

Type a request that pushes the agent toward `task`. The exact phrasing
depends on the model, but something like this works:

```
kairo-code> Use the task tool to add a Hello.java file with a class Hello
            that prints "hello from M3" in main(). Commit it on the worktree
            branch before finishing.
```

The parent agent responds by emitting a tool call. You'll be prompted to
approve it (`task` is `WRITE` side-effect):

```
✱ Tool: task
  description: add Hello.java
  prompt: Create Hello.java in the working directory with a class Hello
          whose main(String[]) prints "hello from M3". Use git add and
          git commit to stage and commit it before you stop.

Approve? [y/n] > y
```

## Step 4 — child runs in a worktree

The child session boots with its working dir set to the new worktree. Its
streaming output is dim-prefixed `[task:t-1a2b3c4d] ` so you can tell it
apart from the parent's output:

```
[task:t-1a2b3c4d] Reading the working directory…
[task:t-1a2b3c4d] Tool: write_file (Hello.java)
Approve? [y/n] > y
[task:t-1a2b3c4d] Tool: bash (git add Hello.java)
Approve? [y/n] > y
[task:t-1a2b3c4d] Tool: bash (git commit -m "add Hello")
Approve? [y/n] > y
[task:t-1a2b3c4d] Created Hello.java and committed it.
```

(Approvals all funnel through the same `ConsoleApprovalHandler` the parent
uses — one prompt UX across both layers.)

## Step 5 — merge prompt

When the child returns, the parent shows the diff stats and the three-way
prompt:

```
✱ Sub-task t-1a2b3c4d finished: add Hello.java
  Worktree: /Users/you/.kairo-code/worktrees/abc123def456/t-1a2b3c4d
  (1 file(s) changed, +3/-0)
[m]erge / [d]iscard / [k]eep >
```

Type `m` and Enter.

## Step 6 — verify the parent has the file

The merge is a `git merge --squash` — file is **staged** in the parent, not
committed. So the user gets to write the commit message themselves:

```bash
$ git status
On branch main
Changes to be committed:
  (use "git restore --staged <file>..." to unstage)
        new file:   Hello.java

$ cat Hello.java
public class Hello {
    public static void main(String[] args) { System.out.println("hello from M3"); }
}

$ ls ~/.kairo-code/worktrees/abc123def456/
# (empty — the worktree was discarded after the squash-merge)
```

## Variant — discard

Re-run the same prompt; this time choose `d` at the merge prompt. The file
never lands in the parent and the worktree is cleaned up immediately.

```bash
$ git status
nothing to commit, working tree clean
```

## Variant — keep

Choose `k` to leave the worktree on disk. The prompt prints the kept path:

```
✓ kept at /Users/you/.kairo-code/worktrees/abc123def456/t-1a2b3c4d
```

You can `cd` into it later to inspect the branch (`task/t-1a2b3c4d`).

---

## Variant — no isolation (read-only sub-task)

Pass `isolation: "none"` to skip the worktree entirely. Useful when the
sub-agent only needs to read or analyse:

```
kairo-code> Use the task tool with isolation="none" to summarise what
            this repo does in one paragraph.
```

No merge prompt fires; the child runs against the parent working tree
directly and the parent receives the summary verbatim.

---

## What's actually happening (one paragraph)

`TaskTool` (`kairo-code-core/.../task/TaskTool.java`) pulls
`TaskToolDependencies` from the agent's tool-dependency map, builds a
`WorkspaceRequest`, and calls `WorktreeWorkspaceProvider.acquire(...)` —
which delegates to `WorktreeLifecycle` to run `git worktree add -b
task/<id> <path>` and write a `.base` sidecar containing the parent HEAD
SHA. `ChildSessionSpawner.spawn(taskId, workDir)` builds a child
`CodeAgentSession` through `CodeAgentFactory` with
`SessionOptions.asChildSession()` (the factory refuses to register `task`
on a child, so recursion is impossible). After `child.agent().call(...)`
returns, the tool runs `WorktreeLifecycle.diff(...)`, asks
`WorktreeMergePrompter` for the user's choice, then `merge` (squash) /
`discard` (rm -rf) / `keep` (no-op) and finally `provider.release(...)`.
The XML `<task_result>` envelope and the `task.*` metadata are how the
ReAct loop gets the child's outcome back into the parent's context.

---

## Verification (for CI / the talk demo deck)

The whole flow is exercised non-interactively by `TaskToolIT`
(`kairo-code-core/src/test/java/io/kairo/code/core/task/TaskToolIT.java`):

```bash
$ mvn -pl kairo-code-core -am test \
      -Dtest='TaskToolIT#taskToolViaFactoryMergesChildWriteIntoParent'
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

The IT uses a stub model that side-effects a file write + commit on its
first call (no real LLM needed) and asserts:

- the parent registry contains `task`,
- a child `SessionOptions.asChildSession()` does **not** register `task`,
- the squash-merged file appears in the parent working tree,
- the `ToolResult` metadata contains `task.outcome=merge`.

So the demo above is exactly what the regression test guards.
