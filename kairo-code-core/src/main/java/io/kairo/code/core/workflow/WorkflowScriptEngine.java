package io.kairo.code.core.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sandboxed GraalJS execution engine for workflow scripts.
 *
 * <p>All orchestration primitives ({@code agent}, {@code parallel}, {@code pipeline}) are
 * <b>synchronous and blocking</b> — the entire script runs on a single (virtual) thread. This
 * avoids GraalJS's single-threaded-context limitation: no Promises, no async/await needed.
 *
 * <p>{@code await} in scripts is harmless (no-op on non-Promise values), so scripts that
 * include it for readability still work.
 */
public final class WorkflowScriptEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WorkflowScriptEngine.class);
    private static final Engine SHARED_ENGINE;
    static {
        SHARED_ENGINE = Engine.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        Runtime.getRuntime().addShutdownHook(new Thread(SHARED_ENGINE::close, "graaljs-engine-shutdown"));
    }

    private final Context context;
    private final WorkflowRuntime runtime;

    public WorkflowScriptEngine(WorkflowRuntime runtime, Object args) {
        this.runtime = runtime;
        this.context = Context.newBuilder("js")
                .engine(SHARED_ENGINE)
                .allowHostAccess(HostAccess.newBuilder(HostAccess.EXPLICIT)
                        .allowArrayAccess(true)
                        .allowListAccess(true)
                        .allowMapAccess(true)
                        .build())
                .allowIO(false)
                .allowNativeAccess(false)
                .allowCreateThread(false)
                .allowHostClassLookup(className -> false)
                .option("js.ecmascript-version", "2022")
                .build();
        bindGlobals(args);
    }

    private void bindGlobals(Object args) {
        Value bindings = context.getBindings("js");

        bindings.putMember("agent", (ProxyExecutable) this::jsAgent);
        bindings.putMember("parallel", (ProxyExecutable) this::jsParallel);
        bindings.putMember("pipeline", (ProxyExecutable) this::jsPipeline);
        bindings.putMember("phase", (ProxyExecutable) this::jsPhase);
        bindings.putMember("log", (ProxyExecutable) this::jsLog);

        if (args != null) {
            bindings.putMember("args", args);
        } else {
            bindings.putMember("args", Value.asValue(null));
        }

        // Disable non-deterministic APIs
        context.eval("js", """
                Date.now = () => { throw new TypeError('Date.now() is disabled in workflows for determinism'); };
                Math.random = () => { throw new TypeError('Math.random() is disabled in workflows for determinism'); };
                const OrigDate = Date;
                globalThis.Date = function(...a) {
                    if (a.length === 0) throw new TypeError('new Date() without args is disabled for determinism');
                    return new OrigDate(...a);
                };
                Object.setPrototypeOf(globalThis.Date, OrigDate);
                globalThis.Date.prototype = OrigDate.prototype;
                globalThis.Date.parse = OrigDate.parse;
                globalThis.Date.UTC = OrigDate.UTC;
                """);
    }

    /**
     * Executes the script body and returns the result.
     *
     * <p>Wrapped in an {@code async} IIFE so scripts can use {@code await} (the standard
     * Claude Code workflow format). Since all bound functions ({@code agent}, {@code parallel},
     * etc.) return plain values (not Promises), {@code await} is a no-op — the Promise
     * resolves synchronously and no event-loop suspension occurs.
     */
    public Object execute(String scriptBody) {
        String wrapped = "(async function() {\n" + scriptBody + "\n})()";
        try {
            Source source = Source.newBuilder("js", wrapped, "workflow.js").build();
            Value promise = context.eval(source);

            // The async IIFE returns a Promise. Since all awaited values are plain
            // (non-Promise), the Promise is already resolved at this point.
            // Extract the resolved value via .then().
            java.util.concurrent.CompletableFuture<Object> future =
                    new java.util.concurrent.CompletableFuture<>();
            promise.invokeMember("then",
                    (ProxyExecutable) ok -> {
                        future.complete(ok.length > 0 ? valueToJava(ok[0]) : null);
                        return null;
                    },
                    (ProxyExecutable) err -> {
                        String errMsg = err.length > 0 && err[0].hasMember("message")
                                ? err[0].getMember("message").asString()
                                : (err.length > 0 ? err[0].toString() : "unknown");
                        future.completeExceptionally(new WorkflowExecutionException(errMsg));
                        return null;
                    });

            return future.get(SCRIPT_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof WorkflowExecutionException wee) throw wee;
            throw new WorkflowExecutionException("Script failed: " + e.getCause().getMessage(), e.getCause());
        } catch (java.util.concurrent.TimeoutException e) {
            throw new WorkflowExecutionException("Script timed out after " + SCRIPT_TIMEOUT_SECONDS + "s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkflowExecutionException("Script interrupted", e);
        } catch (Exception e) {
            String msg = e.getMessage();
            throw new WorkflowExecutionException("Script execution failed: " + msg, e);
        }
    }

    private static final long SCRIPT_TIMEOUT_SECONDS = 14400;

    // --- JS function implementations ---

    /**
     * agent(prompt, opts?) — blocks, spawns child agent, returns result.
     * With schema option returns parsed JSON; without returns raw text.
     */
    private Object jsAgent(Value[] args) {
        if (args.length < 1 || !args[0].isString()) {
            throw new IllegalArgumentException("agent() requires a string prompt as first argument");
        }
        String prompt = args[0].asString();
        Map<String, Object> opts = args.length > 1 && args[1] != null && args[1].hasMembers()
                ? valueToMap(args[1]) : Map.of();
        // Synchronous — blocks the JS thread (which is a virtual thread)
        return runtime.agentSync(prompt, opts);
    }

    /**
     * parallel(descriptions) — takes an array of {prompt, ...opts} objects,
     * starts all agents concurrently, blocks until all complete, returns results array.
     *
     * <p>Each element can be:
     * <ul>
     *   <li>A string (treated as prompt with default opts)</li>
     *   <li>An object with 'prompt' field and optional opts fields</li>
     * </ul>
     */
    private Object jsParallel(Value[] args) {
        if (args.length < 1 || !args[0].hasArrayElements()) {
            throw new IllegalArgumentException("parallel() requires an array argument");
        }
        Value array = args[0];
        int size = (int) array.getArraySize();
        List<WorkflowRuntime.AgentDescriptor> descriptors = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            Value elem = array.getArrayElement(i);
            descriptors.add(toDescriptor(elem, i));
        }
        Object[] results = runtime.parallelSync(descriptors);
        return results;
    }

    /**
     * pipeline(items, ...stageFns) — each stage function maps an item to an agent descriptor.
     * Items are processed concurrently; stages within an item are sequential.
     *
     * <p>Stage function signature: {@code (prevResult, originalItem, index) => descriptorOrValue}
     */
    private Object jsPipeline(Value[] args) {
        if (args.length < 2 || !args[0].hasArrayElements()) {
            throw new IllegalArgumentException("pipeline() requires items array and stage functions");
        }
        Value items = args[0];
        int itemCount = (int) items.getArraySize();
        Value[] stageFns = new Value[args.length - 1];
        for (int i = 1; i < args.length; i++) {
            if (!args[i].canExecute()) {
                throw new IllegalArgumentException("pipeline() stages must be functions");
            }
            stageFns[i - 1] = args[i];
        }

        Object[] itemArray = new Object[itemCount];
        for (int i = 0; i < itemCount; i++) {
            itemArray[i] = valueToJava(items.getArrayElement(i));
        }

        Object[] results = runtime.pipelineSync(itemArray, stageFns, this);
        return results;
    }

    private Object jsPhase(Value[] args) {
        if (args.length < 1 || !args[0].isString()) {
            throw new IllegalArgumentException("phase() requires a string title");
        }
        runtime.phase(args[0].asString());
        return null;
    }

    private Object jsLog(Value[] args) {
        if (args.length < 1) return null;
        runtime.log(args[0].isString() ? args[0].asString() : args[0].toString());
        return null;
    }

    // --- Descriptor conversion ---

    private WorkflowRuntime.AgentDescriptor toDescriptor(Value elem, int index) {
        if (elem.isString()) {
            return new WorkflowRuntime.AgentDescriptor(elem.asString(), Map.of());
        }
        if (elem.hasMembers() && elem.hasMember("prompt")) {
            String prompt = elem.getMember("prompt").asString();
            Map<String, Object> opts = valueToMap(elem);
            opts.remove("prompt");
            return new WorkflowRuntime.AgentDescriptor(prompt, opts);
        }
        throw new IllegalArgumentException(
                "parallel() element " + index + " must be a string or {prompt: '...', ...opts}");
    }

    /**
     * Executes a pipeline stage function and interprets the result:
     * <ul>
     *   <li>{@code {prompt: '...'}} or string → agent call</li>
     *   <li>null/undefined → pass-through (preserve previous result)</li>
     *   <li>anything else → plain value (pure data transform, no agent call)</li>
     * </ul>
     */
    public WorkflowRuntime.StageResult executeStage(Value stageFn, Object prevResult,
                                                    Object originalItem, int index) {
        Value result = stageFn.execute(prevResult, originalItem, index);
        if (result == null || result.isNull()) return null;
        if (result.isString()) {
            return new WorkflowRuntime.StageResult.AgentCall(
                    new WorkflowRuntime.AgentDescriptor(result.asString(), Map.of()));
        }
        if (result.hasMembers() && result.hasMember("prompt")) {
            String prompt = result.getMember("prompt").asString();
            Map<String, Object> opts = valueToMap(result);
            opts.remove("prompt");
            return new WorkflowRuntime.StageResult.AgentCall(
                    new WorkflowRuntime.AgentDescriptor(prompt, opts));
        }
        return new WorkflowRuntime.StageResult.PlainValue(valueToJava(result));
    }

    // --- Utility ---

    @SuppressWarnings("unchecked")
    static Map<String, Object> valueToMap(Value v) {
        if (v == null || v.isNull()) return new java.util.HashMap<>();
        java.util.HashMap<String, Object> result = new java.util.HashMap<>();
        for (String key : v.getMemberKeys()) {
            result.put(key, valueToJava(v.getMember(key)));
        }
        return result;
    }

    static Object valueToJava(Value v) {
        if (v == null || v.isNull()) return null;
        // Unwrap host objects (Java Maps, Lists returned by agent calls)
        if (v.isHostObject()) return v.asHostObject();
        if (v.isString()) return v.asString();
        if (v.isNumber()) {
            if (v.fitsInInt()) return v.asInt();
            if (v.fitsInLong()) return v.asLong();
            return v.asDouble();
        }
        if (v.isBoolean()) return v.asBoolean();
        if (v.hasArrayElements()) {
            int size = (int) v.getArraySize();
            Object[] arr = new Object[size];
            for (int i = 0; i < size; i++) arr[i] = valueToJava(v.getArrayElement(i));
            return arr;
        }
        if (v.hasMembers()) return valueToMap(v);
        return v.as(Object.class);
    }

    @Override
    public void close() {
        context.close();
    }

}
