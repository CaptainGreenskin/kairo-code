package io.kairo.code.core;

import static org.junit.jupiter.api.Assertions.*;

import io.kairo.code.core.task.AgentType;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the AgentType tool filtering logic works correctly at the
 * definition level. Full integration (CodeAgentFactory.createSession with
 * agentType) requires a ModelProvider and is tested in the integration suite.
 */
class ToolFilteringTest {

    @Test
    void coordinatorToolSetExcludesWriteTools() {
        Set<String> allowed = AgentType.COORDINATOR.allowedTools();
        assertNotNull(allowed);

        Set<String> writeTools = Set.of("write", "edit", "patch_apply", "search_replace",
                "batch_write", "mvn", "workflow");
        for (String writeTool : writeTools) {
            assertFalse(allowed.contains(writeTool),
                    "coordinator should NOT contain write tool: " + writeTool);
        }
    }

    @Test
    void coordinatorToolSetIncludesOrchestrationTools() {
        Set<String> allowed = AgentType.COORDINATOR.allowedTools();
        Set<String> required = Set.of("task", "todo_read", "todo_write",
                "team_create", "send_message", "team_delete",
                "enter_plan_mode", "exit_plan_mode", "list_plans", "ask_user");
        for (String tool : required) {
            assertTrue(allowed.contains(tool),
                    "coordinator should contain orchestration tool: " + tool);
        }
    }

    @Test
    void coordinatorToolSetIncludesReadTools() {
        Set<String> allowed = AgentType.COORDINATOR.allowedTools();
        Set<String> readTools = Set.of("read", "bash", "grep", "glob", "tree",
                "batch_read", "diff", "json_query", "git");
        for (String tool : readTools) {
            assertTrue(allowed.contains(tool),
                    "coordinator should contain read tool: " + tool);
        }
    }

    @Test
    void exploreToolSetIsSubsetOfCoordinator() {
        Set<String> explore = AgentType.EXPLORE.allowedTools();
        Set<String> coordinator = AgentType.COORDINATOR.allowedTools();
        assertTrue(coordinator.containsAll(explore),
                "coordinator should include all explore tools");
    }

    @Test
    void sessionOptionsWithAgentType() {
        CodeAgentFactory.SessionOptions opts = CodeAgentFactory.SessionOptions.empty();
        assertNull(opts.agentType(), "default should be null");

        CodeAgentFactory.SessionOptions withType = opts.withAgentType(AgentType.COORDINATOR);
        assertEquals(AgentType.COORDINATOR, withType.agentType());
        assertNull(opts.agentType(), "original should be unchanged (immutable record)");
    }

    @Test
    void asChildSessionPreservesAgentType() {
        CodeAgentFactory.SessionOptions opts = CodeAgentFactory.SessionOptions.empty()
                .withAgentType(AgentType.EXPLORE);
        CodeAgentFactory.SessionOptions child = opts.asChildSession();
        assertTrue(child.childSession());
        assertEquals(AgentType.EXPLORE, child.agentType(),
                "asChildSession should preserve agentType");
    }
}
