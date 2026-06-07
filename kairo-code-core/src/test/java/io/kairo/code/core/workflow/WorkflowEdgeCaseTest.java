package io.kairo.code.core.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentState;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class WorkflowEdgeCaseTest {

    private static final CodeAgentConfig CONFIG =
            new CodeAgentConfig("k", null, "m", 0, "/tmp", null, 0, 0);

    private WorkflowRuntime runtime;
    private WorkflowScriptEngine engine;

    @BeforeEach
    void setUp() {
        var spawner = echoSpawner();
        var deps = new WorkflowDependencies(spawner, null, CONFIG,
                WorkflowProgressEmitter.SLF4J_INSTANCE, null);
        runtime = new WorkflowRuntime(deps);
        engine = new WorkflowScriptEngine(runtime, null);
    }

    @AfterEach
    void tearDown() {
        if (engine != null) engine.close();
        if (runtime != null) runtime.shutdown();
    }

    // ── Empty / null edge cases ──────────────────────────────────────────

    @Test
    void emptyParallelArray() {
        Object result = engine.execute("return parallel([]).length;");
        assertThat(result).isEqualTo(0);
    }

    @Test
    void agentReturnsEmptyString() {
        resetWith(fixedSpawner(""));
        Object result = engine.execute("return agent('x') === '';");
        assertThat(result).isEqualTo(true);
    }

    @Test
    void scriptReturnsUndefined() {
        Object result = engine.execute("agent('x');");
        // no return statement → undefined → null
        assertThat(result).isNull();
    }

    @Test
    void scriptReturnsNull() {
        Object result = engine.execute("return null;");
        assertThat(result).isNull();
    }

    @Test
    void scriptReturnsNestedObject() {
        Object result = engine.execute("""
                return {
                    a: { b: { c: 42 } },
                    arr: [1, 'two', { three: 3 }]
                };
                """);
        assertThat(result).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        var map = (Map<String, Object>) result;
        assertThat(map).containsKey("a");
        assertThat(map).containsKey("arr");
    }

    // ── Error handling ──────────────────────────────────────────────────

    @Test
    void agentThrowingDoesNotCrashWorkflow() {
        resetWith(throwingSpawner());
        Object result = engine.execute("""
                const r = agent('will fail');
                return r === null ? 'handled' : 'unexpected';
                """);
        assertThat(result).isEqualTo("handled");
    }

    @Test
    void parallelWithOneFailingAgent() {
        AtomicInteger callNum = new AtomicInteger();
        ChildSessionSpawner spawner = (taskId, workDir, agentType, modelOverride) -> {
            int n = callNum.incrementAndGet();
            if (n == 2) return makeSession(new ThrowingAgent());
            return makeSession("ok-" + n);
        };
        resetWith(spawner);

        Object result = engine.execute("""
                const results = parallel([
                    {prompt: 'a'}, {prompt: 'b'}, {prompt: 'c'}
                ]);
                const good = results.filter(r => r !== null);
                return good.length;
                """);
        assertThat(result).isEqualTo(2);
    }

    @Test
    void syntaxErrorInScript() {
        assertThatThrownBy(() -> engine.execute("const x = {;"))
                .isInstanceOf(WorkflowExecutionException.class);
    }

    @Test
    void runtimeErrorInScript() {
        assertThatThrownBy(() -> engine.execute("null.toString();"))
                .isInstanceOf(WorkflowExecutionException.class);
    }

    // ── Concurrency / stress ─────────────────────────────────────────────

    @Test
    void manyParallelAgents() {
        AtomicInteger count = new AtomicInteger();
        resetWith((taskId, workDir, agentType, modelOverride) -> {
            count.incrementAndGet();
            return makeSession("r");
        });

        Object result = engine.execute("""
                const descs = [];
                for (let i = 0; i < 20; i++) {
                    descs.push({prompt: 'task-' + i, label: 'w-' + i});
                }
                const results = parallel(descs);
                return results.filter(r => r !== null).length;
                """);
        assertThat(result).isEqualTo(20);
        assertThat(count.get()).isEqualTo(20);
    }

    @Test
    void sequentialAgentsAccumulateResults() {
        AtomicInteger count = new AtomicInteger();
        resetWith((taskId, workDir, agentType, modelOverride) ->
                makeSession("item-" + count.incrementAndGet()));

        Object result = engine.execute("""
                const results = [];
                for (let i = 0; i < 5; i++) {
                    results.push(agent('step ' + i));
                }
                return results.join(',');
                """);
        assertThat(result).isEqualTo("item-1,item-2,item-3,item-4,item-5");
    }

    // ── JS interop edge cases ────────────────────────────────────────────

    @Test
    void agentOptsWithNestedSchema() {
        resetWith(fixedSpawner("{\"found\": true}"));
        Object result = engine.execute("""
                const r = agent('check', {
                    schema: {type: 'object', properties: {found: {type: 'boolean'}}},
                    label: 'checker',
                    agentType: 'explore'
                });
                return r.found;
                """);
        assertThat(result).isEqualTo(true);
    }

    @Test
    void parallelResultUsedInSecondParallel() {
        resetWith(echoSpawner());
        Object result = engine.execute("""
                const first = parallel([{prompt: 'a'}, {prompt: 'b'}]);
                const second = parallel(
                    first.filter(r => r !== null).map(r => ({prompt: 'verify: ' + r}))
                );
                return second.length;
                """);
        assertThat(result).isEqualTo(2);
    }

    @Test
    void agentPromptContainsSpecialChars() {
        resetWith(echoSpawner());
        Object result = engine.execute("""
                const r = agent('Check file "src/main.java" for {bugs} & issues');
                return typeof r;
                """);
        assertThat(result).isEqualTo("string");
    }

    @Test
    void largeResultNotTruncated() {
        String longResponse = "x".repeat(10000);
        resetWith(fixedSpawner(longResponse));
        Object result = engine.execute("return agent('x').length;");
        assertThat(result).isEqualTo(10000);
    }

    // ── Tool-level tests ─────────────────────────────────────────────────

    @Test
    void toolReturnsErrorOnScriptException() {
        var spawner = echoSpawner();
        var deps = new WorkflowDependencies(spawner, null, CONFIG,
                WorkflowProgressEmitter.SLF4J_INSTANCE, null);
        var ctx = new ToolContext("a", "s", Map.of(WorkflowDependencies.class.getName(), deps));

        Map<String, Object> input = new HashMap<>();
        input.put("script", """
                export const meta = { name: 'err', description: 'x' }
                throw new Error('deliberate');
                """);

        ScriptedWorkflowTool tool = new ScriptedWorkflowTool();
        ToolResult result = tool.execute(input, ctx).block();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("deliberate");
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private void resetWith(ChildSessionSpawner spawner) {
        if (engine != null) engine.close();
        if (runtime != null) runtime.shutdown();
        var deps = new WorkflowDependencies(spawner, null, CONFIG,
                WorkflowProgressEmitter.SLF4J_INSTANCE, null);
        runtime = new WorkflowRuntime(deps);
        engine = new WorkflowScriptEngine(runtime, null);
    }

    private static ChildSessionSpawner echoSpawner() {
        return (taskId, workDir, agentType, modelOverride) -> {
            Agent agent = new Agent() {
                @Override public Mono<Msg> call(Msg input) {
                    return Mono.just(Msg.of(MsgRole.ASSISTANT, input.text()));
                }
                @Override public String id() { return "echo"; }
                @Override public String name() { return "echo"; }
                @Override public AgentState state() { return AgentState.IDLE; }
                @Override public void interrupt() {}
            };
            return makeSession(agent);
        };
    }

    private static ChildSessionSpawner fixedSpawner(String response) {
        return (taskId, workDir, agentType, modelOverride) -> makeSession(response);
    }

    private static ChildSessionSpawner throwingSpawner() {
        return (taskId, workDir, agentType, modelOverride) -> makeSession(new ThrowingAgent());
    }

    private static CodeAgentSession makeSession(String response) {
        return makeSession(new SimpleAgent(response));
    }

    private static CodeAgentSession makeSession(Agent agent) {
        var registry = new DefaultToolRegistry();
        var executor = new DefaultToolExecutor(registry, null, null);
        return new CodeAgentSession(agent, executor, registry, Set.of());
    }

    private static class SimpleAgent implements Agent {
        private final String r;
        SimpleAgent(String r) { this.r = r; }
        @Override public Mono<Msg> call(Msg i) { return Mono.just(Msg.of(MsgRole.ASSISTANT, r)); }
        @Override public String id() { return "s"; }
        @Override public String name() { return "s"; }
        @Override public AgentState state() { return AgentState.IDLE; }
        @Override public void interrupt() {}
    }

    private static class ThrowingAgent implements Agent {
        @Override public Mono<Msg> call(Msg i) { return Mono.error(new RuntimeException("agent exploded")); }
        @Override public String id() { return "err"; }
        @Override public String name() { return "err"; }
        @Override public AgentState state() { return AgentState.IDLE; }
        @Override public void interrupt() {}
    }
}
