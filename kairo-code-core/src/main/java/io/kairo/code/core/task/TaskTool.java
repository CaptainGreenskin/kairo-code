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
import io.kairo.expertteam.role.ExpertProfile;
import io.kairo.expertteam.role.ExpertRoleRegistry;
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
                    "Optional expert role ID to specialize the child agent. When set, the child receives "
                            + "role-specific system instructions, tool restrictions, and skill context from the "
                            + "resolved ExpertProfile. Built-in roles use the 'expert:' prefix: "
                            + "expert:architect, expert:researcher, expert:coder, expert:reviewer, "
                            + "expert:tester, expert:synthesizer. Short names (e.g. 'reviewer') are "
                            + "auto-expanded to 'expert:reviewer'.",
            required = false)
    private String expert_role;

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
        if (descIn == null || descIn.isBlank()) {
            return Mono.just(error(null, "Parameter 'description' is required and must be non-blank."));
        }
        if (promptIn == null || promptIn.isBlank()) {
            return Mono.just(error(null, "Parameter 'prompt' is required and must be non-blank."));
        }

        // Resolve expert role if provided
        ExpertProfile resolvedProfile = null;
        if (roleId != null && !roleId.isBlank()) {
            ExpertRoleRegistry registry =
                    context.getBean(ExpertRoleRegistry.class).orElse(null);
            if (registry == null) {
                return Mono.just(error(null,
                        "expert_role '" + roleId + "' specified but no ExpertRoleRegistry is available. "
                                + "Register an ExpertRoleRegistry in toolDependencies."));
            }
            // Try exact match first, then with 'expert:' prefix
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

        // Build effective prompt: prepend role instructions if profile resolved
        String effectivePrompt = buildEffectivePrompt(promptIn, resolvedProfile);

        Msg childResponse;
        Throwable childFailure = null;
        try {
            CodeAgentSession child = deps.spawner().spawn(taskId, workDir);
            childResponse =
                    child.agent().call(Msg.of(MsgRole.USER, effectivePrompt)).block();
        } catch (Throwable t) {
            childResponse = null;
            childFailure = t;
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
