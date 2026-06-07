package io.kairo.code.core.team.tools;

import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolParam;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.ToolSideEffect;
import io.kairo.code.core.team.SharedTaskList;
import java.util.Map;
import reactor.core.publisher.Mono;

@Tool(
    name = "task_update",
    description = "Update a task's status on the shared task board. Use 'in_progress' to claim "
            + "a task, 'completed' when done, 'failed' if blocked.",
    category = ToolCategory.AGENT_AND_TASK,
    sideEffect = ToolSideEffect.READ_ONLY
)
public class SharedTaskUpdateTool implements SyncTool {

    @ToolParam(description = "The task ID to update.", required = true)
    private String task_id;

    @ToolParam(description = "New status: 'in_progress', 'completed', or 'failed'.", required = true)
    private String status;

    @ToolParam(description = "Agent name claiming the task (required for in_progress).", required = false)
    private String owner;

    @Override
    public Mono<ToolResult> execute(Map<String, Object> input, ToolContext ctx) {
        SharedTaskList taskList = ctx.getBean(SharedTaskList.class).orElse(null);
        if (taskList == null) {
            return Mono.just(ToolResult.error(null, "SharedTaskList not available."));
        }
        String id = str(input, "task_id");
        String st = str(input, "status");
        String own = str(input, "owner");
        if (id == null || id.isBlank()) {
            return Mono.just(ToolResult.error(null, "Parameter 'task_id' is required."));
        }
        if (st == null || st.isBlank()) {
            return Mono.just(ToolResult.error(null, "Parameter 'status' is required."));
        }
        String agentId = own != null && !own.isBlank() ? own : ctx.agentId();
        boolean ok;
        switch (st.toLowerCase()) {
            case "in_progress" -> {
                var claimed = taskList.claim(id, agentId);
                ok = claimed.isPresent();
            }
            case "completed" -> ok = taskList.complete(id, agentId);
            case "failed" -> ok = taskList.fail(id, agentId);
            default -> {
                return Mono.just(ToolResult.error(null,
                        "Invalid status '" + st + "'. Use: in_progress, completed, failed."));
            }
        }
        if (!ok) {
            return Mono.just(ToolResult.error(null,
                    "Failed to update task '" + id + "' to '" + st + "'. "
                            + "Check task exists and ownership."));
        }
        return Mono.just(ToolResult.success(null,
                "{\"taskId\":\"" + id + "\",\"status\":\"" + st + "\",\"owner\":\"" + esc(agentId) + "\"}"));
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k); return v == null ? null : v.toString();
    }
    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
