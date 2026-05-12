/*
 * Copyright 2025-2026 the Kairo authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.kairo.code.core.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentState;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.code.core.workspace.WorktreeLifecycle;
import io.kairo.code.core.workspace.WorktreeWorkspaceProvider;
import io.kairo.core.tool.DefaultPermissionGuard;
import io.kairo.core.tool.DefaultToolExecutor;
import io.kairo.core.tool.DefaultToolRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

class TaskToolTest {

    @BeforeAll
    static void requireGit() {
        assumeTrue(commandAvailable("git"), "git CLI not available; skipping TaskTool e2e tests");
    }

    @Test
    void writeChildMergesIntoParent(@TempDir Path tmp) throws Exception {
        Path repo = initRepoWith(tmp.resolve("parent"), "README.md", "hello\n");
        WorktreeLifecycle lifecycle = new WorktreeLifecycle(tmp.resolve("worktrees"), "git");
        WorktreeWorkspaceProvider provider = new WorktreeWorkspaceProvider(repo, lifecycle);

        AtomicInteger spawnCount = new AtomicInteger();
        ChildSessionSpawner spawner =
                (taskId, workDir) -> {
                    spawnCount.incrementAndGet();
                    return newSession(new CommitFileAgent(workDir, "child.txt", "hi from child\n"));
                };
        WorktreeMergePrompter prompter =
                (taskId, desc, stats, wt) -> Mono.just(WorktreeMergeChoice.MERGE);

        TaskToolDependencies deps = new TaskToolDependencies(provider, spawner, prompter);
        ToolContext ctx = ctxWithDeps(deps);

        TaskTool tool = new TaskTool();
        Map<String, Object> input = new HashMap<>();
        input.put("description", "add greeting file");
        input.put("prompt", "create child.txt");
        input.put("__tool_use_id", "tu-1");

        ToolResult result = tool.execute(input, ctx).block();

        assertThat(result.isError()).isFalse();
        assertThat(spawnCount.get()).isEqualTo(1);
        String content = result.content();
        assertThat(content).contains("<task_result");
        assertThat(content).contains("description=\"add greeting file\"");
        assertThat(content).contains("isolation=\"worktree\"");
        assertThat(content).contains("outcome=\"merge\"");
        assertThat(content).contains("files_changed=\"1\"");
        assertThat(content).contains("wrote child.txt");
        assertThat(content).contains("</task_result>");

        assertThat(result.metadata()).containsEntry("task.outcome", "merge");
        assertThat(result.metadata()).containsEntry("task.isolation", "worktree");
        assertThat(result.metadata()).containsEntry("task.files_changed", 1);

        // Merge brings the child file into parent (staged, not committed).
        assertThat(repo.resolve("child.txt")).exists();
        assertThat(Files.readString(repo.resolve("child.txt"))).isEqualTo("hi from child\n");

        // Worktree dir is gone.
        Path expectedWtRoot = tmp.resolve("worktrees");
        try (var s = Files.walk(expectedWtRoot)) {
            assertThat(s.filter(Files::isRegularFile).toList()).isEmpty();
        }
    }

    @Test
    void discardChoiceLeavesParentUntouched(@TempDir Path tmp) throws Exception {
        Path repo = initRepoWith(tmp.resolve("parent"), "README.md", "hello\n");
        WorktreeLifecycle lifecycle = new WorktreeLifecycle(tmp.resolve("worktrees"), "git");
        WorktreeWorkspaceProvider provider = new WorktreeWorkspaceProvider(repo, lifecycle);

        ChildSessionSpawner spawner =
                (taskId, workDir) ->
                        newSession(new CommitFileAgent(workDir, "child.txt", "discard me\n"));
        WorktreeMergePrompter prompter =
                (taskId, desc, stats, wt) -> Mono.just(WorktreeMergeChoice.DISCARD);
        TaskToolDependencies deps = new TaskToolDependencies(provider, spawner, prompter);

        TaskTool tool = new TaskTool();
        Map<String, Object> input = new HashMap<>();
        input.put("description", "throwaway");
        input.put("prompt", "create child.txt");

        ToolResult result = tool.execute(input, ctxWithDeps(deps)).block();

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("outcome=\"discard\"");
        assertThat(repo.resolve("child.txt")).doesNotExist();
    }

    @Test
    void emptyChildDiffSkipsPromptAndDefaultsToDiscard(@TempDir Path tmp) throws Exception {
        Path repo = initRepoWith(tmp.resolve("parent"), "README.md", "hello\n");
        WorktreeLifecycle lifecycle = new WorktreeLifecycle(tmp.resolve("worktrees"), "git");
        WorktreeWorkspaceProvider provider = new WorktreeWorkspaceProvider(repo, lifecycle);

        ChildSessionSpawner spawner = (taskId, workDir) -> newSession(new NoOpAgent());

        AtomicInteger promptCalls = new AtomicInteger();
        WorktreeMergePrompter prompter =
                (taskId, desc, stats, wt) -> {
                    promptCalls.incrementAndGet();
                    return Mono.just(WorktreeMergeChoice.MERGE);
                };
        TaskToolDependencies deps = new TaskToolDependencies(provider, spawner, prompter);

        TaskTool tool = new TaskTool();
        Map<String, Object> input = new HashMap<>();
        input.put("description", "no-op");
        input.put("prompt", "do nothing");

        ToolResult result = tool.execute(input, ctxWithDeps(deps)).block();

        assertThat(result.isError()).isFalse();
        assertThat(promptCalls.get()).isZero();
        assertThat(result.content()).contains("outcome=\"discard\"");
        assertThat(result.content()).contains("files_changed=\"0\"");
    }

    @Test
    void missingDependenciesReturnsError() {
        TaskTool tool = new TaskTool();
        Map<String, Object> input = new HashMap<>();
        input.put("description", "x");
        input.put("prompt", "y");

        ToolContext ctx = new ToolContext("agent", "session", Map.of());
        ToolResult result = tool.execute(input, ctx).block();

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("TaskToolDependencies not registered");
    }

    @Test
    void blankDescriptionRejected(@TempDir Path tmp) throws Exception {
        Path repo = initRepoWith(tmp.resolve("parent"), "README.md", "hello\n");
        WorktreeLifecycle lifecycle = new WorktreeLifecycle(tmp.resolve("worktrees"), "git");
        WorktreeWorkspaceProvider provider = new WorktreeWorkspaceProvider(repo, lifecycle);
        TaskToolDependencies deps =
                new TaskToolDependencies(
                        provider,
                        (taskId, wd) -> newSession(new NoOpAgent()),
                        (id, d, s, w) -> Mono.just(WorktreeMergeChoice.DISCARD));

        TaskTool tool = new TaskTool();
        Map<String, Object> input = new HashMap<>();
        input.put("description", "  ");
        input.put("prompt", "do something");

        ToolResult result = tool.execute(input, ctxWithDeps(deps)).block();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("description");
    }

    /* ------------------------------------------------------------------ helpers */

    private static ToolContext ctxWithDeps(TaskToolDependencies deps) {
        return new ToolContext(
                "agent-x",
                "session-y",
                Map.of(TaskToolDependencies.class.getName(), deps));
    }

    private static CodeAgentSession newSession(Agent agent) {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        DefaultToolExecutor executor = new DefaultToolExecutor(registry, new DefaultPermissionGuard());
        return new CodeAgentSession(agent, executor, registry, Set.of());
    }

    private static Path initRepoWith(Path repoDir, String firstFile, String content)
            throws Exception {
        Files.createDirectories(repoDir);
        runOk(repoDir, List.of("git", "init", "-q", "-b", "main"));
        runOk(repoDir, List.of("git", "config", "user.email", "t@t"));
        runOk(repoDir, List.of("git", "config", "user.name", "Test"));
        Files.writeString(repoDir.resolve(firstFile), content, StandardCharsets.UTF_8);
        runOk(repoDir, List.of("git", "add", firstFile));
        runOk(repoDir, List.of("git", "commit", "-q", "-m", "init"));
        return repoDir;
    }

    private static void runOk(Path cwd, List<String> command) throws Exception {
        Process p =
                new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("Timeout: " + command);
        }
        if (p.exitValue() != 0) {
            throw new IOException(
                    "Exit "
                            + p.exitValue()
                            + " for "
                            + command
                            + ": "
                            + new String(out, StandardCharsets.UTF_8));
        }
    }

    private static boolean commandAvailable(String cmd) {
        try {
            Process p = new ProcessBuilder(cmd, "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /* ------------------------------------------------------------------ stubs */

    /** Writes a file, stages and commits it inside the working dir, then returns a fixed reply. */
    private static final class CommitFileAgent implements Agent {
        private final Path workDir;
        private final String fileName;
        private final String content;
        private final String id = "fake-" + UUID.randomUUID().toString().substring(0, 6);

        CommitFileAgent(Path workDir, String fileName, String content) {
            this.workDir = workDir;
            this.fileName = fileName;
            this.content = content;
        }

        @Override
        public Mono<Msg> call(Msg input) {
            return Mono.fromCallable(
                    () -> {
                        Files.writeString(workDir.resolve(fileName), content, StandardCharsets.UTF_8);
                        runOk(workDir, List.of("git", "add", fileName));
                        runOk(
                                workDir,
                                List.of(
                                        "git",
                                        "-c",
                                        "user.email=t@t",
                                        "-c",
                                        "user.name=T",
                                        "commit",
                                        "-q",
                                        "-m",
                                        "child commit"));
                        return Msg.of(MsgRole.ASSISTANT, "wrote " + fileName);
                    });
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String name() {
            return "fake-commit-agent";
        }

        @Override
        public AgentState state() {
            return AgentState.IDLE;
        }

        @Override
        public void interrupt() {}
    }

    /** Returns a fixed reply without touching the working directory. */
    private static final class NoOpAgent implements Agent {
        private final String id = "noop-" + UUID.randomUUID().toString().substring(0, 6);

        @Override
        public Mono<Msg> call(Msg input) {
            return Mono.just(Msg.of(MsgRole.ASSISTANT, "no work"));
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String name() {
            return "fake-noop-agent";
        }

        @Override
        public AgentState state() {
            return AgentState.IDLE;
        }

        @Override
        public void interrupt() {}
    }
}
