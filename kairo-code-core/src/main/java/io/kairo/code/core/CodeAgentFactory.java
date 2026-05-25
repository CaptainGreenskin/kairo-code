package io.kairo.code.core;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentSnapshot;
import io.kairo.api.message.Msg;
import io.kairo.api.model.ModelProvider;
import io.kairo.api.skill.SkillDefinition;
import io.kairo.api.skill.SkillRegistry;
import io.kairo.api.tool.PermissionGuard;
import io.kairo.api.tool.ToolDefinition;
import io.kairo.api.tool.UserApprovalHandler;
import io.kairo.api.memory.MemoryStore;
import io.kairo.api.tracing.Tracer;
import io.kairo.code.core.mcp.McpConfig;
import io.kairo.code.core.evolution.LearnedLessonStore;
import io.kairo.code.core.memory.KairoMdContextSource;
import io.kairo.code.core.prompt.SessionContextEnricher;
import io.kairo.code.core.prompt.SessionMemoryEnricher;
import io.kairo.code.core.stats.ToolUsageTracker;
import io.kairo.code.core.stats.TurnMetricsCollector;
import io.kairo.code.core.hook.AutoCommitOnSuccessHook;
import io.kairo.code.core.hook.CompileErrorFeedbackHook;
import io.kairo.code.core.hook.ContextCompactionHook;
import io.kairo.code.core.hook.ContextWindowGuardHook;
import io.kairo.code.core.hook.ExecutionTraceHook;
import io.kairo.code.core.hook.FullTestSuiteHook;
import io.kairo.code.core.hook.MaxTurnsGuardHook;
import io.kairo.code.core.hook.MissingTestHintHook;
import io.kairo.code.core.hook.NoWriteDetectedHook;
import io.kairo.code.core.hook.PlanWithoutActionHook;
import io.kairo.code.core.hook.RichLoopDetectorHook;
import io.kairo.code.core.hook.PostBatchEditVerifyHook;
import io.kairo.code.core.hook.PostEditHintHook;
import io.kairo.code.core.hook.RepetitiveToolHook;
import io.kairo.code.core.hook.SessionMetricsCollector;
import io.kairo.code.core.hook.SessionMetricsHook;
import io.kairo.code.core.hook.SessionResultWriterHook;
import io.kairo.code.core.hook.StaleReadDetectorHook;
import io.kairo.code.core.hook.CheckpointWriterHook;
import io.kairo.code.core.hook.TestFailureFeedbackHook;
import io.kairo.code.core.hook.TextOnlyStallHook;
import io.kairo.code.core.hook.ToolBudgetHook;
import io.kairo.code.core.hook.UnfulfilledInstructionHook;
import io.kairo.core.agent.AgentBuilder;
import io.kairo.core.context.CompactionThresholds;
import io.kairo.core.model.ModelRegistry;
import java.nio.file.Path;
import java.time.Duration;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kairo.api.model.ProviderSpec;
import io.kairo.core.model.DefaultProviderRegistry;
import io.kairo.core.model.openai.OpenAIProvider;
import io.kairo.code.core.task.TaskTool;
import io.kairo.code.core.task.TaskToolDependencies;
import io.kairo.code.core.team.SwarmCoordinator;
import io.kairo.code.core.team.tools.ExpertTeamTool;
import io.kairo.core.tool.DefaultPermissionGuard;
import io.kairo.core.tool.DefaultToolExecutor;
import io.kairo.core.tool.DefaultToolRegistry;
import io.kairo.mcp.McpClientRegistry;
import io.kairo.mcp.McpToolExecutor;
import io.kairo.mcp.McpToolGroup;
import io.kairo.tools.exec.BashTool;
import io.kairo.tools.exec.GitTool;
import io.kairo.tools.file.EditTool;
import io.kairo.tools.file.GlobTool;
import io.kairo.tools.file.GrepTool;
import io.kairo.tools.file.ReadTool;
import io.kairo.tools.file.TreeTool;
import io.kairo.tools.file.WriteTool;
import io.kairo.tools.agent.TodoReadTool;
import io.kairo.tools.agent.TodoWriteTool;
import io.kairo.tools.info.AskUserTool;
import io.kairo.tools.info.WebFetchTool;
import io.kairo.tools.cron.SleepTool;
import io.kairo.tools.exec.MvnTool;
import io.kairo.tools.exec.VerifyExecutionTool;
import io.kairo.tools.workflow.WorkflowTool;
import io.kairo.tools.file.BatchReadTool;
import io.kairo.tools.file.BatchWriteTool;
import io.kairo.tools.file.DiffTool;
import io.kairo.tools.file.JsonQueryTool;
import io.kairo.tools.file.PatchApplyTool;
import io.kairo.tools.file.SearchReplaceTool;
import io.kairo.tools.info.HttpTool;
import io.kairo.tools.info.WebSearchTool;
import io.kairo.tools.vcs.GithubTool;
import io.kairo.tools.skill.SkillListTool;
import io.kairo.tools.skill.SkillLoadTool;
import io.kairo.tools.skill.SkillManageTool;
import io.kairo.tools.agent.EnterPlanModeTool;
import io.kairo.tools.agent.ExitPlanModeTool;
import io.kairo.tools.agent.ListPlansTool;
import io.kairo.core.plan.PlanFileManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory that creates a configured Kairo {@link Agent} for code tasks.
 *
 * <p>Registers the standard file and exec tools (bash, read, write, edit, grep, glob), wires a
 * permission guard, and loads the coding-focused system prompt from classpath. Optionally injects
 * loaded skills into the system prompt and restores conversation history from a snapshot.
 */
public final class CodeAgentFactory {

    private static final Logger log = LoggerFactory.getLogger(CodeAgentFactory.class);
    private static final String SYSTEM_PROMPT_RESOURCE = "system-prompt.md";
    static final String SYSTEM_PROMPT_CLAUDE_RESOURCE = "system-prompt-claude.md";
    static final String SYSTEM_PROMPT_GLM_RESOURCE = "system-prompt-glm.md";
    static final String SYSTEM_PROMPT_ENV = "KAIRO_CODE_SYSTEM_PROMPT";

    private CodeAgentFactory() {}

    /** Create a fully-wired agent from the given configuration. */
    public static Agent create(CodeAgentConfig config) {
        return createSession(config, SessionOptions.empty()).agent();
    }

    /** Create an agent with a custom model provider (useful for testing). */
    public static Agent create(CodeAgentConfig config, ModelProvider modelProvider) {
        return createSession(config, SessionOptions.empty().withModelProvider(modelProvider))
                .agent();
    }

    /**
     * Create an agent with hooks and an optional approval handler.
     *
     * <p>When hooks are provided, streaming is automatically enabled so hook listeners receive
     * events in real-time.
     */
    public static Agent create(
            CodeAgentConfig config, UserApprovalHandler approvalHandler, List<Object> hooks) {
        return createSession(
                        config,
                        SessionOptions.empty()
                                .withApprovalHandler(approvalHandler)
                                .withHooks(hooks))
                .agent();
    }

    /** Create an agent with a custom model provider, optional approval handler, and hooks. */
    public static Agent create(
            CodeAgentConfig config,
            ModelProvider modelProvider,
            UserApprovalHandler approvalHandler,
            List<Object> hooks) {
        return createSession(
                        config,
                        SessionOptions.empty()
                                .withModelProvider(modelProvider)
                                .withApprovalHandler(approvalHandler)
                                .withHooks(hooks))
                .agent();
    }

    /**
     * Create a session bundle: agent + tool executor + registry + active-skill set.
     *
     * <p>Used by the REPL to mutate runtime state (toggle plan mode, swap skills, restore from a
     * snapshot) without exposing internal components through the {@link Agent} contract.
     */
    public static CodeAgentSession createSession(CodeAgentConfig config, SessionOptions options) {
        if (options == null) options = SessionOptions.empty();

        ModelProvider modelProvider =
                options.modelProvider() != null
                        ? options.modelProvider()
                        : buildModelProvider(config.apiKey(), config.baseUrl());

        DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.registerTool(BashTool.class);
        registry.registerTool(ReadTool.class);
        // M-D1': WriteTool / EditTool gain LSP post-edit diagnostics when an LspService is
        // wired via LspServiceHolder.setGlobal(). The instance form bypasses the no-arg
        // ctor that the class form would pick. When no LSP is wired (childSession spawns,
        // tests), the LspService is null and the tools fall back to the original behavior.
        var lspForTools = io.kairo.code.core.LspServiceHolder.global();
        registry.registerInstance("write", new WriteTool(null, lspForTools));
        registry.registerInstance("edit", new EditTool(null, lspForTools));
        registry.registerTool(GrepTool.class);
        registry.registerTool(GlobTool.class);
        registry.registerTool(WebFetchTool.class);
        registry.registerTool(GitTool.class);
        registry.registerTool(AskUserTool.class);
        registry.registerTool(TodoReadTool.class);
        registry.registerTool(TodoWriteTool.class);
        registry.registerTool(TreeTool.class);
        // --- M57: extended tool set ---
        registry.registerTool(DiffTool.class);
        registry.registerTool(JsonQueryTool.class);
        registry.registerTool(HttpTool.class);
        registry.registerTool(BatchReadTool.class);
        registry.registerTool(BatchWriteTool.class);
        registry.registerTool(PatchApplyTool.class);
        registry.registerTool(SearchReplaceTool.class);
        registry.registerTool(MvnTool.class);
        // verify_execution: language-agnostic Plan→Execute→Verify closer. Auto-detects
        // mvn/npm/pytest/cargo/make and reports per-command pass/fail + verified bool.
        // Paired with PostBatchEditVerifyHook's nudge after batch Java edits.
        registry.registerTool(VerifyExecutionTool.class);
        // Sleep: lets the agent voluntarily pause (poll CI, rate-limit bulk ops, demo pacing).
        // Reactor-cancellable, capped at 24h. For longer waits the agent should use CronCreate.
        registry.registerTool(SleepTool.class);
        // Workflow tool — schema registration only here. The instance + executor injection
        // happens AFTER the DefaultToolExecutor is constructed below (it doesn't exist yet at
        // this point in the bootstrap).
        registry.registerTool(WorkflowTool.class);
        registry.registerTool(GithubTool.class);
        if (System.getenv("TAVILY_API_KEY") != null && !System.getenv("TAVILY_API_KEY").isBlank()) {
            registry.registerTool(WebSearchTool.class);
        }
        // --- M57-002: skill tools (no executor dependency) ---

        // Skill tools require SkillRegistry; only register when one is wired.
        if (options.skillRegistry() != null) {
            SkillRegistry skillReg = options.skillRegistry();
            registry.registerTool(SkillListTool.class);
            registry.registerInstance("skill_list", new SkillListTool(skillReg));
            registry.registerTool(SkillLoadTool.class);
            registry.registerInstance("skill_load", new SkillLoadTool(skillReg));
            // SkillManageTool — skill creation/edit/delete for the agent
            Path historyPath = Path.of(System.getProperty("user.home"),
                ".kairo-code", "skill-history.jsonl");
            io.kairo.skill.SkillChangeHistory changeHistory =
                new io.kairo.skill.SkillChangeHistory(historyPath);
            List<String> searchPaths = new java.util.ArrayList<>();
            if (config.workingDir() != null && !config.workingDir().isBlank()) {
                searchPaths.add(config.workingDir());
            }
            SkillManageTool skillManage =
                new SkillManageTool(skillReg, changeHistory, searchPaths, false);
            registry.registerTool(SkillManageTool.class);
            registry.registerInstance("skill_manage", skillManage);
        }

        // Register the task tool only when dependencies are wired AND this is not a child session.
        // Child sessions never get TaskTool — recursion is out of scope for M3.
        TaskToolDependencies taskDeps = options.taskToolDependencies();
        if (taskDeps != null && !options.childSession()) {
            registry.registerTool(TaskTool.class);
        }

        // Register the expert team tool when a SwarmCoordinator is wired AND this is not a
        // child session. Like TaskTool, recursive expert teams are out of scope — a child
        // session should not be able to spawn its own team.
        if (options.swarmCoordinator() != null && !options.childSession()) {
            registry.registerTool(ExpertTeamTool.class);
            registry.registerInstance(
                    "expert_team", new ExpertTeamTool(options.swarmCoordinator()));
        }

        // Wire MCP tools from config if present.
        McpClientRegistry mcpRegistry = registerMcpTools(registry, config);

        PermissionGuard guard = new DefaultPermissionGuard();

        // Build the guardrail chain BEFORE the executor so PRE_TOOL/POST_TOOL policies
        // (DangerousCommandPolicy, PathTraversalPolicy, ToolLoopDetectionPolicy) actually
        // fire. The chain on AgentBuilder only covers PRE_MODEL/POST_MODEL — without
        // wiring it into DefaultToolExecutor, DangerousCommandPolicy is dead code (log:
        // "GuardrailChain not configured — guardrail evaluation disabled for this executor").
        // Same instance threaded into AgentBuilder.guardrailChain() below so both layers
        // share state and tests can swap in one mock.
        io.kairo.core.guardrail.policy.LlmBashClassifier llmClassifier =
                buildLlmBashClassifierIfEnabled(config, options, modelProvider);
        io.kairo.api.guardrail.GuardrailChain guardrailChain =
                buildGuardrailChainOrNull(llmClassifier);

        DefaultToolExecutor executor =
                new DefaultToolExecutor(
                        registry,
                        guard,
                        options.tracer(),
                        null,
                        3, // DEFAULT_CIRCUIT_BREAKER_THRESHOLD — package-private upstream
                        guardrailChain);
        // In one-shot / batch mode (no approval handler), auto-approve all tools
        // so bash and other SYSTEM_CHANGE tools can execute without blocking.
        if (options.approvalHandler() == null && !options.isRepl()) {
            executor.setPermissionMode(
                    io.kairo.core.tool.permission.PermissionMode.BYPASS);
        }

        // Workflow tool — the schema is already registered above; now inject the executor
        // so the tool can dispatch each step back through the same executor the agent uses.
        WorkflowTool workflowTool = new WorkflowTool();
        workflowTool.setToolExecutor(executor);
        registry.registerInstance("workflow", workflowTool);

        // --- M57-002: plan mode tools (require executor) ---
        EnterPlanModeTool enterPlanMode = new EnterPlanModeTool();
        ExitPlanModeTool exitPlanMode = new ExitPlanModeTool();
        enterPlanMode.setToolExecutor(executor);
        exitPlanMode.setToolExecutor(executor);
        if (options.approvalHandler() != null) {
            exitPlanMode.setApprovalHandler(options.approvalHandler());
        }
        registry.registerTool(EnterPlanModeTool.class);
        registry.registerInstance("enter_plan_mode", enterPlanMode);
        registry.registerTool(ExitPlanModeTool.class);
        registry.registerInstance("exit_plan_mode", exitPlanMode);
        if (config.workingDir() != null && !config.workingDir().isBlank()) {
            PlanFileManager pfm = new PlanFileManager(Path.of(config.workingDir(), ".plans"));
            enterPlanMode.setPlanFileManager(pfm);
            exitPlanMode.setPlanFileManager(pfm);
            ListPlansTool listPlans = new ListPlansTool();
            listPlans.setPlanFileManager(pfm);
            registry.registerTool(ListPlansTool.class);
            registry.registerInstance("list_plans", listPlans);
        }

        Set<String> activeSkills = options.activeSkills();
        String systemPrompt =
                resolveSystemPrompt(
                        config,
                        modelProvider,
                        options.skillRegistry(),
                        activeSkills,
                        options.toolUsageTracker(),
                        options.memoryStore());

        AgentBuilder builder =
                AgentBuilder.create()
                        .name("kairo-code")
                        .model(modelProvider)
                        .tools(registry)
                        .toolExecutor(executor)
                        .modelName(config.modelName())
                        .systemPrompt(systemPrompt)
                        .maxIterations(config.maxIterations())
                        // Pin tokenBudget to the model's actual contextWindow so IterationGuards'
                        // hard wall matches reality. AgentConfig's hardcoded 200K default
                        // misaligns silently for non-Anthropic models (gpt-4o=128K, 1M variants,
                        // etc.) — burn through then GRACEFUL_EXIT fires either too early or
                        // too late. ModelRegistry returns sensible defaults for unknown models.
                        .tokenBudget(ModelRegistry.getContextWindow(config.modelName()))
                        // 4h ceiling — coding sessions involve human review (plan approval,
                        // tool approval, edit confirmation) that easily exceeds the 10min
                        // AgentConfig default. The user can always interrupt explicitly via
                        // AgentService.stopAgent(); this just prevents spurious timeouts.
                        .timeout(Duration.ofHours(4))
                        // Wire the 6-stage ContextCompactionEngine. Without this, contextManager
                        // stays null and CompactionTrigger short-circuits — long sessions blow
                        // through the 200K budget and IterationGuards fires GRACEFUL_EXIT.
                        // Defaults trigger snip@0.80 → micro@0.85 → collapse@0.90 → auto@0.95
                        // → partial@0.98 of effective budget (context − maxOutput − 13K buffer).
                        .compactionThresholds(CompactionThresholds.DEFAULTS)
                        // Enable smart continuation: when the model emits text-only
                        // narration (zero tool calls) mid-task, nudge it to keep going
                        // instead of terminating prematurely. Critical for chatty models
                        // like glm-5.1 that narrate between tool rounds.
                        .withSmartContinuation();

        if (taskDeps != null && !options.childSession()) {
            Map<String, Object> deps = new LinkedHashMap<>();
            deps.put(TaskToolDependencies.class.getName(), taskDeps);
            builder.toolDependencies(deps);
        }

        if (options.approvalHandler() != null) {
            builder.approvalHandler(options.approvalHandler());
        }

        if (options.tracer() != null) {
            builder.tracer(options.tracer());
        }

        // Streaming enables per-token output AND eager tool dispatch via
        // StreamingToolDetector. M-A4 found that MiniMax M2 emits tool_calls in a single
        // SSE chunk with finish_reason already set; the detector pipeline still routes the
        // call to the executor (the file actually gets written), but the final synthetic
        // ModelResponse that flows to PostReasoning sometimes loses the ToolUseContent —
        // checkpoint then has only the model's `<think>` text. Until that root cause is
        // fixed (see follow-on task), `KAIRO_STREAMING=off` falls back to the non-streaming
        // call() path which uses OpenAIResponseParser and reliably surfaces tool_calls in
        // every PostReasoning event.
        boolean streamingEnabled = !"off".equalsIgnoreCase(System.getenv("KAIRO_STREAMING"));
        builder.streaming(streamingEnabled);
        List<Object> hooks = options.hooks();
        for (Object hook : hooks) {
            builder.hook(hook);
        }

        // Auto-register ContextWindowGuardHook: warns on large context to prevent GLM-5.1
        // overflow. Active in both REPL and one-shot mode.
        builder.hook(new ContextWindowGuardHook());

        // Auto-register ContextCompactionHook: when context approaches the limit, injects a
        // compaction request so the model summarizes work and continues. Non-REPL only.
        // Callers that need to observe compaction events (e.g. transport bridges that surface
        // CONTEXT_COMPACTED to clients) may supply their own ContextCompactionHook via
        // {@link SessionOptions#withHooks(List)} — in that case we skip the auto-registration so
        // the user-supplied instance (with its listener) is the single source of truth.
        if (!options.isRepl() && !hasHookOfType(hooks, ContextCompactionHook.class)) {
            builder.hook(new ContextCompactionHook());
        }

        // Auto-register StaleReadDetectorHook: warns when the agent re-reads the same file
        // multiple times, to improve token efficiency. Active in both REPL and one-shot mode.
        builder.hook(new StaleReadDetectorHook());

        // Auto-register TestFailureFeedbackHook: intercepts bash mvn test failures and injects
        // structured error context so the agent focuses on the right fixes.
        builder.hook(new TestFailureFeedbackHook());

        // Auto-register CompileErrorFeedbackHook: detects Maven compilation errors in bash
        // tool results and injects a reminder to fix them before proceeding. Non-REPL only.
        if (!options.isRepl()) {
            builder.hook(new CompileErrorFeedbackHook());
        }

        // Auto-register PlanWithoutActionHook: detects plan-only responses (todo_write without
        // implementation tools) and injects a corrective message. Disabled in REPL mode.
        // Survives the non-REPL block so the constructor at the bottom can pass it
        // into CodeAgentSession. Null for REPL sessions (no SessionMetricsHook registered).
        SessionMetricsCollector sessionMetrics = null;

        if (!options.isRepl()) {
            TurnMetricsCollector metrics = findTurnMetricsCollector(hooks);
            if (metrics != null) {
                builder.hook(new MaxTurnsGuardHook(metrics));
            }

            // ToolBudgetHook: guards against excessive total tool calls.
            // warn = 60% of force (floor); when force=0 use env defaults.
            int forceThreshold = config.toolBudgetForce();
            if (forceThreshold > 0) {
                int warnThreshold = (int) Math.floor(forceThreshold * 0.6);
                builder.hook(new ToolBudgetHook(false, warnThreshold, forceThreshold));
            } else {
                builder.hook(new ToolBudgetHook(false));
            }

            // RepetitiveToolHook: detects same-tool called in consecutive turns.
            int repThreshold = config.repetitiveToolThreshold();
            if (repThreshold > 0) {
                builder.hook(new RepetitiveToolHook(false, repThreshold));
            } else {
                builder.hook(new RepetitiveToolHook(false));
            }

            builder.hook(new PlanWithoutActionHook());
            builder.hook(new NoWriteDetectedHook());
            builder.hook(new TextOnlyStallHook());
            builder.hook(new PostEditHintHook());
            builder.hook(new PostBatchEditVerifyHook());
            builder.hook(new MissingTestHintHook());
            builder.hook(new AutoCommitOnSuccessHook());

            // Auto-register FullTestSuiteHook: detects partial test runs (single test class)
            // and reminds the agent to run the full mvn test suite. Non-REPL only.
            builder.hook(new FullTestSuiteHook());

            // Auto-register RichLoopDetectorHook: 5-pattern OpenHands-style detector
            // (repeating-action / alternating / no-progress / context-explosion).
            // Ported from kairo-code-eval after watching eval runs burn budgets on
            // doom loops the existing RepetitiveToolHook didn't catch. Fires ONE
            // coaching message per session; KAIRO_CODE_LOOP_DETECTOR=off disables.
            builder.hook(new RichLoopDetectorHook(false));

            // Auto-register UnfulfilledInstructionHook: scans task for "Create ...Test.java"
            // requirements and injects a reminder if the files don't exist yet. Non-REPL only.
            String wd = config.workingDir();
            if (wd != null && !wd.isBlank()) {
                builder.hook(new UnfulfilledInstructionHook(wd));
            }

            // Auto-register SessionMetricsCollector + SessionMetricsHook: tracks tool call
            // distribution, redundant file reads, idle iterations, and hook interventions.
            // Non-REPL only.
            // Renamed from local `metricsCollector` so it doesn't shadow the
            // TurnMetricsCollector lookup at the end of this method. The reference is
            // also passed to CodeAgentSession so AgentService / REST / dispatcher can
            // read live mid-session metrics without waiting for SESSION_END.
            sessionMetrics = new SessionMetricsCollector();
            builder.hook(new SessionMetricsHook(sessionMetrics));

            // Auto-register ExecutionTraceHook: writes per-phase JSONL events to
            // .kairo-trace/session-{ts}.jsonl for agent self-reflection. Non-REPL only.
            if (wd != null && !wd.isBlank()) {
                builder.hook(new ExecutionTraceHook(Path.of(wd)));
            }

            // Auto-register SessionResultWriterHook: writes KAIRO_SESSION_RESULT.json on session
            // end so the dispatcher can machine-read the outcome. Enriched with metrics.
            if (wd != null && !wd.isBlank()) {
                builder.hook(new SessionResultWriterHook(Path.of(wd), sessionMetrics));
            }

            // Auto-register CheckpointWriterHook: persists conversation history to
            // .kairo-session/checkpoint.json after each reasoning step for resume support.
            if (wd != null && !wd.isBlank()) {
                builder.hook(new CheckpointWriterHook(
                        Path.of(wd), new ObjectMapper(), options.checkpointInitialMessage()));
            }
        }

        if (options.textDeltaConsumer() != null) {
            builder.textDeltaConsumer(options.textDeltaConsumer());
        }

        if (options.restoreFrom() != null) {
            builder.restoreFrom(options.restoreFrom());
        }

        // Same chain instance the executor uses (built above before executor construction).
        // Wired here so PRE_MODEL / POST_MODEL phases (PiiRedactionPolicy on model output)
        // share the chain with PRE_TOOL / POST_TOOL phases (DangerousCommandPolicy on bash).
        // Single instance = stateful policies (e.g. ToolLoopDetectionPolicy) see the full
        // request lifecycle.
        if (guardrailChain != null) {
            builder.guardrailChain(guardrailChain);
        }

        Agent agent = builder.build();
        ToolUsageTracker tracker = findToolUsageTracker(hooks);
        TurnMetricsCollector turnMetrics = findTurnMetricsCollector(hooks);
        return new CodeAgentSession(
                agent, executor, registry, activeSkills, mcpRegistry,
                tracker, turnMetrics, sessionMetrics, llmClassifier);
    }

    /**
     * Build the LLM-backed fallback classifier iff the user opted in via
     * {@link LlmClassifierConfig#enabled()}, else return {@code null}. The fallback rides on the
     * SAME {@link ModelProvider} the agent already authenticated — no extra credentials to manage,
     * and any tracer the caller passed via {@link SessionOptions#tracer()} is propagated so the
     * LLM span shows up next to the agent's reasoning spans in Langfuse / OTel.
     *
     * <p>Split out of {@code buildDangerousCommandPolicy} so the same classifier instance can also
     * be threaded into {@link CodeAgentSession} for {@code :stats} visibility — without this seam
     * the REPL has no way to confirm the fallback is actually firing.
     */
    static io.kairo.core.guardrail.policy.LlmBashClassifier buildLlmBashClassifierIfEnabled(
            CodeAgentConfig config, SessionOptions options, ModelProvider modelProvider) {
        LlmClassifierConfig llmCfg = config.llmClassifier();
        if (llmCfg == null || !llmCfg.enabled()) {
            return null;
        }
        String model =
                (llmCfg.model() != null && !llmCfg.model().isBlank())
                        ? llmCfg.model()
                        : config.modelName();
        var cfgBuilder =
                io.kairo.core.guardrail.policy.LlmBashClassifier.Config.builder()
                        .cacheSize(llmCfg.cacheSize())
                        .timeout(java.time.Duration.ofMillis(llmCfg.timeoutMillis()));
        if (options != null && options.tracer() != null) {
            cfgBuilder.tracer(options.tracer());
        }
        return new io.kairo.core.guardrail.policy.LlmBashClassifier(
                modelProvider, model, cfgBuilder.build());
    }

    /**
     * Assemble the full guardrail chain (PII redaction + dangerous command + path traversal +
     * tool loop detection) and return it ready to wire into both the executor (PRE_TOOL /
     * POST_TOOL) and the agent (PRE_MODEL / POST_MODEL). Returns {@code null} if construction
     * fails — bootstrapping the agent without a chain is acceptable degraded mode, blowing up
     * the whole session is not.
     *
     * <p>{@code KAIRO_PII_REDACTION=off} skips the PII step (debugging) — the dangerous-command
     * / path-traversal / loop policies stay on regardless.
     */
    static io.kairo.api.guardrail.GuardrailChain buildGuardrailChainOrNull(
            io.kairo.core.guardrail.policy.LlmBashClassifier classifier) {
        try {
            var policies = new java.util.ArrayList<io.kairo.api.guardrail.GuardrailPolicy>();
            if (!"off".equalsIgnoreCase(System.getenv("KAIRO_PII_REDACTION"))) {
                policies.add(new io.kairo.security.pii.PiiRedactionPolicy());
            }
            policies.add(buildDangerousCommandPolicy(classifier));
            policies.add(new io.kairo.core.guardrail.policy.PathTraversalPolicy());
            policies.add(new io.kairo.core.guardrail.policy.ToolLoopDetectionPolicy());
            return new io.kairo.core.guardrail.DefaultGuardrailChain(policies);
        } catch (Throwable t) {
            LoggerFactory.getLogger(CodeAgentFactory.class)
                    .warn("Failed to wire guardrail chain: {}", t.getMessage());
            return null;
        }
    }

    /**
     * Wrap the (optional) LLM fallback in a {@link io.kairo.core.guardrail.policy.DangerousCommandPolicy}.
     * Null classifier → heuristic-only policy. Failures inside the classifier degrade to
     * {@code UNKNOWN→ALLOW} (preserving today's semantics), so a model outage cannot escalate to
     * a deny-storm.
     */
    static io.kairo.core.guardrail.policy.DangerousCommandPolicy buildDangerousCommandPolicy(
            io.kairo.core.guardrail.policy.LlmBashClassifier classifier) {
        if (classifier == null) {
            return new io.kairo.core.guardrail.policy.DangerousCommandPolicy();
        }
        return new io.kairo.core.guardrail.policy.DangerousCommandPolicy(
                io.kairo.core.guardrail.policy.DangerousCommandPolicy.DEFAULT_SHELL_TOOLS,
                classifier);
    }

    /**
     * Find the ToolUsageTracker instance from the hooks list so it can be exposed
     * via CodeAgentSession for the :stats command.
     */
    /**
     * Build a {@link ModelProvider} from the (apiKey, baseUrl) pair. Routes through {@link
     * DefaultProviderRegistry#withBuiltIns()} so adding a provider upstream automatically becomes
     * available here. The URL-sniffing heuristic stays for two reasons:
     *
     * <ul>
     *   <li>A baseUrl containing {@code "anthropic"} → Anthropic Messages API.
     *   <li>A baseUrl ending in {@code /v<N>} (e.g. GLM's {@code /api/paas/v4}) → OpenAI-compatible
     *       with explicit {@code /chat/completions} path (otherwise we'd get
     *       {@code /v4/v1/chat/completions}).
     * </ul>
     *
     * <p>Everything else flows through the registry's {@code openai-compatible} preset (or the
     * default {@code openai} when no baseUrl is given).
     */
    /**
     * Build a {@link ModelProvider} from raw config without going through a full
     * {@link #createSession}. Exposed so CLI bootstrap (e.g. {@code ReplLoop}) can construct
     * auxiliary subsystems — Expert Team coordinator, evaluation harnesses — that need the same
     * provider wiring as the session agent but don't need the tool registry / hooks pipeline.
     */
    public static ModelProvider buildModelProvider(String apiKey, String baseUrl) {
        var registry = DefaultProviderRegistry.withBuiltIns();
        if (baseUrl != null && baseUrl.toLowerCase().contains("anthropic")) {
            return registry.create("anthropic", ProviderSpec.of(apiKey, baseUrl));
        }
        if (baseUrl != null && baseUrl.matches(".*/v\\d+/?$")) {
            // Corner case the registry doesn't model directly: URL already includes the version
            // segment, so we construct the OpenAIProvider 3-arg variant explicitly.
            String url =
                    baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            return new OpenAIProvider(apiKey, url, "/chat/completions");
        }
        if (baseUrl != null && !baseUrl.isBlank()) {
            return registry.create("openai-compatible", ProviderSpec.of(apiKey, baseUrl));
        }
        return registry.create("openai", ProviderSpec.of(apiKey));
    }

    private static ToolUsageTracker findToolUsageTracker(List<Object> hooks) {
        if (hooks == null) return null;
        for (Object hook : hooks) {
            if (hook instanceof ToolUsageTracker t) return t;
        }
        return null;
    }

    /**
     * Find the TurnMetricsCollector instance from the hooks list so it can be exposed
     * via CodeAgentSession for the /metrics command.
     */
    private static TurnMetricsCollector findTurnMetricsCollector(List<Object> hooks) {
        if (hooks == null) return null;
        for (Object hook : hooks) {
            if (hook instanceof TurnMetricsCollector c) return c;
        }
        return null;
    }

    /**
     * Returns {@code true} if any hook in the list is an instance of {@code type}. Used to
     * suppress auto-registration of default hooks when the caller has supplied a customised one.
     */
    private static boolean hasHookOfType(List<Object> hooks, Class<?> type) {
        if (hooks == null) return false;
        for (Object hook : hooks) {
            if (type.isInstance(hook)) return true;
        }
        return false;
    }

    /**
     * Register MCP tools from the given config into the tool registry.
     *
     * <p>Returns the {@link McpClientRegistry} if any servers were configured, or null otherwise.
     * Connection failures are logged as warnings and do not block startup.
     */
    private static McpClientRegistry registerMcpTools(
            DefaultToolRegistry registry, CodeAgentConfig config) {
        McpConfig mcpConfig = config.mcpConfig();
        if (mcpConfig == null || mcpConfig.servers().isEmpty()) {
            return null;
        }

        McpClientRegistry mcpRegistry = new McpClientRegistry();
        for (var entry : mcpConfig.servers().entrySet()) {
            String name = entry.getKey();
            try {
                io.kairo.mcp.McpServerConfig serverConfig =
                        entry.getValue().toServerConfig();
                McpToolGroup group = mcpRegistry.register(serverConfig).block();
                if (group != null) {
                    for (ToolDefinition def : group.getAllToolDefinitions()) {
                        registry.register(def);
                        McpToolExecutor executor = group.getExecutor(def.name());
                        if (executor != null) {
                            registry.registerInstance(def.name(), executor);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to connect to MCP server '{}': {}", name, e.getMessage());
            }
        }
        return mcpRegistry;
    }

    private static String resolveSystemPrompt(
            CodeAgentConfig config,
            ModelProvider modelProvider,
            SkillRegistry skillRegistry,
            Set<String> activeSkills,
            ToolUsageTracker toolUsageTracker,
            MemoryStore memoryStore) {
        String resourceName = selectSystemPromptResource(modelProvider, config);
        StringBuilder prompt = new StringBuilder(loadSystemPrompt(resourceName));
        if (skillRegistry != null && !activeSkills.isEmpty()) {
            String skillSection = renderSkillSection(skillRegistry, activeSkills);
            if (!skillSection.isBlank()) {
                prompt.append("\n\n").append(skillSection);
            }
        }
        if (config.workingDir() != null && !config.workingDir().isBlank()) {
            prompt.append("\n\n## Working Directory\nYour current working directory is: ")
                    .append(config.workingDir())
                    .append(
                            "\nAll file operations and commands should be relative to this"
                                    + " directory.");
            KairoMdContextSource kairoMdSource =
                    new KairoMdContextSource(Path.of(config.workingDir()));
            String kairoMdContent = kairoMdSource.collect();
            if (!kairoMdContent.isEmpty()) {
                prompt.append("\n\n").append(kairoMdContent);
            }

            // Append dynamic Session Context: date + git status.
            prompt.append("\n\n## Session Context");
            prompt.append("\nDate: ").append(java.time.LocalDate.now().toString());
            String gitStatus = readGitStatus(config.workingDir());
            if (gitStatus != null && !gitStatus.isBlank()) {
                prompt.append("\n\n### Working Tree Status\n```\n")
                        .append(gitStatus)
                        .append("\n```");
            }
        }
        Path globalKairoDir = Path.of(System.getProperty("user.home"), ".kairo-code");
        LearnedLessonStore lessonStore = LearnedLessonStore.fromKairoDir(globalKairoDir);
        String enriched = SessionContextEnricher.enrich(prompt.toString(), toolUsageTracker, lessonStore);

        // Append persistent memories from previous sessions (if any).
        String memorySection = SessionMemoryEnricher.buildMemorySection(memoryStore);
        if (!memorySection.isEmpty()) {
            enriched = enriched + memorySection;
        }
        return enriched;
    }

    private static String renderSkillSection(SkillRegistry registry, Set<String> activeSkills) {
        StringBuilder sb = new StringBuilder("## Active Skills\n");
        boolean any = false;
        for (String name : new LinkedHashSet<>(activeSkills)) {
            SkillDefinition skill = registry.get(name).orElse(null);
            if (skill == null || skill.instructions() == null || skill.instructions().isBlank()) {
                log.debug("Skipping skill '{}' (not in registry or no instructions)", name);
                continue;
            }
            any = true;
            sb.append("\n### ").append(skill.name()).append("\n").append(skill.instructions());
        }
        return any ? sb.toString() : "";
    }

    /**
     * Choose a system-prompt resource name based on (in order):
     * <ol>
     *   <li>{@code KAIRO_CODE_SYSTEM_PROMPT} environment variable — explicit override.</li>
     *   <li>The {@link ModelProvider} type — Anthropic providers get the Claude variant.</li>
     *   <li>The configured model name — names containing {@code "glm"} get the GLM variant.</li>
     *   <li>Otherwise the generic default.</li>
     * </ol>
     *
     * <p>Visible for testing.
     */
    static String selectSystemPromptResource(ModelProvider modelProvider, CodeAgentConfig config) {
        String envOverride = System.getenv(SYSTEM_PROMPT_ENV);
        if (envOverride != null && !envOverride.isBlank()) {
            return envOverride.trim();
        }
        // Check modelName FIRST — model is the most specific signal of what
        // capabilities the agent actually has. The previous order made
        // AnthropicProvider class beat a glm-* modelName, so wrapping GLM
        // behind an Anthropic-compatible proxy got the Claude prompt.
        if (config != null && config.modelName() != null) {
            String model = config.modelName().toLowerCase();
            if (model.contains("claude")) {
                return SYSTEM_PROMPT_CLAUDE_RESOURCE;
            }
            if (model.contains("glm")) {
                return SYSTEM_PROMPT_GLM_RESOURCE;
            }
        }
        if (modelProvider != null) {
            String providerClass = modelProvider.getClass().getName().toLowerCase();
            if (providerClass.contains("anthropic")) {
                return SYSTEM_PROMPT_CLAUDE_RESOURCE;
            }
        }
        return SYSTEM_PROMPT_RESOURCE;
    }

    private static String loadSystemPrompt(String resourceName) {
        try (InputStream is =
                CodeAgentFactory.class
                        .getClassLoader()
                        .getResourceAsStream(resourceName)) {
            if (is != null) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            log.warn(
                    "System prompt resource '{}' not found on classpath, falling back to '{}'",
                    resourceName,
                    SYSTEM_PROMPT_RESOURCE);
        } catch (IOException e) {
            log.error("Failed to load system prompt resource '{}'", resourceName, e);
        }
        if (!SYSTEM_PROMPT_RESOURCE.equals(resourceName)) {
            try (InputStream is =
                    CodeAgentFactory.class
                            .getClassLoader()
                            .getResourceAsStream(SYSTEM_PROMPT_RESOURCE)) {
                if (is != null) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                log.error("Failed to load default system prompt", e);
            }
        }
        return "You are Kairo Code, an expert software engineer AI assistant.";
    }

    /**
     * Run {@code git status --short} in the given working directory.
     *
     * <p>Returns null on any failure (no git, not a repo, timeout, etc.) so the system prompt
     * degrades gracefully. Output is capped at 30 lines.
     */
    private static String readGitStatus(String workingDir) {
        if (workingDir == null || workingDir.isBlank()) return null;
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "status", "--short")
                    .directory(new java.io.File(workingDir))
                    .redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            boolean ok = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!ok || p.exitValue() != 0) return null;
            String[] lines = output.split("\n");
            if (lines.length > 30) {
                output = String.join("\n", java.util.Arrays.copyOf(lines, 30))
                        + "\n... (" + (lines.length - 30) + " more files)";
            }
            return output.isBlank() ? null : output;
        } catch (Exception e) {
            log.debug("Failed to read git status: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Optional inputs to {@link #createSession(CodeAgentConfig, SessionOptions)}. All fields are
     * optional; build via {@link #empty()} and {@code withX(...)} chains.
     */
    public record SessionOptions(
            ModelProvider modelProvider,
            UserApprovalHandler approvalHandler,
            List<Object> hooks,
            SkillRegistry skillRegistry,
            Set<String> activeSkills,
            AgentSnapshot restoreFrom,
            TaskToolDependencies taskToolDependencies,
            boolean childSession,
            java.util.function.Consumer<String> textDeltaConsumer,
            ToolUsageTracker toolUsageTracker,
            TurnMetricsCollector turnMetricsCollector,
            boolean isRepl,
            Msg checkpointInitialMessage,
            MemoryStore memoryStore,
            Tracer tracer,
            SwarmCoordinator swarmCoordinator) {

        public SessionOptions {
            if (hooks == null) hooks = List.of();
            if (activeSkills == null) activeSkills = Set.of();
        }

        public static SessionOptions empty() {
            return new SessionOptions(
                    null, null, List.of(), null, Set.of(), null, null, false, null, null, null, false, null, null, null, null);
        }

        public SessionOptions withModelProvider(ModelProvider provider) {
            return new SessionOptions(
                    provider, approvalHandler, hooks, skillRegistry, activeSkills,
                    restoreFrom, taskToolDependencies, childSession, textDeltaConsumer,
                    toolUsageTracker, turnMetricsCollector, isRepl, checkpointInitialMessage,
                    memoryStore, tracer, swarmCoordinator);
        }

        public SessionOptions withApprovalHandler(UserApprovalHandler handler) {
            return new SessionOptions(
                    modelProvider, handler, hooks, skillRegistry, activeSkills,
                    restoreFrom, taskToolDependencies, childSession, textDeltaConsumer,
                    toolUsageTracker, turnMetricsCollector, isRepl, checkpointInitialMessage,
                    memoryStore, tracer, swarmCoordinator);
        }

        public SessionOptions withHooks(List<Object> hookList) {
            return new SessionOptions(
                    modelProvider, approvalHandler,
                    hookList == null ? List.of() : List.copyOf(hookList),
                    skillRegistry, activeSkills, restoreFrom, taskToolDependencies,
                    childSession, textDeltaConsumer, toolUsageTracker, turnMetricsCollector,
                    isRepl, checkpointInitialMessage, memoryStore, tracer, swarmCoordinator);
        }

        public SessionOptions withSkills(SkillRegistry registry, Set<String> active) {
            return new SessionOptions(
                    modelProvider, approvalHandler, hooks, registry,
                    active == null ? Set.of() : Set.copyOf(active),
                    restoreFrom, taskToolDependencies, childSession, textDeltaConsumer,
                    toolUsageTracker, turnMetricsCollector, isRepl, checkpointInitialMessage,
                    memoryStore, tracer, swarmCoordinator);
        }

        public SessionOptions withRestoreFrom(AgentSnapshot snapshot) {
            return new SessionOptions(
                    modelProvider, approvalHandler, hooks, skillRegistry, activeSkills,
                    snapshot, taskToolDependencies, childSession, textDeltaConsumer,
                    toolUsageTracker, turnMetricsCollector, isRepl, checkpointInitialMessage,
                    memoryStore, tracer, swarmCoordinator);
        }

        public SessionOptions withTaskTool(TaskToolDependencies deps) {
            return new SessionOptions(
                    modelProvider, approvalHandler, hooks, skillRegistry, activeSkills,
                    restoreFrom, deps, childSession, textDeltaConsumer, toolUsageTracker,
                    turnMetricsCollector, isRepl, checkpointInitialMessage, memoryStore, tracer,
                    swarmCoordinator);
        }

        public SessionOptions withTextDeltaConsumer(
                java.util.function.Consumer<String> consumer) {
            return new SessionOptions(
                    modelProvider, approvalHandler, hooks, skillRegistry, activeSkills,
                    restoreFrom, taskToolDependencies, childSession, consumer,
                    toolUsageTracker, turnMetricsCollector, isRepl, checkpointInitialMessage,
                    memoryStore, tracer, swarmCoordinator);
        }

        public SessionOptions asChildSession() {
            return new SessionOptions(
                    modelProvider, approvalHandler, hooks, skillRegistry, activeSkills,
                    restoreFrom, taskToolDependencies, true, textDeltaConsumer,
                    toolUsageTracker, turnMetricsCollector, isRepl, checkpointInitialMessage,
                    memoryStore, tracer, swarmCoordinator);
        }

        public SessionOptions withToolUsageTracker(ToolUsageTracker tracker) {
            return new SessionOptions(
                    modelProvider, approvalHandler, hooks, skillRegistry, activeSkills,
                    restoreFrom, taskToolDependencies, childSession, textDeltaConsumer,
                    tracker, turnMetricsCollector, isRepl, checkpointInitialMessage,
                    memoryStore, tracer, swarmCoordinator);
        }

        public SessionOptions withTurnMetricsCollector(TurnMetricsCollector collector) {
            return new SessionOptions(
                    modelProvider, approvalHandler, hooks, skillRegistry, activeSkills,
                    restoreFrom, taskToolDependencies, childSession, textDeltaConsumer,
                    toolUsageTracker, collector, isRepl, checkpointInitialMessage,
                    memoryStore, tracer, swarmCoordinator);
        }

        public SessionOptions asReplSession() {
            return new SessionOptions(
                    modelProvider, approvalHandler, hooks, skillRegistry, activeSkills,
                    restoreFrom, taskToolDependencies, childSession, textDeltaConsumer,
                    toolUsageTracker, turnMetricsCollector, true, checkpointInitialMessage,
                    memoryStore, tracer, swarmCoordinator);
        }

        /**
         * Set the initial user message for checkpoint persistence.
         * When {@link CheckpointWriterHook} is auto-registered, it uses this message
         * as the first entry in the checkpoint conversation history.
         */
        public SessionOptions withCheckpointInitialMessage(Msg msg) {
            return new SessionOptions(
                    modelProvider, approvalHandler, hooks, skillRegistry, activeSkills,
                    restoreFrom, taskToolDependencies, childSession, textDeltaConsumer,
                    toolUsageTracker, turnMetricsCollector, isRepl, msg,
                    memoryStore, tracer, swarmCoordinator);
        }

        /**
         * Set the memory store for session memory enrichment.
         * When provided, the agent's system prompt is enriched with relevant memories
         * from previous sessions on creation.
         */
        public SessionOptions withMemoryStore(MemoryStore store) {
            return new SessionOptions(
                    modelProvider, approvalHandler, hooks, skillRegistry, activeSkills,
                    restoreFrom, taskToolDependencies, childSession, textDeltaConsumer,
                    toolUsageTracker, turnMetricsCollector, isRepl, checkpointInitialMessage,
                    store, tracer, swarmCoordinator);
        }

        /**
         * Set the tracer for observability.
         */
        public SessionOptions withTracer(Tracer t) {
            return new SessionOptions(
                    modelProvider, approvalHandler, hooks, skillRegistry, activeSkills,
                    restoreFrom, taskToolDependencies, childSession, textDeltaConsumer,
                    toolUsageTracker, turnMetricsCollector, isRepl, checkpointInitialMessage,
                    memoryStore, t, swarmCoordinator);
        }

        /**
         * Set the swarm coordinator. When provided (and not a child session), the
         * {@code expert_team} tool is registered so the model can dispatch sub-tasks to
         * the expert team. Like {@code TaskTool}, child sessions never get this tool —
         * recursive teams are out of scope.
         */
        public SessionOptions withSwarmCoordinator(SwarmCoordinator coord) {
            return new SessionOptions(
                    modelProvider, approvalHandler, hooks, skillRegistry, activeSkills,
                    restoreFrom, taskToolDependencies, childSession, textDeltaConsumer,
                    toolUsageTracker, turnMetricsCollector, isRepl, checkpointInitialMessage,
                    memoryStore, tracer, coord);
        }
    }
}
