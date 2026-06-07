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
    name = "task_get",
    description = "Get details of a specific task from the shared task board.",
    category = ToolCategory.AGENT_AND_TASK,
    sideEffect = ToolSideEffect.READ_ONLY
)
public class SharedTaskGetTool implements SyncTool {

    @ToolParam(description = "The task ID to retrieve.", required = true)
    private String task_id;

    @Override
    public Mono<ToolResult> execute(Map<String, Object> input, ToolContext ctx) {
        SharedTaskList taskList = ctx.getBean(SharedTaskList.class).orElse(null);
        if (taskList == null) {
            return Mono.just(ToolResult.error(null, "SharedTaskList not available."));
        }
        String id = str(input, "task_id");
        if (id == null || id.isBlank()) {
            return Mono.just(ToolResult.error(null, "Parameter 'task_id' is required."));
        }
        return taskList.get(id)
                .map(t -> ToolResult.success(null, SharedTaskListTool.taskJson(t)))
                .map(Mono::just)
                .orElse(Mono.just(ToolResult.error(null, "Task not found: " + id)));
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k); return v == null ? null : v.toString();
    }
}
