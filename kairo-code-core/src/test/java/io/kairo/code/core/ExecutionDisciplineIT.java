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
package io.kairo.code.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.code.core.task.ChildSessionSpawner;
import io.kairo.code.core.task.TaskToolDependencies;
import io.kairo.code.core.task.WorktreeMergeChoice;
import io.kairo.code.core.task.WorktreeMergePrompter;
import io.kairo.code.core.workspace.WorktreeLifecycle;
import io.kairo.code.core.workspace.WorktreeWorkspaceProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

/**
 * Integration test that verifies execution discipline: the agent actually
 * performs work (modifies files) rather than only outputting a plan.
 *
 * <p>Requires a real API key (KAIRO_CODE_API_KEY env). Disabled by default.
 */
@Disabled("Requires real API key — run manually with KAIRO_CODE_API_KEY set")
class ExecutionDisciplineIT {

    private static final String API_KEY = System.getenv("KAIRO_CODE_API_KEY");
    private static final String BASE_URL =
            System.getenv().getOrDefault("KAIRO_CODE_BASE_URL", "https://api.openai.com");
    private static final String MODEL =
            System.getenv().getOrDefault("KAIRO_CODE_MODEL", "gpt-4o");

    @BeforeAll
    static void requireApiKey() {
        assumeTrue(API_KEY != null && !API_KEY.isBlank(), "KAIRO_CODE_API_KEY not set");
        assumeTrue(commandAvailable("git"), "git CLI not available");
    }

    @Test
    void agentExecutesNotJustPlans(@TempDir Path tmp) throws Exception {
        Path repo = initRepoWith(tmp.resolve("repo"), "TokenBucket.java",
                """
                        public class TokenBucket {
                            private final int capacity;
                            private double tokens;
                            private final double refillRate;
                            private long lastRefillTime;

                            public TokenBucket(int capacity, double refillRate) {
                                this.capacity = capacity;
                                this.tokens = capacity;
                                this.refillRate = refillRate;
                                this.lastRefillTime = System.currentTimeMillis();
                            }

                            public synchronized boolean consume(int n) {
                                refill();
                                if (tokens >= n) {
                                    tokens -= n;
                                    return true;
                                }
                                return false;
                            }

                            private void refill() {
                                long now = System.currentTimeMillis();
                                long elapsed = now - lastRefillTime;
                                tokens = Math.min(capacity, tokens + elapsed * refillRate / 1000);
                                lastRefillTime = now;
                            }
                        }
                        """);

        CodeAgentConfig config = new CodeAgentConfig(
                API_KEY, BASE_URL, MODEL, 30, repo.toString(), null);

        // Wire task tool with a spawner that uses the real factory for child sessions
        WorktreeLifecycle lifecycle = new WorktreeLifecycle(tmp.resolve("worktrees"), "git");
        WorktreeWorkspaceProvider provider = new WorktreeWorkspaceProvider(repo, lifecycle);
        AtomicInteger childSpawns = new AtomicInteger();
        ChildSessionSpawner spawner = (taskId, workDir) -> {
            childSpawns.incrementAndGet();
            return CodeAgentFactory.createSession(
                    config,
                    CodeAgentFactory.SessionOptions.empty().asChildSession());
        };
        WorktreeMergePrompter prompter =
                (taskId, desc, stats, wt) -> Mono.just(WorktreeMergeChoice.MERGE);
        TaskToolDependencies deps = new TaskToolDependencies(provider, spawner, prompter);

        CodeAgentSession session = CodeAgentFactory.createSession(
                config,
                CodeAgentFactory.SessionOptions.empty()
                        .withTaskTool(deps));

        // The discipline prefix that one-shot mode adds
        String disciplinePrefix =
                "Complete this task fully. Use your tools to investigate, implement, and verify."
                        + " Do not stop after planning — execute each step with tool calls.\n\n";

        String task = disciplinePrefix +
                "Fix the bug in TokenBucket.java: the refill calculation is wrong — "
                        + "elapsed time should be converted to seconds before multiplying by refillRate. "
                        + "The current code divides by 1000 inside the refill formula but applies "
                        + "the rate per second incorrectly. Fix it so the bucket refills correctly.";

        Msg userMsg = Msg.of(MsgRole.USER, task);
        Msg response = session.agent().call(userMsg).block();

        assertThat(response).isNotNull();
        // If execution discipline works, the agent will have modified the file
        // rather than just outputting a plan
        String fileContent = Files.readString(repo.resolve("TokenBucket.java"));
        // The fix should change the refill logic — assert the file was modified
        // (different from the original content we wrote)
        assertThat(fileContent).isNotEqualTo(
                """
                        public class TokenBucket {
                            private final int capacity;
                            private double tokens;
                            private final double refillRate;
                            private long lastRefillTime;

                            public TokenBucket(int capacity, double refillRate) {
                                this.capacity = capacity;
                                this.tokens = capacity;
                                this.refillRate = refillRate;
                                this.lastRefillTime = System.currentTimeMillis();
                            }

                            public synchronized boolean consume(int n) {
                                refill();
                                if (tokens >= n) {
                                    tokens -= n;
                                    return true;
                                }
                                return false;
                            }

                            private void refill() {
                                long now = System.currentTimeMillis();
                                long elapsed = now - lastRefillTime;
                                tokens = Math.min(capacity, tokens + elapsed * refillRate / 1000);
                                lastRefillTime = now;
                            }
                        }
                        """);
    }

    /* ------------------------------------------------------------------ helpers */

    private static Path initRepoWith(Path repoDir, String fileName, String content)
            throws Exception {
        Files.createDirectories(repoDir);
        runOk(repoDir, List.of("git", "init", "-q", "-b", "main"));
        runOk(repoDir, List.of("git", "config", "user.email", "t@t"));
        runOk(repoDir, List.of("git", "config", "user.name", "Test"));
        Files.writeString(repoDir.resolve(fileName), content, StandardCharsets.UTF_8);
        runOk(repoDir, List.of("git", "add", fileName));
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
}
