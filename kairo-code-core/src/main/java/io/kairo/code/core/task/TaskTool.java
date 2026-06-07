/*
 * Copyright 2025-2026 the Kairo authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.kairo.code.core.task;

import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.ToolParam;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import io.kairo.api.workspace.Workspace;
import io.kairo.api.workspace.WorkspaceRequest;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.code.core.workspace.DiffStats;
import io.kairo.code.core.workspace.WorktreeException;
import io.kairo.code.core.workspace.WorktreeLifecycle;
import io.kairo.code.core.workspace.WorktreeWorkspaceProvider;
import io.kairo.multiagent.subagent.ExpertProfile;
import io.kairo.multiagent.subagent.ExpertRoleRegistry;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Spawn a child agent session to handle a sub-task in isolation, then return its final response to
 * the parent.
 *
 * <p>For write tasks the child runs in a fresh git worktree under {@code
 * ~/.kairo-code/worktrees/<repo-fp>/<task-id>/}. After the child finishes, the user is prompted
 * whether to {@link WorktreeMergeChoice#MERGE merge} the changes (squash-merge, staged not
 * committed), {@link WorktreeMergeChoice#DISCARD discard} them, or {@link WorktreeMergeChoice#KEEP
 * keep} the worktree on disk for later inspection.
 *
 * <p>For read-only tasks (or when the parent is not a git repo) the child runs against the parent
 * working tree directly — no isolation, no merge prompt.
 *
 * <p>Dependencies must be wired by the caller via {@code AgentBuilder.toolDependencies(Map)} —
 * specifically a {@link TaskToolDependencies} bean. Without it the tool returns an error result
 * (rather than throwing) so the agent surfaces a clear failure to the user.
 *
 * @since 0.1.1 (kairo-code M3)
 */
@Tool(
        name = "task",
        description =
                "Spawn a sub-agent to handle a focused sub-task. Use for multi-step work that benefits "
                        + "from a separate context window — e.g. \"refactor the X service then run tests\". "
                        + "Write sub-tasks run in an isolated git worktree; the user is prompted to "
                        + "merge/discard/keep the changes when the sub-agent finishes.",
        category = ToolCategory.GENERAL,
        sideEffect = ToolSideEffect.WRITE,
        timeoutSeconds = 14400,
        usageGuidance =
                "Prefer one task call per coherent sub-goal. Keep prompts concrete: state the inputs, the "
                        + "expected output, and any constraints. Avoid spawning a task for trivial work that "
                        + "would fit in 1-2 tool calls.")
public class TaskTool implements SyncTool {

    private static final Logger LOG = LoggerFactory.getLogger(TaskTool.class);

    @ToolParam(description = "Short, descriptive name for the sub-task (one line).", required = true)
    private String description;

    @ToolParam(
            description =
                    "Full prompt for the child agent. Include enough context that it can act without "
                            + "re-asking the parent for clarification.",
            required = true)
    private String prompt;

    @ToolParam(
            description =
                    "Isolation mode. 'worktree' (default) creates a fresh git worktree so the child can "
                            + "write without polluting the parent. 'none' shares the parent's working tree — "
                            + "use only for read-only sub-tasks.",
            required = false)
    private String isolation;

    @ToolParam(
            description =
                    "The type of specialized agent to spawn. Available types: "
                            + "'general-purpose' (default, full capabilities), "
                            + "'explore' (read-only search/analysis), "
                            + "'plan' (architecture/design planning, read-only), "
                            + "'coder' (implementation with full write access), "
                            + "'reviewer' (code review, read-only), "
                            + "'coordinator' (orchestration-only — can read code but delegates all "
                            + "modifications to workers via task tool). "
                            + "Choose based on what tools the agent needs.",
            required = false)
    private String subagent_type;

    @ToolParam(
            description =
                    "Agents run in the background by default — the tool returns immediately with a "
                            + "task ID and you are notified on completion. Set to false only when you "
                            + "need the result before your next step (blocks until the agent finishes).",
            required = false)
    private Boolean run_in_background;

    @ToolParam(
            description =
                    "Model override for this agent. Use a specific model name to override the default. "
                            + "Omit to inherit the parent's model.",
            required = false)
    private String model;

    @ToolParam(
            description =
                    "When true, the child agent inherits the parent's conversation context. "
                            + "Use for tasks that need to understand what has been discussed so far.",
            required = false)
    private Boolean fork;

    @ToolParam(
            description =
                    "Name for the spawned agent. Makes it addressable via SendMessage while running. "
                            + "Use for agents that need to communicate with other agents.",
            required = false)
    private String name;

    @ToolParam(
            description =
                    "Optional expert role ID to specialize the child agent. When set, the child receives "
                            + "role-specific system instructions, tool restrictions, and skill context from the "
                            + "resolved ExpertProfile. Built-in roles use the 'expert:' prefix: "
                            + "expert:architect, expert:researcher, expert:coder, expert:reviewer, "
                            + "expert:tester, expert:synthesizer. Short names (e.g. 'reviewer') are "
                            + "auto-expanded to 'expert:reviewer'.",
            required = false)
    private String expert_role;

    @ToolParam(
            description =
                    "When true, the child's output is verified by a model before returning to the parent. "
                            + "The verifier judges whether the output correctly fulfills the original prompt. "
                            + "If verification returns REVISE, the child is retried with feedback (up to 2 times).",
            required = false)
    private Boolean verify;

    @ToolParam(
            description =
                    "Model to use for verification (only effective when verify=true). "
                            + "Defaults to the parent session's model. Set to a stronger model for higher "
                            + "quality verification.",
            required = false)
    private String verify_model;

    @Override
    public Mono<ToolResult> execute(Map<String, Object> input, ToolContext context) {
        TaskToolDependencies deps =
                context.getBean(TaskToolDependencies.class).orElse(null);
        if (deps == null) {
            return Mono.just(error(
                    null,
                    "TaskToolDependencies not registered in agent's toolDependencies. "
                            + "Wire it via AgentBuilder.toolDependencies(...)."));
        }

        String descIn = stringInput(input, "description");
        String promptIn = stringInput(input, "prompt");
        String isoIn = stringInput(input, "isolation");
        String roleId = stringInput(input, "expert_role");
        String agentTypeId = stringInput(input, "subagent_type");
        Boolean background = boolInput(input, "run_in_background");
        String modelOverride = stringInput(input, "model");
        Boolean forkContext = boolInput(input, "fork");
        String agentName = stringInput(input, "name");
        if (descIn == null || descIn.isBlank()) {
            return Mono.just(error(null, "Parameter 'description' is required and must be non-blank."));
        }
        if (promptIn == null || promptIn.isBlank()) {
            return Mono.just(error(null, "Parameter 'prompt' is required and must be non-blank."));
        }

        // Resolve agent type (new subagent_type parameter takes priority over expert_role)
        AgentType agentType = AgentType.GENERAL_PURPOSE;
        if (agentTypeId != null && !agentTypeId.isBlank()) {
            agentType = AgentType.resolve(agentTypeId);
            if (agentType == null) {
                return Mono.just(error(null,
                        "Unknown subagent_type '" + agentTypeId + "'. Available types: "
                                + AgentType.availableIds() + "."));
            }
        }

        // Resolve expert role if provided (legacy path, complementary to subagent_type)
        ExpertProfile resolvedProfile = null;
        if (roleId != null && !roleId.isBlank()) {
            ExpertRoleRegistry registry =
                    context.getBean(ExpertRoleRegistry.class).orElse(null);
            if (registry == null) {
                return Mono.just(error(null,
                        "expert_role '" + roleId + "' specified but no ExpertRoleRegistry is available. "
                                + "Register an ExpertRoleRegistry in toolDependencies."));
            }
            resolvedProfile = registry.resolve(roleId).orElse(null);
            if (resolvedProfile == null && !roleId.contains(":")) {
                resolvedProfile = registry.resolve("expert:" + roleId).orElse(null);
            }
            if (resolvedProfile == null) {
                return Mono.just(error(null,
                        "Unknown expert_role '" + roleId + "'. Available roles: "
                                + registry.registeredRoleIds() + "."));
            }
        }

        boolean writable = !"none".equalsIgnoreCase(isoIn);
        String taskId = newTaskId();
        String toolUseId = (String) input.getOrDefault("__tool_use_id", null);

        WorktreeWorkspaceProvider provider = deps.workspaceProvider();
        WorkspaceRequest req =
                writable ? WorkspaceRequest.writable(taskId) : WorkspaceRequest.readOnly(taskId);

        Workspace ws;
        try {
            ws = provider.acquire(req);
        } catch (WorktreeException e) {
            LOG.warn("Worktree acquire failed for task {}: {}", taskId, e.getMessage());
            return Mono.just(error(toolUseId, "Failed to set up workspace for sub-task: " + e.getMessage()));
        }

        boolean isolated = "worktree".equals(ws.metadata().get("kairo.workspace.isolation"));
        Path workDir = ws.root();

        // Build effective prompt: prepend agent type system prompt + role instructions
        String agentPrefix = agentType.systemPromptPrefix();
        String basePrompt = agentPrefix.isEmpty() ? promptIn : agentPrefix + "\n\n" + promptIn;

        // Fork: prepend parent conversation context if available
        if (Boolean.TRUE.equals(forkContext) && deps.parentContextProvider() != null) {
            String parentContext = deps.parentContextProvider().get();
            if (parentContext != null && !parentContext.isBlank()) {
                basePrompt = "<parent_context>\n" + parentContext + "\n</parent_context>\n\n"
                        + "The above is the conversation context from the parent agent. "
                        + "Use it to understand what has been discussed. Now handle this task:\n\n"
                        + basePrompt;
            }
        }

        String effectivePrompt = buildEffectivePrompt(basePrompt, resolvedProfile);

        // Async execution is the default — prevents parent stall.
        // Only run synchronously when explicitly requested via run_in_background=false.
        boolean runAsync = !Boolean.FALSE.equals(background);
        if (runAsync) {
            String toolUseIdBg = (String) input.getOrDefault("__tool_use_id", null);
            final AgentType finalAgentType = agentType;
            final String finalModelOverride = modelOverride;
            final String finalDescIn = descIn;
            try {
                final boolean hasExplicitName = agentName != null && !agentName.isBlank();
                // Always register async tasks so PendingBackgroundTaskStrategy can track them.
                // Use the explicit name if provided, otherwise fall back to the taskId.
                final String finalAgentName = hasExplicitName ? agentName : taskId;
                if (deps.subagentRegistry() != null) {
                    deps.subagentRegistry().register(finalAgentName, taskId);
                }
                CodeAgentSession child = deps.subagentRegistry() != null
                        ? deps.spawner().spawn(taskId, workDir, finalAgentType, finalModelOverride,
                                finalAgentName, deps.subagentRegistry())
                        : deps.spawner().spawn(taskId, workDir, finalAgentType, finalModelOverride);
                final Boolean finalVerify = boolInput(input, "verify");
                final String finalVerifyModel = stringInput(input, "verify_model");
                Thread.startVirtualThread(() -> {
                    long startMs = System.currentTimeMillis();
                    String resultText = null;
                    Throwable failure = null;
                    try {
                        Msg response = child.agent().call(Msg.of(MsgRole.USER, effectivePrompt)).block();
                        if (Boolean.TRUE.equals(finalVerify) && response != null
                                && deps.parentConfig() != null) {
                            response = runVerification(
                                    response, effectivePrompt, finalVerifyModel,
                                    deps.parentConfig(), child, taskId);
                        }
                        if (response != null) {
                            resultText = response.text();
                        }
                    } catch (Throwable t) {
                        failure = t;
                        LOG.warn("Background task {} failed: {}", taskId, t.toString());
                    } finally {
                        if (isolated) {
                            if (failure == null) {
                                try {
                                    io.kairo.code.core.workspace.DiffStats bgDiff =
                                            provider.lifecycle().diff(workDir);
                                    if (!bgDiff.isEmpty()) {
                                        WorktreeMergeChoice bgChoice = deps.mergePrompter()
                                                .prompt(taskId, finalDescIn, bgDiff, workDir)
                                                .blockOptional()
                                                .orElse(WorktreeMergeChoice.DISCARD);
                                        applyChoice(provider.lifecycle(), bgChoice, taskId,
                                                provider.parentRoot(), workDir);
                                        LOG.info("Background task {} worktree {}: {}",
                                                taskId, bgChoice, bgDiff);
                                    }
                                } catch (Exception e) {
                                    LOG.warn("Background task {} merge failed: {}",
                                            taskId, e.getMessage());
                                }
                            }
                            try { provider.release(ws.id()); } catch (Exception ignored) {}
                        }
                        if (finalAgentName != null && !finalAgentName.isBlank()
                                && deps.subagentRegistry() != null) {
                            if (failure != null) {
                                deps.subagentRegistry().markFailed(finalAgentName);
                            } else {
                                deps.subagentRegistry().markCompleted(finalAgentName);
                            }
                        }
                    }
                    long durationMs = System.currentTimeMillis() - startMs;
                    if (deps.asyncCompletionCallback() != null) {
                        deps.asyncCompletionCallback().accept(taskId, finalDescIn,
                                resultText, failure, durationMs);
                    }
                    LOG.info("Background task {} completed in {}ms (success={})",
                            taskId, durationMs, failure == null);
                });
            } catch (Throwable t) {
                LOG.warn("Failed to spawn background task {}: {}", taskId, t.getMessage());
                provider.release(ws.id());
                return Mono.just(error(toolUseIdBg, "Failed to launch background task: " + t.getMessage()));
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("task.id", taskId);
            meta.put("task.status", "async_launched");
            meta.put("task.description", descIn);
            meta.put("task.subagent_type", agentType.id());
            if (agentName != null && !agentName.isBlank()) {
                meta.put("task.name", agentName);
            }
            String nameAttr = (agentName != null && !agentName.isBlank())
                    ? " name=\"" + agentName + "\"" : "";
            return Mono.just(ToolResult.success(
                    "<task_launched task_id=\"" + taskId + "\" description=\"" + descIn
                            + "\" subagent_type=\"" + agentType.id() + "\"" + nameAttr
                            + " status=\"running\">\n"
                            + "Sub-agent launched in background."
                            + (agentName != null && !agentName.isBlank()
                                ? " Addressable as '" + agentName + "' via SendMessage."
                                : " You will be notified when it completes.")
                            + "\n</task_launched>",
                    toolUseIdBg, meta));
        }

        // Synchronous execution (original path)
        Msg childResponse;
        Throwable childFailure = null;
        CodeAgentSession child = null;
        Boolean verifyInput = boolInput(input, "verify");
        String verifyModelInput = stringInput(input, "verify_model");
        try {
            child = deps.spawner().spawn(taskId, workDir, agentType, modelOverride);
            childResponse =
                    child.agent().call(Msg.of(MsgRole.USER, effectivePrompt)).block();

            // Verification loop: if verify=true and parentConfig is available
            if (Boolean.TRUE.equals(verifyInput) && childResponse != null
                    && deps.parentConfig() != null) {
                childResponse = runVerification(
                        childResponse, effectivePrompt, verifyModelInput,
                        deps.parentConfig(), child, taskId);
            }
        } catch (Throwable t) {
            childResponse = null;
            childFailure = t;
            if (child != null) {
                try { child.agent().interrupt(); } catch (Exception ignored) {}
            }
            LOG.warn("Child agent for task {} threw: {}", taskId, t.toString());
        }

        WorktreeMergeChoice choice = WorktreeMergeChoice.DISCARD;
        DiffStats diff = DiffStats.EMPTY;
        Path keptAt = null;
        if (isolated) {
            try {
                diff = provider.lifecycle().diff(workDir);
            } catch (WorktreeException e) {
                LOG.warn("Diff failed for task {}: {}", taskId, e.getMessage());
            }
            if (diff.isEmpty() || childFailure != null) {
                choice = WorktreeMergeChoice.DISCARD;
            } else {
                try {
                    choice = deps.mergePrompter()
                            .prompt(taskId, descIn, diff, workDir)
                            .blockOptional()
                            .orElse(WorktreeMergeChoice.DISCARD);
                } catch (Throwable t) {
                    LOG.warn(
                            "Merge prompter failed for task {}; defaulting to DISCARD: {}",
                            taskId,
                            t.toString());
                    choice = WorktreeMergeChoice.DISCARD;
                }
            }
            keptAt = applyChoice(provider.lifecycle(), choice, taskId, provider.parentRoot(), workDir);
        }
        provider.release(ws.id());

        if (childFailure != null) {
            return Mono.just(error(
                    toolUseId,
                    "Child agent for task '" + descIn + "' failed: " + childFailure.getMessage()));
        }
        return Mono.just(ok(toolUseId, taskId, descIn, isolated, choice, diff, keptAt,
                childResponse, resolvedProfile));
    }

    /* ------------------------------------------------------------------ helpers */

    private static final int MAX_VERIFY_ATTEMPTS = 2;

    /**
     * Run the verification loop: verify child output with a stronger model, retry on REVISE.
     */
    private static Msg runVerification(
            Msg childResponse, String originalPrompt, String verifyModelOverride,
            io.kairo.code.core.CodeAgentConfig parentConfig, CodeAgentSession child, String taskId) {

        String verifyModelName = (verifyModelOverride != null && !verifyModelOverride.isBlank())
                ? verifyModelOverride : parentConfig.modelName();
        io.kairo.api.model.ModelProvider verifyProvider =
                io.kairo.code.core.CodeAgentFactory.buildModelProvider(
                        parentConfig.apiKey(), parentConfig.baseUrl());

        Msg current = childResponse;
        for (int attempt = 0; attempt < MAX_VERIFY_ATTEMPTS; attempt++) {
            SubagentVerifier.VerificationResult vr = SubagentVerifier.verify(
                    originalPrompt, current.text(), verifyProvider, verifyModelName);

            if (vr.verdict() == SubagentVerifier.Verdict.PASS) {
                LOG.debug("Task {} verification PASS on attempt {}", taskId, attempt);
                return current;
            }
            if (vr.verdict() == SubagentVerifier.Verdict.FAIL) {
                LOG.info("Task {} verification FAIL: {}", taskId, vr.feedback());
                return Msg.of(MsgRole.ASSISTANT,
                        current.text() + "\n\n[VERIFICATION FAILED: " + vr.feedback() + "]");
            }
            // REVISE: retry the child with feedback
            LOG.info("Task {} verification REVISE (attempt {}): {}", taskId, attempt + 1, vr.feedback());
            try {
                Msg retry = child.agent().call(Msg.of(MsgRole.USER,
                        "Your previous output was reviewed and needs revision:\n\n"
                                + vr.feedback() + "\n\nPlease revise your output.")).block();
                if (retry != null) {
                    current = retry;
                }
            } catch (Exception e) {
                LOG.warn("Task {} verification retry failed: {}", taskId, e.getMessage());
                break;
            }
        }
        return current;
    }

    /**
     * Build the effective prompt sent to the child agent. If an expert profile is resolved, its
     * role instructions are prepended as a system-level preamble, and tool restrictions / mounted
     * skills are appended as context.
     */
    static String buildEffectivePrompt(String basePrompt, @Nullable ExpertProfile profile) {
        if (profile == null) {
            return basePrompt;
        }
        StringBuilder sb = new StringBuilder();
        String instructions = profile.roleDefinition().instructions();
        if (instructions != null && !instructions.isBlank()) {
            sb.append("<role_instructions>\n");
            sb.append(instructions).append("\n");
            sb.append("</role_instructions>\n\n");
        }
        List<String> allowedTools = profile.roleDefinition().allowedTools();
        if (allowedTools != null && !allowedTools.isEmpty()) {
            sb.append("<tool_restrictions>\n");
            sb.append("You may ONLY use the following tools: ");
            sb.append(String.join(", ", allowedTools)).append("\n");
            sb.append("</tool_restrictions>\n\n");
        }
        List<String> skills = profile.mountedSkills();
        if (skills != null && !skills.isEmpty()) {
            sb.append("<mounted_skills>\n");
            sb.append("Available skills: ").append(String.join(", ", skills)).append("\n");
            sb.append("</mounted_skills>\n\n");
        }
        sb.append(basePrompt);
        return sb.toString();
    }

    private static Path applyChoice(
            WorktreeLifecycle lifecycle,
            WorktreeMergeChoice choice,
            String taskId,
            Path parentRoot,
            Path worktreePath) {
        switch (choice) {
            case MERGE -> {
                try {
                    lifecycle.merge(taskId, parentRoot);
                } catch (WorktreeException e) {
                    LOG.warn(
                            "Merge failed for task {}; falling back to KEEP so changes are recoverable: {}",
                            taskId,
                            e.getMessage());
                    return lifecycle.keep(taskId, parentRoot);
                }
                lifecycle.discard(taskId, parentRoot);
                return null;
            }
            case DISCARD -> {
                lifecycle.discard(taskId, parentRoot);
                return null;
            }
            case KEEP -> {
                return lifecycle.keep(taskId, parentRoot);
            }
            default -> throw new IllegalStateException("Unhandled choice: " + choice);
        }
    }

    private static String stringInput(Map<String, Object> input, String key) {
        Object v = input.get(key);
        return v == null ? null : v.toString();
    }

    private static Boolean boolInput(Map<String, Object> input, String key) {
        Object v = input.get(key);
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }

    private static String newTaskId() {
        return "t-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static ToolResult error(String toolUseId, String message) {
        return ToolResult.error(toolUseId, message);
    }

    private static ToolResult ok(
            String toolUseId,
            String taskId,
            String description,
            boolean isolated,
            WorktreeMergeChoice choice,
            DiffStats diff,
            Path keptAt,
            Msg childResponse,
            @Nullable ExpertProfile profile) {
        String childText = childResponse == null ? "" : childResponse.text();
        StringBuilder sb = new StringBuilder();
        sb.append("<task_result")
                .append(attr("task_id", taskId))
                .append(attr("description", description))
                .append(attr("isolation", isolated ? "worktree" : "none"))
                .append(attr("outcome", choice.name().toLowerCase()))
                .append(attr("files_changed", String.valueOf(diff.filesChanged())))
                .append(attr("insertions", String.valueOf(diff.insertions())))
                .append(attr("deletions", String.valueOf(diff.deletions())));
        if (profile != null) {
            sb.append(attr("expert_role", profile.roleId()));
        }
        if (keptAt != null) {
            sb.append(attr("kept_at", keptAt.toString()));
        }
        sb.append(">\n");
        sb.append(childText);
        if (!childText.endsWith("\n")) sb.append("\n");
        sb.append("</task_result>");

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("task.id", taskId);
        meta.put("task.isolation", isolated ? "worktree" : "none");
        meta.put("task.outcome", choice.name().toLowerCase());
        meta.put("task.files_changed", diff.filesChanged());
        meta.put("task.insertions", diff.insertions());
        meta.put("task.deletions", diff.deletions());
        if (profile != null) {
            meta.put("task.expert_role", profile.roleId());
            List<String> tools = profile.roleDefinition().allowedTools();
            if (tools != null && !tools.isEmpty()) {
                meta.put("task.allowed_tools", tools);
            }
            List<String> skills = profile.mountedSkills();
            if (skills != null && !skills.isEmpty()) {
                meta.put("task.mounted_skills", skills);
            }
        }
        if (keptAt != null) meta.put("task.kept_at", keptAt.toString());
        return ToolResult.success(toolUseId, sb.toString(), Map.copyOf(meta));
    }

    private static String attr(String key, String value) {
        return " " + key + "=\"" + escape(value) + "\"";
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Helpful when a test wants to hand-inspect attribute formatting. */
    static Map<String, Object> defaultMetaForTesting() {
        return new HashMap<>();
    }
}
