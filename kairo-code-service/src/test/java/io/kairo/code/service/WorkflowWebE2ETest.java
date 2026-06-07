package io.kairo.code.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.model.ModelConfig;
import io.kairo.api.model.ModelProvider;
import io.kairo.api.model.ModelResponse;
import io.kairo.code.core.CodeAgentConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * True web-layer E2E: AgentService → ReAct loop → model returns scripted_workflow tool call
 * → GraalJS engine executes script → child agents spawned → results aggregated → model
 * summarizes → events streamed back.
 *
 * <p>No HTTP/WS transport (that's tested by server module); this exercises the full
 * AgentService → Agent → Tool → Engine → Runtime → ChildSpawner path.
 */
class WorkflowWebE2ETest {

    @BeforeAll
    static void requireGit() {
        assumeTrue(commandAvailable("git"), "git CLI required");
    }

    @Test
    void fullReActLoopWithScriptedWorkflow(@TempDir Path tmp) throws Exception {
        Path repo = initRepo(tmp);
        AtomicInteger childCalls = new AtomicInteger();

        // The workflow script: discover → parallel verify → aggregate
        String workflowScript = """
                export const meta = {
                    name: 'e2e-review',
                    description: 'End-to-end code review workflow',
                    phases: [
                        { title: 'Discover', detail: 'Find issues' },
                        { title: 'Verify', detail: 'Confirm findings' }
                    ]
                }

                phase('Discover')
                log('Starting discovery...')
                const discovery = await agent('Scan the codebase for bugs', {
                    schema: {type: 'object'},
                    label: 'scanner'
                })

                phase('Verify')
                log('Verifying ' + discovery.issues.length + ' issues...')
                const verified = await parallel(
                    discovery.issues.map(issue => ({
                        prompt: 'Verify: ' + issue.title + ' in ' + issue.file,
                        label: 'verify:' + issue.file
                    }))
                )

                const confirmed = verified.filter(v => v !== null && v.includes('CONFIRMED'))
                return {
                    total_scanned: discovery.issues.length,
                    confirmed_count: confirmed.length,
                    details: confirmed
                }
                """;

        // Parent model: first call → tool_use(scripted_workflow), second call → text summary
        AtomicInteger parentCallCount = new AtomicInteger();
        ModelProvider parentModel = new ModelProvider() {
            @Override
            public Mono<ModelResponse> call(List<Msg> messages, ModelConfig config) {
                int call = parentCallCount.incrementAndGet();
                if (call == 1) {
                    // Model decides to use scripted_workflow
                    Content.ToolUseContent toolCall = new Content.ToolUseContent(
                            "tc-001", "scripted_workflow",
                            Map.of("script", workflowScript));
                    return Mono.just(new ModelResponse("r1", List.of(toolCall),
                            null, ModelResponse.StopReason.TOOL_USE, "test"));
                }
                // After receiving tool result, model summarizes
                return Mono.just(new ModelResponse("r2",
                        List.of(new Content.TextContent(
                                "Workflow completed. Found 3 issues, 2 confirmed as real bugs.")),
                        null, ModelResponse.StopReason.END_TURN, "test"));
            }

            @Override
            public Flux<ModelResponse> stream(List<Msg> messages, ModelConfig config) {
                return Flux.from(call(messages, config));
            }

            @Override
            public String name() { return "e2e-parent"; }
        };

        // Child model: scanner returns JSON, verifiers return text
        ModelProvider childModel = new ModelProvider() {
            @Override
            public Mono<ModelResponse> call(List<Msg> messages, ModelConfig config) {
                int n = childCalls.incrementAndGet();
                String lastMsg = messages.get(messages.size() - 1).text();
                String response;
                if (lastMsg.contains("schema")) {
                    // Discovery agent — return structured JSON
                    response = """
                            {"issues": [
                                {"title": "Null pointer in UserService", "file": "UserService.java", "line": 42},
                                {"title": "SQL injection in QueryBuilder", "file": "QueryBuilder.java", "line": 15},
                                {"title": "Race condition in Cache", "file": "Cache.java", "line": 88}
                            ]}""";
                } else if (lastMsg.contains("Race condition")) {
                    response = "NOT_AN_ISSUE: This is protected by synchronized block";
                } else {
                    response = "CONFIRMED: Verified as a real bug";
                }
                return Mono.just(new ModelResponse("c" + n,
                        List.of(new Content.TextContent(response)),
                        null, ModelResponse.StopReason.END_TURN, "test"));
            }

            @Override
            public Flux<ModelResponse> stream(List<Msg> messages, ModelConfig config) {
                return Flux.from(call(messages, config));
            }

            @Override
            public String name() { return "e2e-child"; }
        };

        // Build the AgentService and inject our model providers
        AgentService service = new AgentService();

        // Create config with repo as working dir
        CodeAgentConfig config = new CodeAgentConfig(
                "test-key", "http://localhost:9999", "test-model",
                10, repo.toString(), null, 0, 0);

        // Create session — this wires TaskToolDependencies → WorkflowDependencies
        String sessionId = service.createSession(config);

        // Replace model providers in the session
        // We need to inject our models. Since AgentService.createSession uses
        // CodeAgentFactory internally, and the model is built from config, we need
        // to use createSession with a custom ModelProvider.
        // Let's use the overload that accepts options
        service.destroySession(sessionId);

        // Use CodeAgentFactory directly with our models
        var taskDeps = buildTaskDeps(repo, tmp, childModel);
        var opts = io.kairo.code.core.CodeAgentFactory.SessionOptions.empty()
                .withModelProvider(parentModel)
                .withTaskTool(taskDeps);
        var session = io.kairo.code.core.CodeAgentFactory.createSession(config, opts);

        assertThat(session.toolRegistry().get("scripted_workflow")).isPresent();

        // Run the full ReAct loop
        List<String> events = new CopyOnWriteArrayList<>();
        Msg response = session.agent()
                .call(Msg.of(io.kairo.api.message.MsgRole.USER,
                        "Review this codebase for bugs and verify each finding"))
                .block();

        // Assertions
        assertThat(response).isNotNull();
        assertThat(response.text()).contains("Workflow completed");
        // Exactly 4 child calls: 1 scanner + 3 verifiers. No wasted turns.
        assertThat(childCalls.get()).isEqualTo(4);
        // Parent: 1 tool_use + text turns (continuation nudges add ~3 extra)
        assertThat(parentCallCount.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void fullReActLoopWithPipelineWorkflow(@TempDir Path tmp) throws Exception {
        Path repo = initRepo(tmp);
        AtomicInteger childCalls = new AtomicInteger();

        String workflowScript = """
                export const meta = {
                    name: 'e2e-pipeline',
                    description: 'Two-stage pipeline'
                }

                const items = ['auth', 'payments', 'search'];

                phase('Analyze')
                const results = await pipeline(items,
                    (item) => ({prompt: 'Analyze module: ' + item, label: 'analyze:' + item}),
                    (prev, item) => ({prompt: 'Summarize analysis of ' + item + ': ' + prev, label: 'summarize:' + item})
                );

                return {
                    modules: items.length,
                    completed: results.filter(r => r !== null).length
                }
                """;

        AtomicInteger parentCalls = new AtomicInteger();
        ModelProvider parentModel = new ModelProvider() {
            @Override
            public Mono<ModelResponse> call(List<Msg> messages, ModelConfig config) {
                int call = parentCalls.incrementAndGet();
                if (call == 1) {
                    return Mono.just(new ModelResponse("r1",
                            List.of(new Content.ToolUseContent("tc-002", "scripted_workflow",
                                    Map.of("script", workflowScript))),
                            null, ModelResponse.StopReason.TOOL_USE, "test"));
                }
                return Mono.just(new ModelResponse("r2",
                        List.of(new Content.TextContent("Pipeline complete: analyzed 3 modules")),
                        null, ModelResponse.StopReason.END_TURN, "test"));
            }

            @Override
            public Flux<ModelResponse> stream(List<Msg> messages, ModelConfig config) {
                return Flux.from(call(messages, config));
            }

            @Override
            public String name() { return "pipe-parent"; }
        };

        ModelProvider childModel = new ModelProvider() {
            @Override
            public Mono<ModelResponse> call(List<Msg> messages, ModelConfig config) {
                int n = childCalls.incrementAndGet();
                return Mono.just(new ModelResponse("c" + n,
                        List.of(new Content.TextContent("result-" + n)),
                        null, ModelResponse.StopReason.END_TURN, "test"));
            }

            @Override
            public Flux<ModelResponse> stream(List<Msg> messages, ModelConfig config) {
                return Flux.from(call(messages, config));
            }

            @Override
            public String name() { return "pipe-child"; }
        };

        var taskDeps = buildTaskDeps(repo, tmp, childModel);
        var opts = io.kairo.code.core.CodeAgentFactory.SessionOptions.empty()
                .withModelProvider(parentModel)
                .withTaskTool(taskDeps);

        CodeAgentConfig config = new CodeAgentConfig(
                "test-key", "http://localhost:9999", "test",
                10, repo.toString(), null, 0, 0);

        var session = io.kairo.code.core.CodeAgentFactory.createSession(config, opts);
        Msg response = session.agent()
                .call(Msg.of(io.kairo.api.message.MsgRole.USER, "Analyze all modules"))
                .block();

        assertThat(response).isNotNull();
        assertThat(response.text()).contains("Pipeline complete");
        // 3 items × 2 stages = at least 6 child agent calls (may be more due to ReAct turns)
        assertThat(childCalls.get()).isGreaterThanOrEqualTo(6);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private io.kairo.code.core.task.TaskToolDependencies buildTaskDeps(
            Path repo, Path tmp, ModelProvider childModel) {
        var lifecycle = new io.kairo.code.core.workspace.WorktreeLifecycle(
                tmp.resolve("worktrees"), "git");
        var workspaceProvider = new io.kairo.code.core.workspace.WorktreeWorkspaceProvider(
                repo, lifecycle);

        io.kairo.code.core.task.ChildSessionSpawner spawner =
                (taskId, workDir, agentType, modelOverride) -> {
                    CodeAgentConfig childConfig = new CodeAgentConfig(
                            "test-key", "http://localhost:9999", "child-model",
                            5, workDir.toString(), null, 0, 0);
                    return io.kairo.code.core.CodeAgentFactory.createSession(
                            childConfig,
                            io.kairo.code.core.CodeAgentFactory.SessionOptions.empty()
                                    .withModelProvider(childModel)
                                    .asChildSession());
                };

        return new io.kairo.code.core.task.TaskToolDependencies(
                workspaceProvider, spawner,
                (tid, desc, stats, path) -> Mono.just(
                        io.kairo.code.core.task.WorktreeMergeChoice.DISCARD));
    }

    private static Path initRepo(Path base) throws Exception {
        Path repo = base.resolve("repo");
        Files.createDirectories(repo);
        run(repo, "git", "init", "-q", "-b", "main");
        run(repo, "git", "config", "user.email", "test@test");
        run(repo, "git", "config", "user.name", "Test");
        Files.writeString(repo.resolve("README.md"), "# Test\n", StandardCharsets.UTF_8);
        run(repo, "git", "add", "README.md");
        run(repo, "git", "commit", "-q", "-m", "init");
        return repo;
    }

    private static void run(Path cwd, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(cwd.toFile())
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new RuntimeException("Timeout: " + String.join(" ", cmd));
        }
        if (p.exitValue() != 0)
            throw new RuntimeException("Exit " + p.exitValue() + ": " + String.join(" ", cmd));
    }

    private static boolean commandAvailable(String cmd) {
        try {
            Process p = new ProcessBuilder(cmd, "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }
}
