package io.kairo.code.core.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.model.ModelConfig;
import io.kairo.api.model.ModelProvider;
import io.kairo.api.model.ModelResponse;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentFactory;
import io.kairo.code.core.CodeAgentFactory.SessionOptions;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.code.core.task.ChildSessionSpawner;
import io.kairo.code.core.task.TaskToolDependencies;
import io.kairo.code.core.task.WorktreeMergeChoice;
import io.kairo.code.core.workspace.WorktreeLifecycle;
import io.kairo.code.core.workspace.WorktreeWorkspaceProvider;
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

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * True end-to-end test through {@link CodeAgentFactory#createSession}.
 * No mocks for the factory — validates real tool registration, bean wiring,
 * and ToolContext injection.
 */
class ScriptedWorkflowFactoryIT {

    private static final CodeAgentConfig CONFIG =
            new CodeAgentConfig("test-key", "https://localhost", "test-model",
                    50, null, null, 0, 0);

    @BeforeAll
    static void requireGit() {
        assumeTrue(commandAvailable("git"), "git CLI required");
    }

    // ── 1. Tool registration ──────────────────────────────────────────────

    @Test
    void parentSessionHasScriptedWorkflowTool(@TempDir Path tmp) throws Exception {
        Path repo = initRepo(tmp.resolve("parent"));
        TaskToolDependencies deps = buildDeps(repo, tmp);

        CodeAgentSession session = CodeAgentFactory.createSession(
                configWithWorkDir(repo),
                SessionOptions.empty()
                        .withModelProvider(new StubModelProvider())
                        .withTaskTool(deps));

        assertThat(session.toolRegistry().get("scripted_workflow")).isPresent();
        assertThat(session.toolRegistry().get("task")).isPresent();
    }

    @Test
    void childSessionDoesNotHaveScriptedWorkflowTool(@TempDir Path tmp) throws Exception {
        Path repo = initRepo(tmp.resolve("child"));
        TaskToolDependencies deps = buildDeps(repo, tmp);

        CodeAgentSession session = CodeAgentFactory.createSession(
                configWithWorkDir(repo),
                SessionOptions.empty()
                        .withModelProvider(new StubModelProvider())
                        .withTaskTool(deps)
                        .asChildSession());

        assertThat(session.toolRegistry().get("scripted_workflow")).isEmpty();
        // TaskTool IS registered for child (schema-only, deps not wired)
        assertThat(session.toolRegistry().get("task")).isPresent();
    }

    // ── 2. Full tool invocation through factory-built session ────────────

    @Test
    void executeWorkflowThroughFactorySession(@TempDir Path tmp) throws Exception {
        Path repo = initRepo(tmp.resolve("e2e"));
        AtomicInteger childSpawns = new AtomicInteger();

        ChildSessionSpawner spawner = (taskId, workDir, agentType, modelOverride) -> {
            childSpawns.incrementAndGet();
            return CodeAgentFactory.createSession(
                    configWithWorkDir(workDir),
                    SessionOptions.empty()
                            .withModelProvider(new FixedResponseProvider("child-result-" + childSpawns.get()))
                            .asChildSession());
        };

        WorktreeLifecycle lifecycle = new WorktreeLifecycle(tmp.resolve("wt"), "git");
        WorktreeWorkspaceProvider provider = new WorktreeWorkspaceProvider(repo, lifecycle);
        TaskToolDependencies deps = new TaskToolDependencies(provider, spawner,
                (tid, desc, stats, wt) -> Mono.just(WorktreeMergeChoice.DISCARD));

        CodeAgentSession parentSession = CodeAgentFactory.createSession(
                configWithWorkDir(repo),
                SessionOptions.empty()
                        .withModelProvider(new StubModelProvider())
                        .withTaskTool(deps));

        Object handler = parentSession.toolRegistry().getToolInstance("scripted_workflow");
        assertThat(handler).isNotNull().isInstanceOf(SyncTool.class);
        SyncTool tool = (SyncTool) handler;

        Map<String, Object> input = new HashMap<>();
        input.put("script", """
                export const meta = {
                    name: 'factory-e2e',
                    description: 'End-to-end through CodeAgentFactory'
                }

                phase('Run')
                log('Starting agents...')
                const r1 = agent('task one');
                const r2 = agent('task two');
                return { first: r1, second: r2 };
                """);

        ToolContext toolCtx = buildToolCtx(repo, spawner, provider);
        ToolResult result = tool.execute(input, toolCtx).block();

        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("factory-e2e");
        assertThat(result.content()).contains("completed successfully");
        assertThat(result.metadata()).containsKey("runId");
        assertThat(childSpawns.get()).isEqualTo(2);
    }

    @Test
    void executeParallelWorkflowThroughFactory(@TempDir Path tmp) throws Exception {
        Path repo = initRepo(tmp.resolve("parallel"));
        AtomicInteger childSpawns = new AtomicInteger();

        ChildSessionSpawner spawner = (taskId, workDir, agentType, modelOverride) -> {
            int n = childSpawns.incrementAndGet();
            return CodeAgentFactory.createSession(
                    configWithWorkDir(workDir),
                    SessionOptions.empty()
                            .withModelProvider(new FixedResponseProvider("result-" + n))
                            .asChildSession());
        };

        WorktreeLifecycle lifecycle = new WorktreeLifecycle(tmp.resolve("wt2"), "git");
        WorktreeWorkspaceProvider provider = new WorktreeWorkspaceProvider(repo, lifecycle);
        TaskToolDependencies deps = new TaskToolDependencies(provider, spawner,
                (tid, desc, stats, wt) -> Mono.just(WorktreeMergeChoice.DISCARD));

        CodeAgentSession session = CodeAgentFactory.createSession(
                configWithWorkDir(repo),
                SessionOptions.empty()
                        .withModelProvider(new StubModelProvider())
                        .withTaskTool(deps));

        SyncTool tool = (SyncTool) session.toolRegistry().getToolInstance("scripted_workflow");

        Map<String, Object> input = new HashMap<>();
        input.put("script", """
                export const meta = { name: 'parallel-factory', description: 'x' }

                const results = parallel([
                    {prompt: 'check A', label: 'a'},
                    {prompt: 'check B', label: 'b'},
                    {prompt: 'check C', label: 'c'}
                ]);
                return { count: results.filter(r => r !== null).length };
                """);

        ToolContext toolCtx = buildToolCtx(repo, spawner, provider);
        ToolResult result = tool.execute(input, toolCtx).block();

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("parallel-factory");
        assertThat(childSpawns.get()).isEqualTo(3);
    }

    @Test
    void executeWorkflowWithSchemaAndDynamicParallel(@TempDir Path tmp) throws Exception {
        Path repo = initRepo(tmp.resolve("schema"));
        AtomicInteger callNum = new AtomicInteger();

        ChildSessionSpawner spawner = (taskId, workDir, agentType, modelOverride) -> {
            int n = callNum.incrementAndGet();
            // First call returns JSON (schema mode), subsequent return plain text
            String response = n == 1
                    ? "{\"items\": [\"a\", \"b\", \"c\"]}"
                    : "verified-" + n;
            return CodeAgentFactory.createSession(
                    configWithWorkDir(workDir),
                    SessionOptions.empty()
                            .withModelProvider(new FixedResponseProvider(response))
                            .asChildSession());
        };

        WorktreeLifecycle lifecycle = new WorktreeLifecycle(tmp.resolve("wt3"), "git");
        WorktreeWorkspaceProvider provider = new WorktreeWorkspaceProvider(repo, lifecycle);
        TaskToolDependencies deps = new TaskToolDependencies(provider, spawner,
                (tid, desc, stats, wt) -> Mono.just(WorktreeMergeChoice.DISCARD));

        CodeAgentSession session = CodeAgentFactory.createSession(
                configWithWorkDir(repo),
                SessionOptions.empty()
                        .withModelProvider(new StubModelProvider())
                        .withTaskTool(deps));

        SyncTool tool = (SyncTool) session.toolRegistry().getToolInstance("scripted_workflow");

        Map<String, Object> input = new HashMap<>();
        input.put("script", """
                export const meta = { name: 'schema-dynamic', description: 'x' }

                phase('Discover')
                const disc = agent('discover items', {schema: {type: 'object'}});

                phase('Process')
                const results = parallel(
                    disc.items.map(item => ({prompt: 'process: ' + item, label: item}))
                );

                return {
                    discovered: disc.items.length,
                    processed: results.filter(r => r !== null).length
                };
                """);

        ToolContext toolCtx = buildToolCtx(repo, spawner, provider);
        ToolResult result = tool.execute(input, toolCtx).block();

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("schema-dynamic");
        // 1 discovery + 3 parallel = 4
        assertThat(callNum.get()).isEqualTo(4);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static ToolContext buildToolCtx(Path repo, ChildSessionSpawner spawner,
                                              WorktreeWorkspaceProvider provider) {
        WorkflowDependencies wfDeps = new WorkflowDependencies(
                spawner, provider, configWithWorkDir(repo),
                WorkflowProgressEmitter.SLF4J_INSTANCE, null);
        return new ToolContext("agent", "session",
                Map.of(WorkflowDependencies.class.getName(), wfDeps));
    }

    private static CodeAgentConfig configWithWorkDir(Path dir) {
        return new CodeAgentConfig("test-key", "https://localhost", "test-model",
                50, dir.toString(), null, 0, 0);
    }

    private TaskToolDependencies buildDeps(Path repo, Path tmp) {
        WorktreeLifecycle lifecycle = new WorktreeLifecycle(tmp.resolve("wt-stub"), "git");
        WorktreeWorkspaceProvider provider = new WorktreeWorkspaceProvider(repo, lifecycle);
        return new TaskToolDependencies(
                provider,
                (taskId, wd, at, mo) -> CodeAgentFactory.createSession(
                        configWithWorkDir(wd),
                        SessionOptions.empty()
                                .withModelProvider(new StubModelProvider())
                                .asChildSession()),
                (taskId, desc, stats, wt) -> Mono.just(WorktreeMergeChoice.DISCARD));
    }

    private static Path initRepo(Path dir) throws Exception {
        Files.createDirectories(dir);
        run(dir, "git", "init", "-q", "-b", "main");
        run(dir, "git", "config", "user.email", "t@t");
        run(dir, "git", "config", "user.name", "T");
        Files.writeString(dir.resolve("README.md"), "init\n", StandardCharsets.UTF_8);
        run(dir, "git", "add", "README.md");
        run(dir, "git", "commit", "-q", "-m", "init");
        return dir;
    }

    private static void run(Path cwd, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new RuntimeException("Timeout: " + String.join(" ", cmd));
        }
        if (p.exitValue() != 0) throw new RuntimeException("Exit " + p.exitValue() + ": " + String.join(" ", cmd));
    }

    private static boolean commandAvailable(String cmd) {
        try {
            Process p = new ProcessBuilder(cmd, "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }

    static class StubModelProvider implements ModelProvider {
        @Override
        public Mono<ModelResponse> call(List<Msg> messages, ModelConfig config) {
            return Mono.just(new ModelResponse("stub", List.of(new Content.TextContent("stub")),
                    null, null, "stub"));
        }
        @Override
        public Flux<ModelResponse> stream(List<Msg> messages, ModelConfig config) {
            return Flux.from(call(messages, config));
        }
        @Override public String name() { return "stub"; }
    }

    static class FixedResponseProvider implements ModelProvider {
        private final String response;
        FixedResponseProvider(String response) { this.response = response; }
        @Override
        public Mono<ModelResponse> call(List<Msg> messages, ModelConfig config) {
            return Mono.just(new ModelResponse("fixed", List.of(new Content.TextContent(response)),
                    null, null, "fixed"));
        }
        @Override
        public Flux<ModelResponse> stream(List<Msg> messages, ModelConfig config) {
            return Flux.from(call(messages, config));
        }
        @Override public String name() { return "fixed"; }
    }
}
