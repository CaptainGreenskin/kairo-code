package io.kairo.code.core;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentSnapshot;
import io.kairo.api.model.ModelProvider;
import io.kairo.api.skill.SkillDefinition;
import io.kairo.api.skill.SkillRegistry;
import io.kairo.api.tool.PermissionGuard;
import io.kairo.api.tool.UserApprovalHandler;
import io.kairo.core.agent.AgentBuilder;
import io.kairo.core.model.openai.OpenAIProvider;
import io.kairo.code.core.task.TaskTool;
import io.kairo.code.core.task.TaskToolDependencies;
import io.kairo.core.tool.DefaultPermissionGuard;
import io.kairo.core.tool.DefaultToolExecutor;
import io.kairo.core.tool.DefaultToolRegistry;
import io.kairo.tools.exec.BashTool;
import io.kairo.tools.file.EditTool;
import io.kairo.tools.file.GlobTool;
import io.kairo.tools.file.GrepTool;
import io.kairo.tools.file.ReadTool;
import io.kairo.tools.file.WriteTool;
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
        // Register the task tool only when dependencies are wired AND this is not a child session.
        // Child sessions never get TaskTool — recursion is out of scope for M3.
        TaskToolDependencies taskDeps = options.taskToolDependencies();
        if (taskDeps != null && !options.childSession()) {
            registry.registerTool(TaskTool.class);
        }

        PermissionGuard guard = new DefaultPermissionGuard();
        DefaultToolExecutor executor = new DefaultToolExecutor(registry, guard);

        Set<String> activeSkills = options.activeSkills();
        String systemPrompt = resolveSystemPrompt(config, options.skillRegistry(), activeSkills);

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

        List<Object> hooks = options.hooks();
        if (!hooks.isEmpty()) {
            builder.streaming(true);
            for (Object hook : hooks) {
                builder.hook(hook);
            }
        }

        if (options.restoreFrom() != null) {
            builder.restoreFrom(options.restoreFrom());
        }

        Agent agent = builder.build();
        return new CodeAgentSession(agent, executor, registry, activeSkills);
    }

    private static String resolveSystemPrompt(
            CodeAgentConfig config, SkillRegistry skillRegistry, Set<String> activeSkills) {
        StringBuilder prompt = new StringBuilder(loadSystemPrompt());
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
        }
        return prompt.toString();
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

    private static String loadSystemPrompt() {
        try (InputStream is =
                CodeAgentFactory.class
                        .getClassLoader()
                        .getResourceAsStream(SYSTEM_PROMPT_RESOURCE)) {
            if (is == null) {
                log.warn(
                        "System prompt resource '{}' not found on classpath, using fallback",
                        SYSTEM_PROMPT_RESOURCE);
                return "You are Kairo Code, an expert software engineer AI assistant.";
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load system prompt from classpath", e);
            return "You are Kairo Code, an expert software engineer AI assistant.";
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
            boolean childSession) {

        public SessionOptions {
            if (hooks == null) hooks = List.of();
            if (activeSkills == null) activeSkills = Set.of();
        }

        public static SessionOptions empty() {
            return new SessionOptions(null, null, List.of(), null, Set.of(), null, null, false);
        }

        public SessionOptions withModelProvider(ModelProvider provider) {
            return new SessionOptions(
                    provider,
                    approvalHandler,
                    hooks,
                    skillRegistry,
                    activeSkills,
                    restoreFrom,
                    taskToolDependencies,
                    childSession);
        }

        public SessionOptions withApprovalHandler(UserApprovalHandler handler) {
            return new SessionOptions(
                    modelProvider,
                    handler,
                    hooks,
                    skillRegistry,
                    activeSkills,
                    restoreFrom,
                    taskToolDependencies,
                    childSession);
        }

        public SessionOptions withHooks(List<Object> hookList) {
            return new SessionOptions(
                    modelProvider,
                    approvalHandler,
                    hookList == null ? List.of() : List.copyOf(hookList),
                    skillRegistry,
                    activeSkills,
                    restoreFrom,
                    taskToolDependencies,
                    childSession);
        }

        public SessionOptions withSkills(SkillRegistry registry, Set<String> active) {
            return new SessionOptions(
                    modelProvider,
                    approvalHandler,
                    hooks,
                    registry,
                    active == null ? Set.of() : Set.copyOf(active),
                    restoreFrom,
                    taskToolDependencies,
                    childSession);
        }

        public SessionOptions withRestoreFrom(AgentSnapshot snapshot) {
            return new SessionOptions(
                    modelProvider,
                    approvalHandler,
                    hooks,
                    skillRegistry,
                    activeSkills,
                    snapshot,
                    taskToolDependencies,
                    childSession);
        }

        /**
         * Wire TaskTool dependencies. The {@code task} tool is registered only when this is
         * non-null AND {@link #childSession()} is false.
         */
        public SessionOptions withTaskTool(TaskToolDependencies deps) {
            return new SessionOptions(
                    modelProvider,
                    approvalHandler,
                    hooks,
                    skillRegistry,
                    activeSkills,
                    restoreFrom,
                    deps,
                    childSession);
        }

        /**
         * Mark this as a child session — TaskTool will not be registered (no recursion). Child
         * sessions are spawned by the parent's {@code task} tool via {@link
         * io.kairo.code.core.task.ChildSessionSpawner}.
         */
        public SessionOptions asChildSession() {
            return new SessionOptions(
                    modelProvider,
                    approvalHandler,
                    hooks,
                    skillRegistry,
                    activeSkills,
                    restoreFrom,
                    taskToolDependencies,
                    true);
        }
    }
}
