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

import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.model.ModelConfig;
import io.kairo.api.model.ModelProvider;
import io.kairo.api.model.ModelResponse;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolHandler;
import io.kairo.api.tool.ToolResult;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentFactory;
import io.kairo.code.core.CodeAgentFactory.SessionOptions;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.code.core.workspace.WorktreeLifecycle;
import io.kairo.code.core.workspace.WorktreeWorkspaceProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * End-to-end wiring test for {@link TaskTool} via {@link CodeAgentFactory}.
 *
 * <p>Verifies the factory contract:
 *
 * <ul>
 *   <li>Parent session has the {@code task} tool when {@link
 *       SessionOptions#withTaskTool(TaskToolDependencies)} is set.
 *   <li>Child session ({@link SessionOptions#asChildSession()}) does NOT have {@code task}
 *       registered — recursion is out of scope for M3.
 *   <li>Invoking the registered handler from the parent session spawns a child via {@link
 *       ChildSessionSpawner} (also built through the factory), runs it in a worktree, and the
 *       merge brings child changes into the parent.
 * </ul>
 */
class TaskToolIT {

    private static final CodeAgentConfig CONFIG =
            new CodeAgentConfig("test-key", "https://api.openai.com", "gpt-4o", 50, null);

    @BeforeAll
    static void requireGit() {
        assumeTrue(commandAvailable("git"), "git CLI not available; skipping TaskTool IT");
    }

    @Test
    void parentRegistersTaskToolChildDoesNot(@TempDir Path tmp) throws Exception {
        Path repo = initRepoWith(tmp.resolve("parent"), "README.md", "hello\n");
        TaskToolDependencies deps = stubDeps(repo, tmp);

        SessionOptions parentOpts =
                SessionOptions.empty()
                        .withModelProvider(new StubModelProvider())
                        .withTaskTool(deps);
        CodeAgentSession parent = CodeAgentFactory.createSession(CONFIG, parentOpts);
        assertThat(parent.toolRegistry().get("task")).isPresent();

        SessionOptions childOpts =
                SessionOptions.empty()
                        .withModelProvider(new StubModelProvider())
                        .withTaskTool(deps)
                        .asChildSession();
        CodeAgentSession child = CodeAgentFactory.createSession(CONFIG, childOpts);
        assertThat(child.toolRegistry().get("task")).isEmpty();
    }

    @Test
    void taskToolViaFactoryMergesChildWriteIntoParent(@TempDir Path tmp) throws Exception {
        Path repo = initRepoWith(tmp.resolve("parent"), "README.md", "hello\n");
        WorktreeLifecycle lifecycle = new WorktreeLifecycle(tmp.resolve("worktrees"), "git");
        WorktreeWorkspaceProvider provider = new WorktreeWorkspaceProvider(repo, lifecycle);

        AtomicInteger childSpawns = new AtomicInteger();
        ChildSessionSpawner spawner =
                (taskId, workDir) -> {
                    childSpawns.incrementAndGet();
                    // Child uses the real factory — proves child wiring works end-to-end.
                    SessionOptions childOpts =
                            SessionOptions.empty()
                                    .withModelProvider(new FileWritingChildModel(workDir, "hello.java"))
                                    .asChildSession();
                    return CodeAgentFactory.createSession(CONFIG, childOpts);
                };

        WorktreeMergePrompter prompter =
                (taskId, desc, stats, wt) -> Mono.just(WorktreeMergeChoice.MERGE);
        TaskToolDependencies deps = new TaskToolDependencies(provider, spawner, prompter);

        // Build parent through the factory (this is what the IT proves).
        CodeAgentSession parent =
                CodeAgentFactory.createSession(
                        CONFIG,
                        SessionOptions.empty()
                                .withModelProvider(new StubModelProvider())
                                .withTaskTool(deps));

        // Resolve the registered TaskTool handler from the parent registry.
        Object handler = parent.toolRegistry().getToolInstance("task");
        assertThat(handler).isInstanceOf(ToolHandler.class);
        ToolHandler taskHandler = (ToolHandler) handler;

        Map<String, Object> input = new HashMap<>();
        input.put("description", "create hello.java");
        input.put("prompt", "write a Java file");
        input.put("__tool_use_id", "tu-it-1");

        ToolContext ctx =
                new ToolContext(
                        "agent-it",
                        "session-it",
                        Map.of(TaskToolDependencies.class.getName(), deps));

        ToolResult result = taskHandler.execute(input, ctx);

        assertThat(result.isError()).isFalse();
        assertThat(childSpawns.get()).isEqualTo(1);
        assertThat(result.content()).contains("outcome=\"merge\"");
        assertThat(result.content()).contains("isolation=\"worktree\"");
        assertThat(result.metadata()).containsEntry("task.outcome", "merge");

        // Child's write made it into the parent (staged after squash-merge).
        Path merged = repo.resolve("hello.java");
        assertThat(merged).exists();
        assertThat(Files.readString(merged)).contains("class Hello");
    }

    /* ------------------------------------------------------------------ helpers */

    /** Small dependencies bundle used when only registry presence is being tested. */
    private static TaskToolDependencies stubDeps(Path repo, Path tmp) {
        WorktreeLifecycle lifecycle = new WorktreeLifecycle(tmp.resolve("worktrees-stub"), "git");
        WorktreeWorkspaceProvider provider = new WorktreeWorkspaceProvider(repo, lifecycle);
        return new TaskToolDependencies(
                provider,
                (taskId, wd) ->
                        CodeAgentFactory.createSession(
                                CONFIG,
                                SessionOptions.empty()
                                        .withModelProvider(new StubModelProvider())
                                        .asChildSession()),
                (taskId, desc, stats, wt) -> Mono.just(WorktreeMergeChoice.DISCARD));
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

    /** Returns a fixed text response on every call. */
    static class StubModelProvider implements ModelProvider {
        @Override
        public Mono<ModelResponse> call(List<Msg> messages, ModelConfig config) {
            List<Content> contents = List.of(new Content.TextContent("stub"));
            return Mono.just(new ModelResponse("stub", contents, null, null, "stub"));
        }

        @Override
        public Flux<ModelResponse> stream(List<Msg> messages, ModelConfig config) {
            return Flux.from(call(messages, config));
        }

        @Override
        public String name() {
            return "stub";
        }
    }

    /**
     * Stub child model that, on first call, side-effects a file write + git commit in {@code
     * workDir}, then returns a final text message. Bypasses the ReAct loop's tool-call cycle —
     * the agent loop just sees a textual response and finishes.
     */
    static class FileWritingChildModel implements ModelProvider {
        private final Path workDir;
        private final String fileName;

        FileWritingChildModel(Path workDir, String fileName) {
            this.workDir = workDir;
            this.fileName = fileName;
        }

        @Override
        public Mono<ModelResponse> call(List<Msg> messages, ModelConfig config) {
            return Mono.fromCallable(
                    () -> {
                        Path target = workDir.resolve(fileName);
                        if (!Files.exists(target)) {
                            Files.writeString(
                                    target,
                                    "public class Hello { public static void main(String[] a){} }\n",
                                    StandardCharsets.UTF_8);
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
                        }
                        return new ModelResponse(
                                "child-resp",
                                List.of(new Content.TextContent("created " + fileName)),
                                null,
                                null,
                                "stub");
                    });
        }

        @Override
        public Flux<ModelResponse> stream(List<Msg> messages, ModelConfig config) {
            return Flux.from(call(messages, config));
        }

        @Override
        public String name() {
            return "file-writing-stub";
        }
    }
}
