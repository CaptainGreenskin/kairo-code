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
import io.kairo.code.core.task.AgentType;
import io.kairo.code.core.evolution.FailurePatternTracker;
import io.kairo.code.core.evolution.LearnedLessonStore;
import io.kairo.code.core.evolution.ReflectionPipeline;
import io.kairo.code.core.memory.AutoMemoryHook;
import io.kairo.code.core.memory.KairoMdContextSource;
import io.kairo.code.core.team.ExpertTeamFactory;
import io.kairo.code.core.profile.UserProfile;
import io.kairo.code.core.profile.UserProfileContextSource;
import io.kairo.code.core.prompt.SessionContextEnricher;
import io.kairo.code.core.prompt.SessionMemoryEnricher;
import io.kairo.code.core.stats.ToolUsageTracker;
import io.kairo.code.core.stats.TurnMetricsCollector;
import io.kairo.code.core.hook.AutoCommitOnSuccessHook;
import io.kairo.code.core.hook.CompileErrorFeedbackHook;
import io.kairo.code.core.hook.ExecutionTraceHook;
import io.kairo.code.core.hook.FullTestSuiteHook;
import io.kairo.code.core.hook.MissingTestHintHook;
import io.kairo.code.core.hook.NoWriteDetectedHook;
import io.kairo.code.core.hook.PlanWithoutActionHook;
import io.kairo.code.core.hook.PostBatchEditVerifyHook;
import io.kairo.code.core.hook.PostEditHintHook;
import io.kairo.code.core.hook.SessionMetricsCollector;
import io.kairo.code.core.hook.SessionMetricsHook;
import io.kairo.code.core.hook.SessionResultWriterHook;
import io.kairo.code.core.hook.StaleReadDetectorHook;
import io.kairo.code.core.hook.TestFailureFeedbackHook;
import io.kairo.code.core.hook.TextOnlyStallHook;
import io.kairo.code.core.hook.UnfulfilledInstructionHook;
import io.kairo.core.governance.ContextSizeGuard;
import io.kairo.core.governance.MaxTurnsGuard;
import io.kairo.core.governance.RepetitiveToolGuard;
import io.kairo.core.governance.ToolCallBudgetGuard;
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
import io.kairo.api.team.MessageBus;
import io.kairo.api.team.TeamManager;
import io.kairo.code.core.team.SwarmCoordinator;
import io.kairo.code.core.team.tools.SendMessageTool;
import io.kairo.code.core.team.tools.TeamCreateTool;
import io.kairo.code.core.team.tools.TeamDeleteTool;
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
    public static CodeAgentSession createSession(
            CodeAgentConfig config, SessionOptions options, Map<String, Object> extraToolDeps) {
        CodeAgentSession session = createSession(config, options);
        if (extraToolDeps != null && !extraToolDeps.isEmpty()) {
            Agent agent = session.agent();
            if (agent instanceof io.kairo.core.agent.DefaultReActAgent dra) {
                dra.mergeToolDependencies(extraToolDeps);
            }
        }
        return session;
    }

    public static CodeAgentSession createSession(CodeAgentConfig config, SessionOptions options) {
        if (options == null) options = SessionOptions.empty();

        ModelProvider modelProvider =
                options.modelProvider() != null
                        ? options.modelProvider()
                        : buildModelProvider(config.apiKey(), config.baseUrl(), config.modelName());

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
        registry.registerTool(io.kairo.tools.agent.SearchToolsTool.class);
        registry.registerTool(io.kairo.tools.agent.ExecuteDeferredTool.class);
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

        // Register the task tool when dependencies are wired.
        // Recursion depth is controlled by the spawner: child sessions get TaskTool with a
        // depth-limited spawner (allows 1 level), grandchildren get no TaskToolDependencies.
        TaskToolDependencies taskDeps = options.taskToolDependencies();
        if (taskDeps != null) {
            registry.registerTool(TaskTool.class);
            // Scripted workflow only for parent sessions — child sessions should not
            // orchestrate sub-workflows (same recursion guard as team tools).
            if (!options.childSession()) {
                registry.registerTool(
                        io.kairo.code.core.workflow.ScriptedWorkflowTool.class);
            }
        }

        // expert_team tool removed from Agent mode — it causes stalls and duplicates
        // the subagent capability already provided by TaskTool. Expert Team is only
        // available via the dedicated Experts mode (escalation path in AgentService).

        // M-Team (#60): register team-collaboration tools (team_create / send_message /
        // team_delete) only when team primitives are wired AND this is not a child session.
        // Child sessions never get team tools — recursion guard mirrors TaskTool, so a worker
        // spawned via the `task` tool cannot itself spin up a sub-team. Per ADR-001 §"Non-goals":
        // nested teams are out of scope and mirror Claude Code's Task/Team child-session guard.
        TeamManager teamManager = options.teamManager();
        MessageBus teamMessageBus = options.teamMessageBus();
        io.kairo.code.core.task.SubagentRegistry subagentReg = options.taskToolDependencies() != null
                ? options.taskToolDependencies().subagentRegistry() : null;
        if (teamManager != null && teamMessageBus != null && !options.childSession()) {
            registry.registerTool(TeamCreateTool.class);
            registry.registerInstance("team_create", new TeamCreateTool(teamManager));
            registry.registerTool(SendMessageTool.class);
            registry.registerInstance("send_message",
                    new SendMessageTool(teamManager, teamMessageBus, subagentReg));
            registry.registerTool(TeamDeleteTool.class);
            registry.registerInstance("team_delete", new TeamDeleteTool(teamManager));
        } else if (subagentReg != null && !options.childSession()) {
            registry.registerTool(SendMessageTool.class);
            registry.registerInstance("send_message",
                    new SendMessageTool(null, null, subagentReg));
        }

        // Shared task board tools — available to ALL agents (coordinator + workers)
        // so workers can claim/complete tasks. The SharedTaskList instance is injected
        // via toolDependencies bean map for cross-session sharing.
        registry.registerTool(io.kairo.code.core.team.tools.SharedTaskCreateTool.class);
        registry.registerTool(io.kairo.code.core.team.tools.SharedTaskUpdateTool.class);
        registry.registerTool(io.kairo.code.core.team.tools.SharedTaskListTool.class);
        registry.registerTool(io.kairo.code.core.team.tools.SharedTaskGetTool.class);

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

        // Coordinator bash write guard: block file-writing bash commands
        // so the coordinator cannot bypass write tool restriction via bash echo.
        if (guardrailChain instanceof io.kairo.core.guardrail.DefaultGuardrailChain dgc
                && options.agentType() != null
                && "coordinator".equals(options.agentType().id())) {
            dgc.addPolicy(new io.kairo.code.core.guardrail.BashWriteGuardPolicy());
        }

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

        // Confine file writes to the workspace: a WRITE whose target escapes workingDir is
        // escalated to ASK (human approval) via the existing approval flow. No-op in BYPASS
        // (one-shot/batch) mode, where there is no approver anyway.
        if (config.workingDir() != null && !config.workingDir().isBlank()) {
            executor.setWorkspaceRoot(Path.of(config.workingDir()));
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

        // ── Hard tool filtering (MUST be after ALL tool registrations) ──────────
        // When an AgentType with a non-null allowedTools whitelist is set, physically
        // remove all tools NOT in the whitelist from the registry. Placed here — after
        // MCP, workflow, plan-mode, skill tools are all registered — so nothing escapes.
        AgentType agentType = options.agentType();
        if (agentType != null && agentType.allowedTools() != null) {
            Set<String> allowed = agentType.allowedTools();
            List<ToolDefinition> toRemove = registry.getAll().stream()
                    .filter(t -> !allowed.contains(t.name()))
                    .toList();
            for (ToolDefinition t : toRemove) {
                registry.unregister(t.name());
            }
            List<String> keptNames = registry.getAll().stream()
                    .map(ToolDefinition::name).sorted().toList();
            List<String> removedNames = toRemove.stream()
                    .map(ToolDefinition::name).sorted().toList();
            log.info("AgentType '{}' tool filtering: kept {}/{} tools — kept={}, removed={}",
                    agentType.id(), keptNames.size(),
                    keptNames.size() + removedNames.size(), keptNames, removedNames);
            // Defense-in-depth: set the same whitelist on the executor so tools
            // registered dynamically after this point are also blocked at call time.
            executor.setAllowedToolNames(agentType.allowedTools());
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

        if (agentType != null && !agentType.systemPromptPrefix().isEmpty()) {
            systemPrompt = agentType.systemPromptPrefix() + "\n\n" + systemPrompt;
        }

        io.kairo.api.cost.CostTracker costTracker = new io.kairo.core.cost.DefaultCostTracker();

        AgentBuilder builder =
                AgentBuilder.create()
                        .name("kairo-code")
                        .model(modelProvider)
                        .tools(registry)
                        .toolExecutor(executor)
                        .modelName(config.modelName())
                        .costTracker(costTracker)
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
                        .compactionThresholds(CompactionThresholds.DEFAULTS);

        // Generic smart continuation (PendingTodoNudge + RecentToolActivity) is disabled:
        // was causing "一直不停止" (infinite nudge loop) in web sessions.
        // Only PendingBackgroundTaskStrategy is wired — it blocks (not nudge-loops)
        // on the notification queue when background subagents are active.
        if (taskDeps != null && taskDeps.subagentRegistry() != null
                && taskDeps.notificationQueue() != null) {
            builder.continuationStrategy(
                    new io.kairo.code.core.task.PendingBackgroundTaskStrategy(
                            taskDeps.subagentRegistry(), taskDeps.notificationQueue()));
        }

        // Bind the session workspace so file tools resolve relative paths against workingDir (not
        // the JVM cwd) — keeping tool path resolution aligned with the executor's workspace-write
        // boundary (setWorkspaceRoot above). Without this, ToolContext defaults to Workspace.cwd().
        if (config.workingDir() != null && !config.workingDir().isBlank()) {
            builder.workspace(new DirWorkspace(Path.of(config.workingDir()).toAbsolutePath().normalize()));
        }

        // Wire session-scoped storage when sessionId is available. The AgentBuilder
        // auto-creates an IterationCheckpointManager from the provider during build().
        if (options.sessionId() != null && config.workingDir() != null) {
            Path sessionRoot = Path.of(config.workingDir()).resolve(".kairo-session");
            builder.sessionId(options.sessionId())
                    .sessionStorage(new io.kairo.core.session.FileSessionStorageProvider(sessionRoot));
        }

        // Tool dependencies: bean map accessible via ToolContext.getBean()
        Map<String, Object> toolDeps = new LinkedHashMap<>();
        if (taskDeps != null && !options.childSession()) {
            toolDeps.put(TaskToolDependencies.class.getName(), taskDeps);
            toolDeps.put(io.kairo.code.core.workflow.WorkflowDependencies.class.getName(),
                    new io.kairo.code.core.workflow.WorkflowDependencies(
                            taskDeps.spawner(),
                            taskDeps.workspaceProvider(),
                            config,
                            io.kairo.code.core.workflow.WorkflowProgressEmitter.SLF4J_INSTANCE,
                            taskDeps.subagentRegistry()));
        }
        // SharedTaskList: shared across ALL sessions in the same process (parent + children).
        // Uses a global singleton so coordinator and spawned workers share the same task board.
        toolDeps.put(io.kairo.code.core.team.SharedTaskList.class.getName(),
                GlobalSharedTaskList.INSTANCE);
        toolDeps.put("toolRegistry", registry);
        toolDeps.put("toolExecutor", executor);
        if (!toolDeps.isEmpty()) {
            builder.toolDependencies(toolDeps);
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
            if (hook instanceof io.kairo.api.agent.SystemPromptContributor spc) {
                builder.systemPromptContributor(spc);
            }
        }

        // Governance: context size guard — warns on large context to prevent overflow.
        // Active in both REPL and one-shot mode (ContextSizeGuard does not suppress in interactive).
        builder.hook(new ContextSizeGuard());

        // Auto-register AutoMemoryHook: after each reasoning step, heuristically extracts
        // durable facts/preferences/decisions into the MemoryStore so future sessions can
        // recall them. No-op when no MemoryStore is wired (REPL-safe). The READ side is
        // already wired via SessionMemoryEnricher in resolveSystemPrompt(); this closes the
        // WRITE side that was previously never registered. Active in both modes.
        builder.hook(new AutoMemoryHook(options.memoryStore()));

        // Auto-register the self-reflection write path: when the same tool fails 3 times in
        // a row, FailurePatternTracker fires and ReflectionPipeline asks the LLM (async, off
        // the agent thread) to distill a one-line lesson, saved as PENDING in the global
        // LearnedLessonStore. Approved lessons are injected into later system prompts via
        // SessionContextEnricher (already wired). Without this the evolution loop never
        // produced any lessons. Approval happens out-of-band (CLI :evolve / EvolutionController).
        Path evolutionDir = Path.of(System.getProperty("user.home"), ".kairo-code");
        LearnedLessonStore evolutionLessons = LearnedLessonStore.fromKairoDir(evolutionDir);
        builder.hook(new FailurePatternTracker(
                strike -> ReflectionPipeline.generateAndSave(strike, config, evolutionLessons)));

        // Auto-register the user-profile write path: before each reasoning step, re-extract
        // preferred languages/frameworks/communication style from the conversation and persist
        // to ~/.kairo-code/profile.json. The READ side (resolveSystemPrompt) injects it into the
        // next session's system prompt. Previously the extractor + context source existed but
        // nothing drove them, so personalization never happened. Active in both modes.
        builder.hook(new io.kairo.code.core.profile.UserProfileUpdateHook(
                io.kairo.code.core.profile.UserProfileStore.fromKairoDir(evolutionDir)));

        // Context compaction is handled by the framework's CompactionTrigger (wired via
        // .compactionThresholds() above). Web sessions additionally register a
        // CompactionEventBridgeHook (@PostCompact) to emit SSE events. No separate
        // PRE_REASONING hook is needed — the framework's 6-stage pipeline handles
        // snip → micro → collapse → auto → partial compaction automatically.

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
            // Governance guards (framework-level, from io.kairo.core.governance):
            builder.hook(new MaxTurnsGuard(20, 30, false));

            int forceThreshold = config.toolBudgetForce();
            if (forceThreshold > 0) {
                int warnThreshold = (int) Math.floor(forceThreshold * 0.6);
                builder.hook(new ToolCallBudgetGuard(warnThreshold, forceThreshold, false));
            } else {
                builder.hook(new ToolCallBudgetGuard());
            }

            int repThreshold = config.repetitiveToolThreshold();
            if (repThreshold > 0) {
                builder.hook(new RepetitiveToolGuard(repThreshold, false));
            } else {
                builder.hook(new RepetitiveToolGuard());
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

            // Loop detection is handled by the framework's LoopDetector (6-layer:
            // hash + frequency + tool-repeat + alternating + no-progress + context-explosion).
            // Previously RichLoopDetectorHook duplicated detection as a POST_REASONING hook;
            // its coaching messages have been ported to the framework's DetectionResult strings.

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

            // Auto-register SessionSummaryHook: saves a compressed session summary as a
            // memory entry at session end, enabling the next session to pick up context.
            if (options.memoryStore() != null) {
                builder.hook(
                        new io.kairo.code.core.hook.SessionSummaryHook(options.memoryStore()));
            }

            // Wire framework IterationCheckpointStore: persists full Msg objects (including
            // metadata) after each tool execution for session resume support.
            // Replaces the custom CheckpointWriterHook which used a lossy serialization format.
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

        // Session-scoped checkpoint is now wired via AgentBuilder.sessionStorage()
        // (set by the caller, e.g. AgentService). The builder auto-creates an
        // IterationCheckpointManager from the provider during build().

        ToolUsageTracker tracker = findToolUsageTracker(hooks);
        TurnMetricsCollector turnMetrics = findTurnMetricsCollector(hooks);
        return new CodeAgentSession(
                agent, executor, registry, activeSkills, mcpRegistry,
                tracker, turnMetrics, sessionMetrics, llmClassifier, costTracker);
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
        return buildModelProvider(apiKey, baseUrl, null);
    }

    /**
     * Build a {@link ModelProvider} using {@link io.kairo.core.model.DefaultModelCatalog} for
     * model-name-to-provider resolution when {@code modelName} is available. Falls back to URL
     * heuristics when the catalog cannot resolve the model.
     */
    public static ModelProvider buildModelProvider(
            String apiKey, String baseUrl, String modelName) {
        var providerRegistry = DefaultProviderRegistry.withBuiltIns();

        if (modelName != null && !modelName.isBlank()) {
            var catalog = io.kairo.core.model.DefaultModelCatalog.withBuiltIns();
            var info = catalog.resolve(modelName);
            if (info.isPresent()) {
                return providerRegistry.create(
                        info.get().providerName(),
                        ProviderSpec.of(apiKey, baseUrl).withModel(modelName));
            }
        }

        if (baseUrl != null && baseUrl.toLowerCase().contains("anthropic")) {
            return providerRegistry.create("anthropic", ProviderSpec.of(apiKey, baseUrl));
        }
        if (baseUrl != null && baseUrl.matches(".*/v\\d+/?$")) {
            String url =
                    baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            return new OpenAIProvider(apiKey, url, "/chat/completions");
        }
        if (baseUrl != null && !baseUrl.isBlank()) {
            return providerRegistry.create(
                    "openai-compatible", ProviderSpec.of(apiKey, baseUrl));
        }
        return providerRegistry.create("openai", ProviderSpec.of(apiKey));
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

        // Personalization: inject the persisted user profile (preferred languages/frameworks/
        // communication style) collected by UserProfileUpdateHook across prior sessions.
        UserProfile persistedProfile =
                io.kairo.code.core.profile.UserProfileStore.fromKairoDir(globalKairoDir).load();
        if (persistedProfile != null) {
            String profileSection = new UserProfileContextSource(persistedProfile).collect();
            if (profileSection != null && !profileSection.isEmpty()) {
                prompt.append("\n\n").append(profileSection);
            }
        }

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
            SwarmCoordinator swarmCoordinator,
            TeamManager teamManager,
            MessageBus teamMessageBus,
            String sessionId,
            AgentType agentType) {

        public SessionOptions {
            if (hooks == null) hooks = List.of();
            if (activeSkills == null) activeSkills = Set.of();
        }

        public static SessionOptions empty() {
            return new SessionOptions(
                    null, null, List.of(), null, Set.of(), null, null, false, null, null, null,
                    false, null, null, null, null, null, null, null, null);
        }

        public SessionOptions withModelProvider(ModelProvider provider) {
            return new SessionOptions(
                    provider, approvalHandler, hooks, skillRegistry, activeSkills,
                    restoreFrom, taskToolDependencies, childSession, textDeltaConsumer,
                    toolUsageTracker, turnMetricsCollector, isRepl, checkpointInitialMessage,
                    memoryStore, tracer, swarmCoordinator, teamManager, teamMessageBus, sessionId,
                    agentType);
        }

        public SessionOptions withApprovalHandler(UserApprovalHandler handler) {
            return new SessionOptions(
                    modelProvider, handler, hooks, skillRegistry, activeSkills,
                    restoreFrom, taskToolDependencies, childSession, textDeltaConsumer,
                    toolUsageTracker, turnMetricsCollector, isRepl, checkpointInitialMessage,
                    memoryStore, tracer, swarmCoordinator, teamManager, teamMessageBus, sessionId,
                    agentType);
        }

        public SessionOptions withHooks(List<Object> hookList) {
            return new SessionOptions(
                    modelProvider, approvalHandler,
                    hookList == null ? List.of() : List.copyOf(hookList),
                    skillRegistry, activeSkills, restoreFrom, taskToolDependencies,
                    childSession, textDeltaConsumer, toolUsageTracker, turnMetricsCollector,
                    isRepl, checkpointInitialMessage, memoryStore, tracer, swarmCoordinator,
                    teamManager, teamMessageBus, sessionId, agentType);
        }

        public SessionOptions withSkills(SkillRegistry registry, Set<String> active) {
            return new SessionOptions(
                    modelProvider, approvalHandler, hooks, registry,
                    active == null ? Set.of() : Set.copyOf(active),
                    restoreFrom, taskToolDependencies, childSession, textDeltaConsumer,
                    toolUsageTracker, turnMetricsCollector, isRepl, checkpointInitialMessage,
                    memoryStore, tracer, swarmCoordinator, teamManager, teamMessageBus, sessionId,
                    agentType);
        }

        public SessionOptions withRestoreFrom(AgentSnapshot snapshot) {
            return new SessionOptions(
                    modelProvider, approvalHandler, hooks, skillRegistry, activeSkills,
                    snapshot, taskToolDependencies, childSession, textDeltaConsumer,
                    toolUsageTracker, turnMetricsCollector, isRepl, checkpointInitialMessage,
                    memoryStore, tracer, swarmCoordinator, teamManager, teamMessageBus, sessionId,
                    agentType);
        }

        public SessionOptions withTaskTool(TaskToolDependencies deps) {
            return new SessionOptions(
                    modelProvider, approvalHandler, hooks, skillRegistry, activeSkills,
                    restoreFrom, deps, childSession, textDeltaConsumer, toolUsageTracker,
                    turnMetricsCollector, isRepl, checkpointInitialMessage, memoryStore, tracer,
                    swarmCoordinator, teamManager, teamMessageBus, sessionId, agentType);
        }

        public SessionOptions withTextDeltaConsumer(
                java.util.function.Consumer<String> consumer) {
            return new SessionOptions(
                    modelProvider, approvalHandler, hooks, skillRegistry, activeSkills,
                    restoreFrom, taskToolDependencies, childSession, consumer,
                    toolUsageTracker, turnMetricsCollector, isRepl, checkpointInitialMessage,
                    memoryStore, tracer, swarmCoordinator, teamManager, teamMessageBus, sessionId,
                    agentType);
        }

        public SessionOptions asChildSession() {
            return new SessionOptions(
                    modelProvider, approvalHandler, hooks, skillRegistry, activeSkills,
                    restoreFrom, taskToolDependencies, true, textDeltaConsumer,
                    toolUsageTracker, turnMetricsCollector, isRepl, checkpointInitialMessage,
                    memoryStore, tracer, swarmCoordinator, teamManager, teamMessageBus, sessionId,
                    agentType);
        }

        public SessionOptions withToolUsageTracker(ToolUsageTracker tracker) {
            return new SessionOptions(
                    modelProvider, approvalHandler, hooks, skillRegistry, activeSkills,
                    restoreFrom, taskToolDependencies, childSession, textDeltaConsumer,
                    tracker, turnMetricsCollector, isRepl, checkpointInitialMessage,
                    memoryStore, tracer, swarmCoordinator, teamManager, teamMessageBus, sessionId,
                    agentType);
        }

        public SessionOptions withTurnMetricsCollector(TurnMetricsCollector collector) {
            return new SessionOptions(
                    modelProvider, approvalHandler, hooks, skillRegistry, activeSkills,
                    restoreFrom, taskToolDependencies, childSession, textDeltaConsumer,
                    toolUsageTracker, collector, isRepl, checkpointInitialMessage,
                    memoryStore, tracer, swarmCoordinator, teamManager, teamMessageBus, sessionId,
                    agentType);
        }

        public SessionOptions asReplSession() {
            return new SessionOptions(
                    modelProvider, approvalHandler, hooks, skillRegistry, activeSkills,
                    restoreFrom, taskToolDependencies, childSession, textDeltaConsumer,
                    toolUsageTracker, turnMetricsCollector, true, checkpointInitialMessage,
                    memoryStore, tracer, swarmCoordinator, teamManager, teamMessageBus, sessionId,
                    agentType);
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
                    memoryStore, tracer, swarmCoordinator, teamManager, teamMessageBus, sessionId,
                    agentType);
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
                    store, tracer, swarmCoordinator, teamManager, teamMessageBus, sessionId,
                    agentType);
        }

        /**
         * Set the tracer for observability.
         */
        public SessionOptions withTracer(Tracer t) {
            return new SessionOptions(
                    modelProvider, approvalHandler, hooks, skillRegistry, activeSkills,
                    restoreFrom, taskToolDependencies, childSession, textDeltaConsumer,
                    toolUsageTracker, turnMetricsCollector, isRepl, checkpointInitialMessage,
                    memoryStore, t, swarmCoordinator, teamManager, teamMessageBus, sessionId,
                    agentType);
        }

        /**
         * Attach a swarm coordinator to the session options.
         *
         * <p><b>Not used by Agent mode.</b> Per ADR-001, Agent-mode sessions never
         * surface the expert team as a model-facing tool — a multi-minute batch
         * inside a tool-result loop would look like a hang. Experts mode uses
         * {@link SwarmCoordinator} out-of-band via {@code TeamSessionPayload} with
         * an {@code ExpertsPresetConfig} attached (M-Experts-Upgrade / #61).
         *
         * <p>This setter is retained on {@code SessionOptions} for use by the live
         * Claude-style Team mode ({@code TeamSessionPayload}, M-Team / #60), which
         * plumbs peer-to-peer messaging and shared task lists via {@code TeamManager}
         * + {@code MessageBus} rather than through the model's tool registry.
         */
        public SessionOptions withSwarmCoordinator(SwarmCoordinator coord) {
            return new SessionOptions(
                    modelProvider, approvalHandler, hooks, skillRegistry, activeSkills,
                    restoreFrom, taskToolDependencies, childSession, textDeltaConsumer,
                    toolUsageTracker, turnMetricsCollector, isRepl, checkpointInitialMessage,
                    memoryStore, tracer, coord, teamManager, teamMessageBus, sessionId,
                    agentType);
        }

        /**
         * Wire the live multi-agent Team-mode primitives — {@link TeamManager} for the team
         * registry + shared {@code SharedTaskList} and {@link MessageBus} for in-process peer
         * messaging. When both are non-null AND this is not a child session, the agent's tool
         * registry gains {@code team_create} / {@code send_message} / {@code team_delete}.
         *
         * <p>Introduced by M-Team (task #60). Child sessions never get these tools — the
         * recursion guard mirrors {@link TaskTool}'s and matches ADR-001 §"Non-goals".
         */
        public SessionOptions withTeamPrimitives(TeamManager mgr, MessageBus bus) {
            return new SessionOptions(
                    modelProvider, approvalHandler, hooks, skillRegistry, activeSkills,
                    restoreFrom, taskToolDependencies, childSession, textDeltaConsumer,
                    toolUsageTracker, turnMetricsCollector, isRepl, checkpointInitialMessage,
                    memoryStore, tracer, swarmCoordinator, mgr, bus, sessionId, agentType);
        }

        public SessionOptions withSessionId(String sid) {
            return new SessionOptions(
                    modelProvider, approvalHandler, hooks, skillRegistry, activeSkills,
                    restoreFrom, taskToolDependencies, childSession, textDeltaConsumer,
                    toolUsageTracker, turnMetricsCollector, isRepl, checkpointInitialMessage,
                    memoryStore, tracer, swarmCoordinator, teamManager, teamMessageBus, sid,
                    agentType);
        }

        public SessionOptions withAgentType(AgentType type) {
            return new SessionOptions(
                    modelProvider, approvalHandler, hooks, skillRegistry, activeSkills,
                    restoreFrom, taskToolDependencies, childSession, textDeltaConsumer,
                    toolUsageTracker, turnMetricsCollector, isRepl, checkpointInitialMessage,
                    memoryStore, tracer, swarmCoordinator, teamManager, teamMessageBus, sessionId,
                    type);
        }
    }

    /** Minimal {@code LOCAL} workspace rooted at the session's working directory. */
    private record DirWorkspace(java.nio.file.Path root)
            implements io.kairo.api.workspace.Workspace {
        @Override
        public String id() {
            return root.toString();
        }

        @Override
        public io.kairo.api.workspace.WorkspaceKind kind() {
            return io.kairo.api.workspace.WorkspaceKind.LOCAL;
        }

        @Override
        public java.util.Map<String, String> metadata() {
            return java.util.Map.of();
        }
    }
}
