package io.kairo.code.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.hook.PostActingEvent;
import io.kairo.api.tool.ToolResult;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for per-tool call count tracking in {@link AgentEventPrinter}. */
class ToolCallMetricsTest {

    private AgentEventPrinter printer;

    @BeforeEach
    void setUp() {
        printer = new AgentEventPrinter(new PrintWriter(new StringWriter()));
    }

    private void simulateToolCall(String toolName) {
        ToolResult result = new ToolResult("id", "output", false, Map.of());
        printer.onPostActing(new PostActingEvent(toolName, result));
    }

    @Test
    void countsAreCumulatedPerTool() {
        simulateToolCall("read");
        simulateToolCall("read");
        simulateToolCall("read");

        assertThat(printer.getToolCallCounts()).containsEntry("read", 3);
    }

    @Test
    void multipleToolsTrackedSeparately() {
        simulateToolCall("read");
        simulateToolCall("write");
        simulateToolCall("bash");
        simulateToolCall("read");

        Map<String, Integer> counts = printer.getToolCallCounts();
        assertThat(counts).containsEntry("read", 2);
        assertThat(counts).containsEntry("write", 1);
        assertThat(counts).containsEntry("bash", 1);
    }

    @Test
    void emptyWhenNoToolCallsHaveOccurred() {
        assertThat(printer.getToolCallCounts()).isEmpty();
    }

    @Test
    void countsAreReturnedAsUnmodifiableCopy() {
        simulateToolCall("read");
        Map<String, Integer> counts = printer.getToolCallCounts();

        assertThat(counts).containsKey("read");
        // Modifying the returned map should not affect internal state
        try {
            counts.put("injected", 999);
        } catch (UnsupportedOperationException ignored) {
            // Expected if truly unmodifiable
        }
        assertThat(printer.getToolCallCounts()).doesNotContainKey("injected");
    }
}
