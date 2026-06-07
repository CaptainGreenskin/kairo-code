package io.kairo.code.core.team.tools;

import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolParam;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.ToolSideEffect;
import io.kairo.code.core.team.SharedTask;
import io.kairo.code.core.team.SharedTaskList;
import java.util.Map;
import reactor.core.publisher.Mono;

@Tool(
    name = "task_create",
    description = "Create a new task on the shared task board. Use this to break work into "
            + "trackable pieces that team members can claim and complete.",
    category = ToolCategory.AGENT_AND_TASK,
    sideEffect = ToolSideEffect.READ_ONLY
)
public class SharedTaskCreateTool implements SyncTool {

    @ToolParam(description = "Brief title for the task.", required = true)
    private String subject;

    @ToolParam(description = "Detailed description of what needs to be done.", required = true)
    private String description;

    @Override
    public Mono<ToolResult> execute(Map<String, Object> input, ToolContext ctx) {
        SharedTaskList taskList = ctx.getBean(SharedTaskList.class).orElse(null);
        if (taskList == null) {
            return Mono.just(ToolResult.error(null, "SharedTaskList not available."));
        }
        String subj = str(input, "subject");
        String desc = str(input, "description");
        if (subj == null || subj.isBlank()) {
            return Mono.just(ToolResult.error(null, "Parameter 'subject' is required."));
        }
        if (desc == null || desc.isBlank()) {
            return Mono.just(ToolResult.error(null, "Parameter 'description' is required."));
        }
        SharedTask task = taskList.create(subj, desc);
        return Mono.just(ToolResult.success(null,
                "{\"taskId\":\"" + task.taskId()
                        + "\",\"subject\":\"" + esc(task.title())
                        + "\",\"status\":\"" + task.status() + "\"}"));
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k); return v == null ? null : v.toString();
    }
    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
