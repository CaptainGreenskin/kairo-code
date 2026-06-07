package io.kairo.code.core.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.code.core.task.AgentType;
import io.kairo.code.core.task.ChildSessionSpawner;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java-side implementation of workflow orchestration primitives.
 *
 * <p>All methods are synchronous/blocking — designed to be called from the single-threaded
 * GraalJS context running on a virtual thread. Concurrent agent execution happens on separate
 * virtual threads managed by this runtime; the JS thread blocks until results are ready.
 */
public final class WorkflowRuntime {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRuntime.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int DEFAULT_MAX_CONCURRENCY =
            Math.max(2, Math.min(16, Runtime.getRuntime().availableProcessors() - 2));
    private static final int MAX_AGENTS = 1000;
    private static final long PARALLEL_TIMEOUT_SECONDS = 14400; // 4 hours, same as tool timeout

    private final WorkflowDependencies deps;
    private final WorkflowProgressEmitter emitter;
    private final Semaphore concurrencySemaphore;
    private final AtomicInteger totalAgentCount = new AtomicInteger(0);
    private final ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final String runId;
    private final WorkflowRunJournal journal;

    private volatile String currentPhase = "";

    public WorkflowRuntime(WorkflowDependencies deps) {
        this(deps, null);
    }

    public WorkflowRuntime(WorkflowDependencies deps, WorkflowRunJournal journal) {
        this.deps = deps;
        this.emitter = deps.progressEmitter();
        this.journal = journal;
        int maxConcurrency = resolveMaxConcurrency();
        this.concurrencySemaphore = new Semaphore(maxConcurrency);
        this.runId = journal != null ? journal.runId()
                : "wf_" + UUID.randomUUID().toString().substring(0, 8);
        log.info("WorkflowRuntime created: runId={}, maxConcurrency={}", runId, maxConcurrency);
    }

    public String runId() { return runId; }

    public record AgentDescriptor(String prompt, Map<String, Object> opts) {}

    /** Result of a pipeline stage evaluation — either an agent call or a plain value. */
    public sealed interface StageResult {
        record AgentCall(AgentDescriptor descriptor) implements StageResult {}
        record PlainValue(Object value) implements StageResult {}
    }

    // --- Core orchestration primitives (all synchronous/blocking) ---

    public Object agentSync(String prompt, Map<String, Object> opts) {
        int count = totalAgentCount.incrementAndGet();
        if (count > MAX_AGENTS) {
            throw new WorkflowExecutionException(
                    "Workflow agent cap exceeded (" + MAX_AGENTS + "). Total spawned: " + count);
        }
        String label = opts.getOrDefault("label", "agent-" + count).toString();
        String phase = opts.containsKey("phase")
                ? opts.get("phase").toString() : currentPhase;

        // Resume: return cached result if available
        if (journal != null) {
            String cacheKey = WorkflowRunJournal.computeCacheKey(prompt, opts);
            var cached = journal.getCached(cacheKey);
            if (cached.isPresent()) {
                log.info("[workflow] agent '{}' cache hit (resume)", label);
                emitter.agentStarted(label, phase);
                emitter.agentCompleted(label, phase, 0, true);
                return cached.get();
            }
        }

        try {
            concurrencySemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkflowExecutionException("Interrupted waiting for concurrency slot");
        }
        int maxRetries = parseIntOpt(opts, "maxRetries", 0);
        long retryDelayMs = parseLongOpt(opts, "retryDelayMs", 1000);

        long startMs = System.currentTimeMillis();
        emitter.agentStarted(label, phase);
        boolean success = false;
        try {
            Object result = null;
            Exception lastError = null;

            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                try {
                    result = executeAgent(prompt, opts, label);
                    lastError = null;
                    break;
                } catch (Exception e) {
                    lastError = e;
                    if (attempt < maxRetries) {
                        long delay = Math.min(retryDelayMs * (1L << attempt), 30_000);
                        log.info("[workflow] agent '{}' failed (attempt {}/{}), retrying in {}ms: {}",
                                label, attempt + 1, maxRetries + 1, delay, e.getMessage());
                        Thread.sleep(delay);
                    }
                }
            }

            if (lastError != null) {
                log.warn("[workflow] agent '{}' failed after {} attempts: {}",
                        label, maxRetries + 1, lastError.getMessage());
                return null;
            }

            success = true;
            if (journal != null) {
                journal.cache(WorkflowRunJournal.computeCacheKey(prompt, opts), result);
                try { journal.save(); } catch (java.io.IOException e) {
                    log.warn("[workflow] failed to persist journal: {}", e.getMessage());
                }
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[workflow] agent '{}' retry interrupted", label);
            return null;
        } catch (Exception e) {
            log.warn("[workflow] agent '{}' failed: {}", label, e.getMessage());
            return null;
        } finally {
            long durationMs = System.currentTimeMillis() - startMs;
            emitter.agentCompleted(label, phase, durationMs, success);
            concurrencySemaphore.release();
        }
    }

    @SuppressWarnings("unchecked")
    public Object[] parallelSync(List<AgentDescriptor> descriptors) {
        CompletableFuture<Object>[] futures = new CompletableFuture[descriptors.size()];
        for (int i = 0; i < descriptors.size(); i++) {
            AgentDescriptor desc = descriptors.get(i);
            futures[i] = CompletableFuture.supplyAsync(
                    () -> agentSync(desc.prompt(), desc.opts()), virtualExecutor);
        }
        try {
            CompletableFuture.allOf(futures).get(PARALLEL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            log.error("[workflow] parallel() timed out after {}s", PARALLEL_TIMEOUT_SECONDS);
            for (CompletableFuture<Object> f : futures) f.cancel(true);
            throw new WorkflowExecutionException("parallel() timed out after " + PARALLEL_TIMEOUT_SECONDS + "s");
        } catch (Exception e) {
            // InterruptedException or ExecutionException — collect whatever completed
            log.warn("[workflow] parallel() interrupted or failed: {}", e.getMessage());
        }
        Object[] results = new Object[futures.length];
        for (int i = 0; i < futures.length; i++) {
            try {
                results[i] = futures[i].isDone() ? futures[i].get() : null;
            } catch (Exception e) {
                results[i] = null;
            }
        }
        return results;
    }

    /**
     * Pipeline: for each stage, evaluate all items' stage functions on the JS thread (sequential),
     * then dispatch all resulting agent calls concurrently.
     *
     * <p>Stage functions can return:
     * <ul>
     *   <li>{@code {prompt: '...'}} — spawns an agent call</li>
     *   <li>{@code 'string'} — spawns an agent call with the string as prompt</li>
     *   <li>Any other value — used directly as the stage result (pure data transform)</li>
     *   <li>{@code null/undefined} — preserves the previous result (pass-through)</li>
     * </ul>
     */
    public Object[] pipelineSync(Object[] items, Value[] stageFns, WorkflowScriptEngine engine) {
        Object[] prevResults = Arrays.copyOf(items, items.length);
        Object[] originals = Arrays.copyOf(items, items.length);

        for (Value stageFn : stageFns) {
            StageResult[] stageResults = new StageResult[items.length];
            for (int i = 0; i < items.length; i++) {
                if (prevResults[i] == null) continue;
                try {
                    stageResults[i] = engine.executeStage(stageFn, prevResults[i], originals[i], i);
                } catch (Exception e) {
                    log.warn("[workflow] pipeline stage failed for item {}: {}", i, e.getMessage());
                }
            }

            // Collect agent calls for concurrent dispatch
            java.util.List<AgentDescriptor> agentDescs = new java.util.ArrayList<>();
            int[] agentIndices = new int[items.length];
            int agentCount = 0;

            for (int i = 0; i < stageResults.length; i++) {
                if (stageResults[i] instanceof StageResult.AgentCall ac) {
                    agentDescs.add(ac.descriptor());
                    agentIndices[agentCount++] = i;
                } else if (stageResults[i] instanceof StageResult.PlainValue pv) {
                    prevResults[i] = pv.value();
                }
                // null = pass-through, prevResults[i] preserved
            }

            if (agentCount > 0) {
                Object[] agentResults = parallelSync(agentDescs);
                for (int j = 0; j < agentCount; j++) {
                    prevResults[agentIndices[j]] = agentResults[j];
                }
            }
        }

        return prevResults;
    }

    public void phase(String title) {
        this.currentPhase = title;
        emitter.phaseStarted(title);
    }

    public void log(String message) {
        emitter.logMessage(message);
    }

    // --- Internal agent execution ---

    private Object executeAgent(String prompt, Map<String, Object> opts, String label) {
        String agentTypeId = opts.containsKey("agentType")
                ? opts.get("agentType").toString() : null;
        String modelOverride = opts.containsKey("model")
                ? opts.get("model").toString() : null;
        Object schema = opts.get("schema");

        String taskId = "wf-" + runId + "-" + label.replaceAll("[^a-zA-Z0-9_-]", "")
                + "-" + UUID.randomUUID().toString().substring(0, 4);
        AgentType agentType = agentTypeId != null ? AgentType.resolve(agentTypeId) : AgentType.GENERAL_PURPOSE;
        if (agentType == null) agentType = AgentType.GENERAL_PURPOSE;

        String workingDir = deps.parentConfig().workingDir();
        Path workDir = workingDir != null && !workingDir.isBlank()
                ? Path.of(workingDir) : Path.of(System.getProperty("user.dir"));

        ChildSessionSpawner spawner = deps.spawner();
        CodeAgentSession child = spawner.spawn(taskId, workDir, agentType, modelOverride);

        String effectivePrompt = prompt;
        if (schema != null) {
            effectivePrompt += "\n\nYou MUST respond with valid JSON matching this schema:\n"
                    + schema.toString()
                    + "\n\nRespond ONLY with the JSON object, no surrounding text or markdown.";
        }

        Msg response = child.agent().call(Msg.of(MsgRole.USER, effectivePrompt)).block();
        if (response == null) return null;

        String text = response.text();
        if (schema != null) {
            try {
                return MAPPER.readValue(text, Map.class);
            } catch (Exception e) {
                log.warn("[workflow] agent '{}' response is not valid JSON, returning raw text", label);
                return text;
            }
        }
        return text;
    }

    private static final int RESOLVED_MAX_CONCURRENCY = resolveMaxConcurrency();

    private static int resolveMaxConcurrency() {
        String env = System.getenv("KAIRO_WORKFLOW_MAX_CONCURRENCY");
        if (env != null && !env.isBlank()) {
            try {
                return Integer.parseInt(env.trim());
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        return DEFAULT_MAX_CONCURRENCY;
    }

    private static int parseIntOpt(Map<String, Object> opts, String key, int defaultVal) {
        Object v = opts.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { /* */ } }
        return defaultVal;
    }

    private static long parseLongOpt(Map<String, Object> opts, String key, long defaultVal) {
        Object v = opts.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) { try { return Long.parseLong(s); } catch (NumberFormatException e) { /* */ } }
        return defaultVal;
    }

    public void shutdown() {
        virtualExecutor.shutdown();
        try {
            if (!virtualExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                virtualExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            virtualExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
