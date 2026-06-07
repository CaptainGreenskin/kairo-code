package io.kairo.code.core.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentState;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.code.core.task.AgentType;
import io.kairo.code.core.task.ChildSessionSpawner;
import io.kairo.core.tool.DefaultToolExecutor;
import io.kairo.core.tool.DefaultToolRegistry;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

/**
 * End-to-end integration test for {@link ScriptedWorkflowTool}.
 * Exercises the full chain: tool → engine → runtime → spawner → agent.
 */
class ScriptedWorkflowToolIT {

    private static final CodeAgentConfig CONFIG =
            new CodeAgentConfig("key", null, "test", 0, "/tmp/test", null, 0, 0);

    @Test
    void simpleAgentWorkflow() {
        var spawner = new CountingSpawner("bug found in line 42");
        var result = executeWorkflow(spawner, """
                export const meta = {
                    name: 'simple-test',
                    description: 'Single agent test'
                }

                const r = agent('Find bugs in main.java');
                return { result: r };
                """);

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("simple-test");
        assertThat(result.content()).contains("completed successfully");
        assertThat(spawner.count.get()).isEqualTo(1);
    }

    @Test
    void parallelWorkflow() {
        var spawner = new CountingSpawner("found issue");
        var result = executeWorkflow(spawner, """
                export const meta = {
                    name: 'parallel-test',
                    description: 'Parallel agents'
                }

                phase('Find')
                const results = parallel([
                    {prompt: 'Check module A', label: 'a'},
                    {prompt: 'Check module B', label: 'b'},
                    {prompt: 'Check module C', label: 'c'}
                ]);
                return { count: results.length, first: results[0] };
                """);

        assertThat(result.isError()).isFalse();
        assertThat(spawner.count.get()).isEqualTo(3);
        assertThat(result.content()).contains("parallel-test");
    }

    @Test
    void parallelStringShorthand() {
        var spawner = new CountingSpawner("ok");
        var result = executeWorkflow(spawner, """
                export const meta = { name: 'shorthand', description: 'x' }

                const r = parallel(['check A', 'check B']);
                return r.length;
                """);

        assertThat(result.isError()).isFalse();
        assertThat(spawner.count.get()).isEqualTo(2);
    }

    @Test
    void parallelActuallyConcurrent() {
        // 4 agents each taking 200ms — if sequential that's 800ms+, if parallel under 500ms
        ChildSessionSpawner spawner = (taskId, workDir, agentType, modelOverride) ->
                makeSession(new SlowAgent("done", 200));

        long start = System.currentTimeMillis();
        var result = executeWorkflow(spawner, """
                export const meta = { name: 'concurrent', description: 'x' }
                const results = parallel([
                    {prompt: 'a'}, {prompt: 'b'}, {prompt: 'c'}, {prompt: 'd'}
                ]);
                return results.length;
                """);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result.isError()).isFalse();
        // 4×200ms serial = 800ms; parallel should finish well under 600ms
        assertThat(elapsed).isLessThan(600);
    }

    @Test
    void phaseAndLogWork() {
        var emitter = new RecordingEmitter();
        var spawner = new CountingSpawner("done");
        var result = executeWorkflow(spawner, emitter, """
                export const meta = { name: 'phases', description: 'x' }

                phase('Find')
                log('Starting search...')
                agent('search');

                phase('Verify')
                log('Verifying results...')
                agent('verify');

                return 'ok';
                """);

        assertThat(result.isError()).isFalse();
        assertThat(emitter.phases).containsExactly("Find", "Verify");
        assertThat(emitter.logs).containsExactly("Starting search...", "Verifying results...");
        assertThat(emitter.agentsStarted).hasSize(2);
    }

    @Test
    void agentWithSchema() {
        // Agent returns JSON
        var spawner = new FixedResponseSpawner("{\"bugs\": [{\"file\": \"a.java\", \"line\": 10}]}");
        var result = executeWorkflow(spawner, """
                export const meta = { name: 'schema-test', description: 'x' }

                const r = agent('find bugs', {
                    schema: {type: 'object', properties: {bugs: {type: 'array'}}},
                    label: 'finder'
                });
                return r;
                """);

        assertThat(result.isError()).isFalse();
        // Should contain the parsed JSON
        assertThat(result.content()).contains("bugs");
    }

    @Test
    void agentTypePassedToSpawner() {
        AtomicInteger exploreCalls = new AtomicInteger();
        ChildSessionSpawner spawner = (taskId, workDir, agentType, modelOverride) -> {
            if (agentType == AgentType.EXPLORE) exploreCalls.incrementAndGet();
            return makeSession("explored");
        };

        var result = executeWorkflow(spawner, """
                export const meta = { name: 'type-test', description: 'x' }
                const r = agent('find files', {agentType: 'explore'});
                return r;
                """);

        assertThat(result.isError()).isFalse();
        assertThat(exploreCalls.get()).isEqualTo(1);
    }

    @Test
    void pipelineTwoStages() {
        // Stage 1 returns prefix, stage 2 returns suffix
        AtomicInteger callIndex = new AtomicInteger();
        ChildSessionSpawner spawner = (taskId, workDir, agentType, modelOverride) -> {
            int idx = callIndex.incrementAndGet();
            return makeSession("result-" + idx);
        };

        var result = executeWorkflow(spawner, """
                export const meta = { name: 'pipeline-test', description: 'x' }
                const items = ['alpha', 'beta'];
                const results = pipeline(items,
                    (item, orig, idx) => ({prompt: 'stage1: ' + item}),
                    (prev, orig, idx) => ({prompt: 'stage2: ' + prev + ' for ' + orig})
                );
                return { count: results.length, first: results[0] };
                """);

        assertThat(result.isError()).isFalse();
        // 2 items × 2 stages = 4 agent calls
        assertThat(callIndex.get()).isEqualTo(4);
    }

    @Test
    void controlFlowWithLoops() {
        var spawner = new CountingSpawner("found");
        var result = executeWorkflow(spawner, """
                export const meta = { name: 'loop-test', description: 'x' }

                const items = ['a', 'b', 'c'];
                const results = [];
                for (const item of items) {
                    results.push(agent('Check ' + item));
                }
                return { total: results.length };
                """);

        assertThat(result.isError()).isFalse();
        assertThat(spawner.count.get()).isEqualTo(3);
    }

    @Test
    void controlFlowWithConditional() {
        // Agent returns different things based on prompt
        ChildSessionSpawner spawner = (taskId, workDir, agentType, modelOverride) -> {
            // Return "yes" for everything (simplified)
            return makeSession("yes");
        };

        var result = executeWorkflow(spawner, """
                export const meta = { name: 'conditional', description: 'x' }

                const check = agent('is server healthy?');
                if (check === 'yes') {
                    return 'all good';
                } else {
                    agent('restart server');
                    return 'restarted';
                }
                """);

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("all good");
    }

    @Test
    void workflowFromFile(@TempDir Path tmp) throws Exception {
        java.nio.file.Files.createDirectories(tmp.resolve(".kairo/workflows"));
        java.nio.file.Files.writeString(tmp.resolve(".kairo/workflows/my-flow.js"), """
                export const meta = { name: 'from-file', description: 'loaded from disk' }
                return agent('do stuff');
                """);

        var spawner = new CountingSpawner("file result");
        CodeAgentConfig config = new CodeAgentConfig("key", null, "test", 0,
                tmp.toString(), null, 0, 0);
        var deps = new WorkflowDependencies(spawner, null, config,
                WorkflowProgressEmitter.SLF4J_INSTANCE, null);

        Map<String, Object> input = new HashMap<>();
        input.put("scriptPath", tmp.resolve(".kairo/workflows/my-flow.js").toString());

        var ctx = new ToolContext("agent", "session",
                Map.of(WorkflowDependencies.class.getName(), deps));

        ScriptedWorkflowTool tool = new ScriptedWorkflowTool();
        ToolResult result = tool.execute(input, ctx).block();

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("from-file");
    }

    @Test
    void errorOnMissingScript() {
        var spawner = new CountingSpawner("x");
        var result = executeWorkflow(spawner, Map.of()); // no script/name/scriptPath

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("required");
    }

    @Test
    void errorOnBadMeta() {
        var spawner = new CountingSpawner("x");
        var result = executeWorkflow(spawner, """
                const x = 1;
                return x;
                """); // no export const meta

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("meta");
    }

    @Test
    void errorOnMissingDeps() {
        Map<String, Object> input = new HashMap<>();
        input.put("script", "export const meta = {name:'x',description:'x'}\nreturn 1;");
        var ctx = new ToolContext("agent", "session", Map.of()); // no deps
        ScriptedWorkflowTool tool = new ScriptedWorkflowTool();
        ToolResult result = tool.execute(input, ctx).block();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("WorkflowDependencies");
    }

    @Test
    void metadataContainsRunId() {
        var spawner = new CountingSpawner("x");
        var result = executeWorkflow(spawner, """
                export const meta = { name: 'meta-check', description: 'x' }
                return 'ok';
                """);

        assertThat(result.isError()).isFalse();
        assertThat(result.metadata()).containsKey("runId");
        assertThat(result.metadata().get("runId").toString()).startsWith("wf_");
        assertThat(result.metadata()).containsEntry("workflow", "meta-check");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ToolResult executeWorkflow(ChildSessionSpawner spawner, String script) {
        return executeWorkflow(spawner, WorkflowProgressEmitter.SLF4J_INSTANCE, script);
    }

    private ToolResult executeWorkflow(ChildSessionSpawner spawner,
                                       WorkflowProgressEmitter emitter, String script) {
        Map<String, Object> input = new HashMap<>();
        input.put("script", script);
        return executeWorkflow(spawner, emitter, input);
    }

    private ToolResult executeWorkflow(ChildSessionSpawner spawner, Map<String, Object> input) {
        return executeWorkflow(spawner, WorkflowProgressEmitter.SLF4J_INSTANCE, input);
    }

    private ToolResult executeWorkflow(ChildSessionSpawner spawner,
                                       WorkflowProgressEmitter emitter,
                                       Map<String, Object> input) {
        var deps = new WorkflowDependencies(spawner, null, CONFIG, emitter, null);
        var ctx = new ToolContext("agent", "session",
                Map.of(WorkflowDependencies.class.getName(), deps));

        ScriptedWorkflowTool tool = new ScriptedWorkflowTool();
        return tool.execute(input, ctx).block();
    }

    private static CodeAgentSession makeSession(String response) {
        return makeSession(new EchoAgent(response));
    }

    private static CodeAgentSession makeSession(Agent agent) {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        DefaultToolExecutor executor = new DefaultToolExecutor(registry, null, null);
        return new CodeAgentSession(agent, executor, registry, Set.of());
    }

    // ── Stub spawners ────────────────────────────────────────────────────────

    private static class CountingSpawner implements ChildSessionSpawner {
        final AtomicInteger count = new AtomicInteger();
        private final String response;

        CountingSpawner(String response) { this.response = response; }

        @Override
        public CodeAgentSession spawn(String taskId, Path workDir,
                                      AgentType agentType, String modelOverride) {
            count.incrementAndGet();
            return makeSession(response);
        }
    }

    private static class FixedResponseSpawner implements ChildSessionSpawner {
        private final String response;

        FixedResponseSpawner(String response) { this.response = response; }

        @Override
        public CodeAgentSession spawn(String taskId, Path workDir,
                                      AgentType agentType, String modelOverride) {
            return makeSession(response);
        }
    }

    // ── Stub agents ──────────────────────────────────────────────────────────

    private static class EchoAgent implements Agent {
        private final String response;
        EchoAgent(String response) { this.response = response; }
        @Override public Mono<Msg> call(Msg input) {
            return Mono.just(Msg.of(MsgRole.ASSISTANT, response));
        }
        @Override public String id() { return "echo"; }
        @Override public String name() { return "echo"; }
        @Override public AgentState state() { return AgentState.IDLE; }
        @Override public void interrupt() {}
    }

    private static class SlowAgent implements Agent {
        private final String response;
        private final long delayMs;
        SlowAgent(String response, long delayMs) {
            this.response = response;
            this.delayMs = delayMs;
        }
        @Override public Mono<Msg> call(Msg input) {
            return Mono.delay(java.time.Duration.ofMillis(delayMs))
                    .map(v -> Msg.of(MsgRole.ASSISTANT, response));
        }
        @Override public String id() { return "slow"; }
        @Override public String name() { return "slow"; }
        @Override public AgentState state() { return AgentState.IDLE; }
        @Override public void interrupt() {}
    }

    // ── Recording emitter ────────────────────────────────────────────────────

    private static class RecordingEmitter implements WorkflowProgressEmitter {
        final java.util.List<String> phases = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.List<String> logs = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.List<String> agentsStarted = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override public void phaseStarted(String title) { phases.add(title); }
        @Override public void agentStarted(String label, String phase) { agentsStarted.add(label); }
        @Override public void agentCompleted(String l, String p, long d, boolean s) {}
        @Override public void logMessage(String message) { logs.add(message); }
    }
}
