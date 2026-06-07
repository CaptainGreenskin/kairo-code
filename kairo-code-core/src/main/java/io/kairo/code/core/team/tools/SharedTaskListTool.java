package io.kairo.code.core.team.tools;

import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.ToolSideEffect;
import io.kairo.code.core.team.SharedTask;
import io.kairo.code.core.team.SharedTaskList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;

@Tool(
    name = "task_list",
    description = "List all tasks on the shared task board with their status, owner, and "
            + "dependencies. Use to see what's available, in progress, or completed.",
    category = ToolCategory.AGENT_AND_TASK,
    sideEffect = ToolSideEffect.READ_ONLY
)
public class SharedTaskListTool implements SyncTool {

    @Override
    public Mono<ToolResult> execute(Map<String, Object> input, ToolContext ctx) {
        SharedTaskList taskList = ctx.getBean(SharedTaskList.class).orElse(null);
        if (taskList == null) {
            return Mono.just(ToolResult.error(null, "SharedTaskList not available."));
        }
        List<SharedTask> all = taskList.all();
        if (all.isEmpty()) {
            return Mono.just(ToolResult.success(null, "No tasks on the board."));
        }
        String json = all.stream()
                .map(SharedTaskListTool::taskJson)
                .collect(Collectors.joining(",", "[", "]"));
        return Mono.just(ToolResult.success(null, json));
    }

    static String taskJson(SharedTask t) {
        return "{\"id\":\"" + t.taskId()
                + "\",\"subject\":\"" + esc(t.title())
                + "\",\"status\":\"" + t.status()
                + "\",\"owner\":" + (t.ownerId() != null ? "\"" + esc(t.ownerId()) + "\"" : "null")
                + ",\"blockedBy\":" + t.blockedBy()
                + "}";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
