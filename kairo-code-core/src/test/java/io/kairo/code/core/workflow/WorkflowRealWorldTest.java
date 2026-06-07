package io.kairo.code.core.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentState;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import java.util.Map;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.code.core.task.AgentType;
import io.kairo.code.core.task.ChildSessionSpawner;
import io.kairo.core.tool.DefaultToolExecutor;
import io.kairo.core.tool.DefaultToolRegistry;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Tests real-world script patterns that models would actually generate.
 * Catches JS/Java interop edge cases.
 */
class WorkflowRealWorldTest {

    private WorkflowRuntime runtime;
    private WorkflowScriptEngine engine;

    @BeforeEach
    void setUp() {
        var spawner = new PromptEchoSpawner();
        var config = new CodeAgentConfig("key", null, "m", 0, "/tmp", null, 0, 0);
        var deps = new WorkflowDependencies(spawner, null, config,
                WorkflowProgressEmitter.SLF4J_INSTANCE, null);
        runtime = new WorkflowRuntime(deps);
        engine = new WorkflowScriptEngine(runtime, null);
    }

    @AfterEach
    void tearDown() {
        if (engine != null) engine.close();
        if (runtime != null) runtime.shutdown();
    }

    @Test
    void parallelResultSupportsFilterAndMap() {
        Object result = engine.execute("""
                const results = parallel([
                    {prompt: 'a'}, {prompt: 'b'}, {prompt: 'c'}
                ]);
                const filtered = results.filter(r => r !== null);
                const mapped = filtered.map(r => r.toUpperCase());
                return mapped.length;
                """);
        assertThat(result).isEqualTo(3);
    }

    @Test
    void parallelResultSupportsForOf() {
        Object result = engine.execute("""
                const results = parallel([{prompt: 'x'}, {prompt: 'y'}]);
                const collected = [];
                for (const r of results) {
                    collected.push(r);
                }
                return collected.length;
                """);
        assertThat(result).isEqualTo(2);
    }

    @Test
    void agentSchemaResultPropertyAccess() {
        // Agent returns JSON, script accesses properties
        engine.close();
        runtime.shutdown();

        var spawner = new FixedSpawner("{\"bugs\": [{\"file\": \"a.java\"}], \"count\": 2}");
        var config = new CodeAgentConfig("key", null, "m", 0, "/tmp", null, 0, 0);
        var deps = new WorkflowDependencies(spawner, null, config,
                WorkflowProgressEmitter.SLF4J_INSTANCE, null);
        runtime = new WorkflowRuntime(deps);
        engine = new WorkflowScriptEngine(runtime, null);

        Object result = engine.execute("""
                const r = agent('find bugs', {schema: {type: 'object'}});
                return r.count;
                """);
        assertThat(result).isEqualTo(2);
    }

    @Test
    void agentSchemaResultNestedAccess() {
        engine.close();
        runtime.shutdown();

        var spawner = new FixedSpawner("{\"items\": [{\"name\": \"foo\"}, {\"name\": \"bar\"}]}");
        var config = new CodeAgentConfig("key", null, "m", 0, "/tmp", null, 0, 0);
        var deps = new WorkflowDependencies(spawner, null, config,
                WorkflowProgressEmitter.SLF4J_INSTANCE, null);
        runtime = new WorkflowRuntime(deps);
        engine = new WorkflowScriptEngine(runtime, null);

        Object result = engine.execute("""
                const r = agent('get items', {schema: {}});
                const names = r.items.map(i => i.name);
                return names.length;
                """);
        assertThat(result).isEqualTo(2);
    }

    @Test
    void dynamicParallelFromAgentResult() {
        engine.close();
        runtime.shutdown();

        AtomicInteger callCount = new AtomicInteger();
        ChildSessionSpawner spawner = (taskId, workDir, agentType, modelOverride) -> {
            int n = callCount.incrementAndGet();
            String response = n == 1
                    ? "{\"tasks\": [\"check-a\", \"check-b\", \"check-c\"]}"
                    : "verified-" + n;
            return makeSession(response);
        };
        var config = new CodeAgentConfig("key", null, "m", 0, "/tmp", null, 0, 0);
        var deps = new WorkflowDependencies(spawner, null, config,
                WorkflowProgressEmitter.SLF4J_INSTANCE, null);
        runtime = new WorkflowRuntime(deps);
        engine = new WorkflowScriptEngine(runtime, null);

        Object result = engine.execute("""
                phase('Discover')
                const discovery = agent('find tasks', {schema: {}});

                phase('Execute')
                const results = parallel(
                    discovery.tasks.map(t => ({prompt: 'execute: ' + t, label: t}))
                );

                return {
                    discovered: discovery.tasks.length,
                    executed: results.filter(r => r !== null).length
                };
                """);

        assertThat(result).isInstanceOf(java.util.Map.class);
        @SuppressWarnings("unchecked")
        var map = (java.util.Map<String, Object>) result;
        assertThat(map.get("discovered")).isEqualTo(3);
        assertThat(map.get("executed")).isEqualTo(3);
        assertThat(callCount.get()).isEqualTo(4); // 1 discovery + 3 parallel
    }

    @Test
    void stringConcatenationInPrompt() {
        Object result = engine.execute("""
                const files = ['a.java', 'b.java'];
                const results = [];
                for (const f of files) {
                    results.push(agent('Review file: ' + f));
                }
                return results.join(', ');
                """);
        assertThat(result).isInstanceOf(String.class);
    }

    @Test
    void templateLiteralInPrompt() {
        Object result = engine.execute("""
                const name = 'test';
                const r = agent(`Check ${name} module`);
                return r;
                """);
        assertThat(result).isInstanceOf(String.class);
        // Prompt should contain the interpolated value
        assertThat(result.toString()).contains("Check test module");
    }

    @Test
    void objectSpreadAndDestructuring() {
        Object result = engine.execute("""
                const base = {label: 'base'};
                const r = agent('test', {...base, agentType: 'explore'});
                return typeof r;
                """);
        assertThat(result).isEqualTo("string");
    }

    @Test
    void argsPassedToWorkflow() {
        engine.close();
        runtime.shutdown();

        var spawner = new PromptEchoSpawner();
        var config = new CodeAgentConfig("key", null, "m", 0, "/tmp", null, 0, 0);
        var deps = new WorkflowDependencies(spawner, null, config,
                WorkflowProgressEmitter.SLF4J_INSTANCE, null);
        runtime = new WorkflowRuntime(deps);
        engine = new WorkflowScriptEngine(runtime,
                java.util.Map.of("files", java.util.List.of("a.java", "b.java")));

        Object result = engine.execute("""
                const results = parallel(
                    args.files.map(f => ({prompt: 'Review ' + f}))
                );
                return results.length;
                """);
        assertThat(result).isEqualTo(2);
    }

    @Test
    void metaWithCommentsInSource() {
        String script = """
                export const meta = {
                    name: 'commented',
                    // This is the old description
                    description: 'new description'
                }
                return 'ok';
                """;
        WorkflowMeta meta = WorkflowMetaParser.parse(script);
        assertThat(meta.name()).isEqualTo("commented");
        assertThat(meta.description()).isEqualTo("new description");

        String body = WorkflowMetaParser.extractScriptBody(script);
        Object result = engine.execute(body);
        assertThat(result).isEqualTo("ok");
    }

    @Test
    void claudeCodeStyleScriptWithAwait() {
        // This is the exact format Claude Code's model generates
        engine.close();
        runtime.shutdown();

        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        ChildSessionSpawner spawner = (taskId, workDir, agentType, modelOverride) -> {
            int i = n.incrementAndGet();
            String resp = i == 1 ? "{\"bugs\":[{\"file\":\"a.java\",\"desc\":\"null check\"}]}" : "confirmed";
            return makeSession(resp);
        };
        var config = new CodeAgentConfig("key", null, "m", 0, "/tmp", null, 0, 0);
        var deps = new WorkflowDependencies(spawner, null, config,
                WorkflowProgressEmitter.SLF4J_INSTANCE, null);
        runtime = new WorkflowRuntime(deps);
        engine = new WorkflowScriptEngine(runtime, null);

        Object result = engine.execute("""
                phase('Find')
                const bugs = await agent('Find bugs in the codebase', {
                    schema: {type: 'object'},
                    label: 'finder'
                })

                phase('Verify')
                const verified = await parallel(
                    bugs.bugs.map(b => ({
                        prompt: 'Verify: ' + b.desc,
                        label: 'verify:' + b.file
                    }))
                )

                return {
                    total: bugs.bugs.length,
                    confirmed: verified.filter(v => v !== null).length
                }
                """);

        assertThat(result).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        var map = (Map<String, Object>) result;
        assertThat(map.get("total")).isEqualTo(1);
        assertThat(map.get("confirmed")).isEqualTo(1);
        assertThat(n.get()).isEqualTo(2);
    }

    @Test
    void errorInScriptGivesUsefulMessage() {
        try {
            engine.execute("undefinedFunction();");
        } catch (WorkflowExecutionException e) {
            assertThat(e.getMessage()).contains("undefinedFunction");
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static CodeAgentSession makeSession(String response) {
        return makeSession(new SimpleAgent(response));
    }

    private static CodeAgentSession makeSession(Agent agent) {
        var registry = new DefaultToolRegistry();
        var executor = new DefaultToolExecutor(registry, null, null);
        return new CodeAgentSession(agent, executor, registry, Set.of());
    }

    /** Echoes the prompt back as the response. */
    private static class PromptEchoSpawner implements ChildSessionSpawner {
        @Override
        public CodeAgentSession spawn(String taskId, Path workDir,
                                      AgentType agentType, String modelOverride) {
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
        }
    }

    private static class FixedSpawner implements ChildSessionSpawner {
        private final String response;
        FixedSpawner(String response) { this.response = response; }
        @Override
        public CodeAgentSession spawn(String taskId, Path workDir,
                                      AgentType agentType, String modelOverride) {
            return makeSession(response);
        }
    }

    private static class SimpleAgent implements Agent {
        private final String response;
        SimpleAgent(String response) { this.response = response; }
        @Override public Mono<Msg> call(Msg input) {
            return Mono.just(Msg.of(MsgRole.ASSISTANT, response));
        }
        @Override public String id() { return "simple"; }
        @Override public String name() { return "simple"; }
        @Override public AgentState state() { return AgentState.IDLE; }
        @Override public void interrupt() {}
    }
}
