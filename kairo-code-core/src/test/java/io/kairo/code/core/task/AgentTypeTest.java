package io.kairo.code.core.task;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentTypeTest {

    @Test
    void resolveCoordinator() {
        AgentType type = AgentType.resolve("coordinator");
        assertNotNull(type, "coordinator should resolve");
        assertEquals(AgentType.COORDINATOR, type);
        assertEquals("coordinator", type.id());
    }

    @Test
    void coordinatorHasReadToolsButNoWriteTools() {
        Set<String> tools = AgentType.COORDINATOR.allowedTools();
        assertNotNull(tools, "coordinator must have a tool whitelist");
        assertTrue(tools.contains("read"), "coordinator should have read");
        assertTrue(tools.contains("bash"), "coordinator should have bash");
        assertTrue(tools.contains("grep"), "coordinator should have grep");
        assertTrue(tools.contains("glob"), "coordinator should have glob");
        assertTrue(tools.contains("task"), "coordinator should have task");
        assertTrue(tools.contains("team_create"), "coordinator should have team_create");
        assertTrue(tools.contains("send_message"), "coordinator should have send_message");
        assertTrue(tools.contains("enter_plan_mode"), "coordinator should have plan tools");
        assertFalse(tools.contains("write"), "coordinator must NOT have write");
        assertFalse(tools.contains("edit"), "coordinator must NOT have edit");
    }

    @Test
    void availableIdsIncludesCoordinator() {
        List<String> ids = AgentType.availableIds();
        assertTrue(ids.contains("coordinator"));
    }

    @Test
    void resolveNull() {
        assertEquals(AgentType.GENERAL_PURPOSE, AgentType.resolve(null));
        assertEquals(AgentType.GENERAL_PURPOSE, AgentType.resolve(""));
        assertEquals(AgentType.GENERAL_PURPOSE, AgentType.resolve("  "));
    }

    @Test
    void exploreHasReadOnlyTools() {
        Set<String> tools = AgentType.EXPLORE.allowedTools();
        assertNotNull(tools);
        assertTrue(tools.contains("read"));
        assertTrue(tools.contains("bash"));
        assertFalse(tools.contains("write"));
        assertFalse(tools.contains("edit"));
    }

    @Test
    void generalPurposeAllowsAllTools() {
        assertNull(AgentType.GENERAL_PURPOSE.allowedTools(),
                "null means all tools allowed");
    }

    @Test
    void coordinatorSystemPromptIsNonEmpty() {
        assertFalse(AgentType.COORDINATOR.systemPromptPrefix().isEmpty());
    }
}
