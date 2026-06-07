package io.kairo.code.core.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.kairo.api.agent.Agent;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.code.core.task.AgentType;
import io.kairo.code.core.task.ChildSessionSpawner;
import io.kairo.core.tool.DefaultToolExecutor;
import io.kairo.core.tool.DefaultToolRegistry;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class WorkflowScriptEngineTest {

    private WorkflowRuntime runtime;
    private WorkflowScriptEngine engine;

    @BeforeEach
    void setUp() {
        ChildSessionSpawner spawner = new StubSpawner("echo response");
        CodeAgentConfig config = new CodeAgentConfig("key", null, "test-model",
                0, "/tmp/test", null, 0, 0);
        WorkflowDependencies deps = new WorkflowDependencies(
                spawner, null, config, WorkflowProgressEmitter.SLF4J_INSTANCE, null);
        runtime = new WorkflowRuntime(deps);
        engine = new WorkflowScriptEngine(runtime, null);
    }

    @AfterEach
    void tearDown() {
        if (engine != null) engine.close();
        if (runtime != null) runtime.shutdown();
    }

    @Test
    void executesSimpleReturn() {
        Object result = engine.execute("return 42;");
        assertThat(result).isEqualTo(42);
    }

    @Test
    void executesStringReturn() {
        Object result = engine.execute("return 'hello world';");
        assertThat(result).isEqualTo("hello world");
    }

    @Test
    void executesObjectReturn() {
        Object result = engine.execute("return { a: 1, b: 'two' };");
        assertThat(result).isInstanceOf(Map.class);
    }

    @Test
    void phaseCallWorks() {
        Object result = engine.execute("phase('Test'); return 'done';");
        assertThat(result).isEqualTo("done");
    }

    @Test
    void logCallWorks() {
        Object result = engine.execute("log('hello'); return 'ok';");
        assertThat(result).isEqualTo("ok");
    }

    @Test
    void agentCallReturnsResult() {
        Object result = engine.execute("const r = agent('test prompt'); return r;");
        assertThat(result).isEqualTo("echo response");
    }

    @Test
    void parallelReturnsArray() {
        Object result = engine.execute("""
                const results = parallel([
                    {prompt: 'a'},
                    {prompt: 'b'}
                ]);
                return results.length;
                """);
        assertThat(result).isEqualTo(2);
    }

    @Test
    void parallelWithStringShorthand() {
        Object result = engine.execute("""
                const results = parallel(['hello', 'world']);
                return results[0];
                """);
        assertThat(result).isEqualTo("echo response");
    }

    @Test
    void awaitAgentCallWorks() {
        Object result = engine.execute("const r = await agent('test'); return r;");
        assertThat(result).isEqualTo("echo response");
    }

    @Test
    void awaitParallelWorks() {
        Object result = engine.execute("""
                const results = await parallel([{prompt: 'a'}, {prompt: 'b'}]);
                return results.length;
                """);
        assertThat(result).isEqualTo(2);
    }

    @Test
    void mixedAwaitAndNonAwait() {
        Object result = engine.execute("""
                const r1 = agent('no await');
                const r2 = await agent('with await');
                return r1 === r2;
                """);
        assertThat(result).isEqualTo(true);
    }

    @Test
    void dateNowThrows() {
        assertThatThrownBy(() -> engine.execute("return Date.now();"))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("determinism");
    }

    @Test
    void mathRandomThrows() {
        assertThatThrownBy(() -> engine.execute("return Math.random();"))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("determinism");
    }

    @Test
    void hostClassAccessBlocked() {
        assertThatThrownBy(() -> engine.execute("return Java.type('java.lang.Runtime');"))
                .isInstanceOf(WorkflowExecutionException.class);
    }

    @Test
    void argsGlobalAccessible() {
        engine.close();
        runtime.shutdown();

        ChildSessionSpawner spawner = new StubSpawner("result");
        CodeAgentConfig config2 = new CodeAgentConfig("key", null, "m", 0, "/tmp", null, 0, 0);
        WorkflowDependencies deps2 = new WorkflowDependencies(
                spawner, null, config2, WorkflowProgressEmitter.SLF4J_INSTANCE, null);
        runtime = new WorkflowRuntime(deps2);
        engine = new WorkflowScriptEngine(runtime, Map.of("key", "value"));

        Object result = engine.execute("return args.key;");
        assertThat(result).isEqualTo("value");
    }

    /**
     * Stub that creates a session with a fixed-response agent. No Mockito needed.
     */
    private static class StubSpawner implements ChildSessionSpawner {
        private final String response;

        StubSpawner(String response) { this.response = response; }

        @Override
        public CodeAgentSession spawn(String taskId, Path workingDir,
                                      AgentType agentType, String modelOverride) {
            Agent agent = new StubAgent(response);
            DefaultToolRegistry registry = new DefaultToolRegistry();
            DefaultToolExecutor executor = new DefaultToolExecutor(registry, null, null);
            return new CodeAgentSession(agent, executor, registry, Set.of());
        }
    }

    private static class StubAgent implements Agent {
        private final String response;
        StubAgent(String response) { this.response = response; }

        @Override public Mono<Msg> call(Msg input) {
            return Mono.just(Msg.of(MsgRole.ASSISTANT, response));
        }
        @Override public String id() { return "stub"; }
        @Override public String name() { return "stub"; }
        @Override public io.kairo.api.agent.AgentState state() {
            return io.kairo.api.agent.AgentState.IDLE;
        }
        @Override public void interrupt() {}
    }
}
