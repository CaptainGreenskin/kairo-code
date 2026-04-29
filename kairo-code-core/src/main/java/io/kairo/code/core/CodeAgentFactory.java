package io.kairo.code.core;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentSnapshot;
import io.kairo.api.model.ModelProvider;
import io.kairo.api.skill.SkillDefinition;
import io.kairo.api.skill.SkillRegistry;
import io.kairo.api.tool.PermissionGuard;
import io.kairo.api.tool.ToolDefinition;
import io.kairo.api.tool.UserApprovalHandler;
import io.kairo.code.core.mcp.McpConfig;
import io.kairo.code.core.evolution.LearnedLessonStore;
import io.kairo.code.core.memory.KairoMdLoader;
import io.kairo.code.core.prompt.SessionContextEnricher;
import io.kairo.code.core.stats.ToolUsageTracker;
import io.kairo.code.core.stats.TurnMetricsCollector;
import io.kairo.code.core.hook.AutoCommitOnSuccessHook;
import io.kairo.code.core.hook.ContextCompactionHook;
import io.kairo.code.core.hook.ContextWindowGuardHook;
import io.kairo.code.core.hook.FullTestSuiteHook;
import io.kairo.code.core.hook.MaxTurnsGuardHook;
import io.kairo.code.core.hook.MissingTestHintHook;
import io.kairo.code.core.hook.NoWriteDetectedHook;
import io.kairo.code.core.hook.PlanWithoutActionHook;
import io.kairo.code.core.hook.PostBatchEditVerifyHook;
import io.kairo.code.core.hook.PostEditHintHook;
import io.kairo.code.core.hook.RepetitiveToolHook;
import io.kairo.code.core.hook.SessionResultWriterHook;
import io.kairo.code.core.hook.StaleReadDetectorHook;
import io.kairo.code.core.hook.TestFailureFeedbackHook;
import io.kairo.code.core.hook.ToolBudgetHook;
import io.kairo.code.core.hook.UnfulfilledInstructionHook;
import io.kairo.core.agent.AgentBuilder;
import java.nio.file.Path;
import io.kairo.core.model.openai.OpenAIProvider;
import io.kairo.code.core.task.TaskTool;
import io.kairo.code.core.task.TaskToolDependencies;
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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
                        : new OpenAIProvider(config.apiKey(), config.baseUrl());

        DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.registerTool(BashTool.class);
        registry.registerTool(ReadTool.class);
        registry.registerTool(WriteTool.class);
        registry.registerTool(EditTool.class);
        registry.registerTool(GrepTool.class);
        registry.registerTool(GlobTool.class);
        registry.registerTool(WebFetchTool.class);
        registry.registerTool(GitTool.class);
        registry.registerTool(AskUserTool.class);
        registry.registerTool(TodoReadTool.class);
        registry.registerTool(TodoWriteTool.class);
        registry.registerTool(TreeTool.class);
        // Register the task tool only when dependencies are wired AND this is not a child session.
        // Child sessions never get TaskTool — recursion is out of scope for M3.
        TaskToolDependencies taskDeps = options.taskToolDependencies();
        if (taskDeps != null && !options.childSession()) {
            registry.registerTool(TaskTool.class);
        }

        // Wire MCP tools from config if present.
        McpClientRegistry mcpRegistry = registerMcpTools(registry, config);

        PermissionGuard guard = new DefaultPermissionGuard();
        DefaultToolExecutor executor = new DefaultToolExecutor(registry, guard);

        Set<String> activeSkills = options.activeSkills();
        String systemPrompt =
                resolveSystemPrompt(
                        config,
                        modelProvider,
                        options.skillRegistry(),
                        activeSkills,
                        options.toolUsageTracker());

        AgentBuilder builder =
                AgentBuilder.create()
                        .name("kairo-code")
                        .model(modelProvider)
                        .tools(registry)
                        .toolExecutor(executor)
                        .modelName(config.modelName())
                        .systemPrompt(systemPrompt)
                        .maxIterations(config.maxIterations());

        if (taskDeps != null && !options.childSession()) {
            Map<String, Object> deps = new LinkedHashMap<>();
            deps.put(TaskToolDependencies.class.getName(), taskDeps);
            builder.toolDependencies(deps);
        }

        if (options.approvalHandler() != null) {
            builder.approvalHandler(options.approvalHandler());
        }

        // Always enable streaming so per-token output works even without hooks.
        builder.streaming(true);
        List<Object> hooks = options.hooks();
        for (Object hook : hooks) {
            builder.hook(hook);
        }

        // Auto-register ContextWindowGuardHook: warns on large context to prevent GLM-5.1
        // overflow. Active in both REPL and one-shot mode.
        builder.hook(new ContextWindowGuardHook());

        // Auto-register ContextCompactionHook: when context approaches the limit, injects a
        // compaction request so the model summarizes work and continues. Non-REPL only.
        if (!options.isRepl()) {
            builder.hook(new ContextCompactionHook());
        }

        // Auto-register StaleReadDetectorHook: warns when the agent re-reads the same file
        // multiple times, to improve token efficiency. Active in both REPL and one-shot mode.
        builder.hook(new StaleReadDetectorHook());

        // Auto-register TestFailureFeedbackHook: intercepts bash mvn test failures and injects
        // structured error context so the agent focuses on the right fixes.
        builder.hook(new TestFailureFeedbackHook());

        // Auto-register PlanWithoutActionHook: detects plan-only responses (todo_write without
        // implementation tools) and injects a corrective message. Disabled in REPL mode.
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
            builder.hook(new PostEditHintHook());
            builder.hook(new PostBatchEditVerifyHook());
            builder.hook(new MissingTestHintHook());
            builder.hook(new AutoCommitOnSuccessHook());

            // Auto-register FullTestSuiteHook: detects partial test runs (single test class)
            // and reminds the agent to run the full mvn test suite. Non-REPL only.
            builder.hook(new FullTestSuiteHook());

            // Auto-register UnfulfilledInstructionHook: scans task for "Create ...Test.java"
            // requirements and injects a reminder if the files don't exist yet. Non-REPL only.
            String wd = config.workingDir();
            if (wd != null && !wd.isBlank()) {
                builder.hook(new UnfulfilledInstructionHook(wd));
            }

            // Auto-register SessionResultWriterHook: writes KAIRO_SESSION_RESULT.json on session
            // end so the dispatcher can machine-read the outcome.
            if (wd != null && !wd.isBlank()) {
                builder.hook(new SessionResultWriterHook(Path.of(wd)));
            }
        }

        if (options.textDeltaConsumer() != null) {
            builder.textDeltaConsumer(options.textDeltaConsumer());
        }

        if (options.restoreFrom() != null) {
            builder.restoreFrom(options.restoreFrom());
        }

        Agent agent = builder.build();
        ToolUsageTracker tracker = findToolUsageTracker(hooks);
        TurnMetricsCollector metricsCollector = findTurnMetricsCollector(hooks);
        return new CodeAgentSession(agent, executor, registry, activeSkills, mcpRegistry, tracker, metricsCollector);
    }

    /**
     * Find the ToolUsageTracker instance from the hooks list so it can be exposed
     * via CodeAgentSession for the :stats command.
     */
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
            ToolUsageTracker toolUsageTracker) {
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
            Optional<String> kairoMd =
                    KairoMdLoader.findAndLoad(Path.of(config.workingDir()));
            kairoMd.ifPresent(content ->
                    prompt.append("\n\n## Project Instructions (KAIRO.md)\n").append(content));
        }
        Path globalKairoDir = Path.of(System.getProperty("user.home"), ".kairo-code");
        LearnedLessonStore lessonStore = LearnedLessonStore.fromKairoDir(globalKairoDir);
        return SessionContextEnricher.enrich(prompt.toString(), toolUsageTracker, lessonStore);
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
        if (modelProvider != null) {
            String providerClass = modelProvider.getClass().getName().toLowerCase();
            if (providerClass.contains("anthropic")) {
                return SYSTEM_PROMPT_CLAUDE_RESOURCE;
            }
        }
        if (config != null && config.modelName() != null) {
            String model = config.modelName().toLowerCase();
            if (model.contains("claude")) {
                return SYSTEM_PROMPT_CLAUDE_RESOURCE;
            }
            if (model.contains("glm")) {
                return SYSTEM_PROMPT_GLM_RESOURCE;
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
            boolean isRepl) {

        public SessionOptions {
            if (hooks == null) hooks = List.of();
            if (activeSkills == null) activeSkills = Set.of();
        }

        public static SessionOptions empty() {
            return new SessionOptions(
                    null, null, List.of(), null, Set.of(), null, null, false, null, null, null, false);
        }

        public SessionOptions withModelProvider(ModelProvider provider) {
            return new SessionOptions(
                    provider, approvalHandler, hooks, skillRegistry, activeSkills,
                    restoreFrom, taskToolDependencies, childSession, textDeltaConsumer,
                    toolUsageTracker, turnMetricsCollector, isRepl);
        }

        public SessionOptions withApprovalHandler(UserApprovalHandler handler) {
            return new SessionOptions(
                    modelProvider, handler, hooks, skillRegistry, activeSkills,
                    restoreFrom, taskToolDependencies, childSession, textDeltaConsumer,
                    toolUsageTracker, turnMetricsCollector, isRepl);
        }

        public SessionOptions withHooks(List<Object> hookList) {
            return new SessionOptions(
                    modelProvider, approvalHandler,
                    hookList == null ? List.of() : List.copyOf(hookList),
                    skillRegistry, activeSkills, restoreFrom, taskToolDependencies,
                    childSession, textDeltaConsumer, toolUsageTracker, turnMetricsCollector,
                    isRepl);
        }

        public SessionOptions withSkills(SkillRegistry registry, Set<String> active) {
            return new SessionOptions(
                    modelProvider, approvalHandler, hooks, registry,
                    active == null ? Set.of() : Set.copyOf(active),
                    restoreFrom, taskToolDependencies, childSession, textDeltaConsumer,
                    toolUsageTracker, turnMetricsCollector, isRepl);
        }

        public SessionOptions withRestoreFrom(AgentSnapshot snapshot) {
            return new SessionOptions(
                    modelProvider, approvalHandler, hooks, skillRegistry, activeSkills,
                    snapshot, taskToolDependencies, childSession, textDeltaConsumer,
                    toolUsageTracker, turnMetricsCollector, isRepl);
        }

        /**
         * Wire TaskTool dependencies. The {@code task} tool is registered only when this is
         * non-null AND {@link #childSession()} is false.
         */
        public SessionOptions withTaskTool(TaskToolDependencies deps) {
            return new SessionOptions(
                    modelProvider, approvalHandler, hooks, skillRegistry, activeSkills,
                    restoreFrom, deps, childSession, textDeltaConsumer, toolUsageTracker,
                    turnMetricsCollector, isRepl);
        }

        /**
         * Register a per-token text output consumer fired during streaming model calls.
         * Set to null to disable (default). Child sessions inherit this from the parent.
         */
        public SessionOptions withTextDeltaConsumer(
                java.util.function.Consumer<String> consumer) {
            return new SessionOptions(
                    modelProvider, approvalHandler, hooks, skillRegistry, activeSkills,
                    restoreFrom, taskToolDependencies, childSession, consumer,
                    toolUsageTracker, turnMetricsCollector, isRepl);
        }

        /**
         * Mark this as a child session — TaskTool will not be registered (no recursion). Child
         * sessions are spawned by the parent's {@code task} tool via {@link
         * io.kairo.code.core.task.ChildSessionSpawner}.
         */
        public SessionOptions asChildSession() {
            return new SessionOptions(
                    modelProvider, approvalHandler, hooks, skillRegistry, activeSkills,
                    restoreFrom, taskToolDependencies, true, textDeltaConsumer,
                    toolUsageTracker, turnMetricsCollector, isRepl);
        }

        /**
         * Wire a {@link ToolUsageTracker} so its current snapshot is rendered into the system
         * prompt's "Tool Insights" section at agent build time. The same tracker should also be
         * registered as a hook (via {@link #withHooks(List)}) so it keeps accumulating across
         * session rebuilds (e.g. {@code :clear}).
         */
        public SessionOptions withToolUsageTracker(ToolUsageTracker tracker) {
            return new SessionOptions(
                    modelProvider, approvalHandler, hooks, skillRegistry, activeSkills,
                    restoreFrom, taskToolDependencies, childSession, textDeltaConsumer,
                    tracker, turnMetricsCollector, isRepl);
        }

        /**
         * Wire a {@link TurnMetricsCollector} so it is exposed via CodeAgentSession for the
         * /metrics command. The collector should also be registered as a hook (via
         * {@link #withHooks(List)}) so it keeps accumulating across session rebuilds.
         */
        public SessionOptions withTurnMetricsCollector(TurnMetricsCollector collector) {
            return new SessionOptions(
                    modelProvider, approvalHandler, hooks, skillRegistry, activeSkills,
                    restoreFrom, taskToolDependencies, childSession, textDeltaConsumer,
                    toolUsageTracker, collector, isRepl);
        }

        /**
         * Mark this session as running in REPL/interactive mode. When true,
         * {@link io.kairo.code.core.hook.PlanWithoutActionHook} will not inject corrective
         * messages.
         */
        public SessionOptions asReplSession() {
            return new SessionOptions(
                    modelProvider, approvalHandler, hooks, skillRegistry, activeSkills,
                    restoreFrom, taskToolDependencies, childSession, textDeltaConsumer,
                    toolUsageTracker, turnMetricsCollector, true);
        }
    }
}
