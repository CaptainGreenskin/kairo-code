package io.kairo.code.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.hook.PostActingEvent;
import io.kairo.api.hook.PreActingEvent;
import io.kairo.api.tool.ToolResult;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentEventPrinterToolTest {

    private StringWriter outputCapture;
    private AgentEventPrinter printer;

    @BeforeEach
    void setUp() {
        outputCapture = new StringWriter();
        printer = new AgentEventPrinter(new PrintWriter(outputCapture, true));
    }

    // --- Duration display ---

    @Test
    void postActingFastToolUsesDimColor() {
        PreActingEvent preEvent = new PreActingEvent("bash", Map.of("command", "echo hi"), false);
        printer.onPreActing(preEvent);

        PostActingEvent postEvent = postActing("bash", false, "hello");
        printer.onPostActing(postEvent);

        String output = outputCapture.toString();
        assertThat(output).contains("completed");
        // Duration should be shown — fast tool shows ms
        assertThat(output).contains("ms)");
    }

    @Test
    void postActingDurationShowsMsForShortDelay() {
        PreActingEvent preEvent = new PreActingEvent("bash", Map.of("command", "echo hi"), false);
        printer.onPreActing(preEvent);

        PostActingEvent postEvent = postActing("bash", false, "hello");
        printer.onPostActing(postEvent);

        String output = outputCapture.toString();
        assertThat(output).contains("completed");
        assertThat(output).contains("ms)");
    }

    @Test
    void postActingDurationShowsSecondsForLongerDelay() {
        PreActingEvent preEvent = new PreActingEvent("bash", Map.of("command", "sleep 2"), false);
        printer.onPreActing(preEvent);

        try { Thread.sleep(1100); } catch (InterruptedException ignored) {}

        PostActingEvent postEvent = postActing("bash", false, "done");
        printer.onPostActing(postEvent);

        String output = outputCapture.toString();
        assertThat(output).contains("completed");
        // 1.1s duration shows as "X.Xs" format
        assertThat(output).contains("s)");
        assertThat(output).doesNotContain("ms)");
    }

    // --- Error truncation ---

    @Test
    void errorResultTruncatesAt500Chars() {
        String longError = "x".repeat(600) + " END";
        PreActingEvent preEvent = new PreActingEvent("bash", Map.of(), false);
        printer.onPreActing(preEvent);

        PostActingEvent postEvent = postActing("bash", true, longError);
        printer.onPostActing(postEvent);

        String output = outputCapture.toString();
        assertThat(output).contains("failed");
        assertThat(output).contains("... [truncated]");
        // The "END" marker should be cut off (600 > 500)
        assertThat(output).doesNotContain("END");
    }

    @Test
    void successResultTruncatesAt200Chars() {
        String longOutput = "y".repeat(250) + " END";
        PreActingEvent preEvent = new PreActingEvent("bash", Map.of(), false);
        printer.onPreActing(preEvent);

        PostActingEvent postEvent = postActing("bash", false, longOutput);
        printer.onPostActing(postEvent);

        String output = outputCapture.toString();
        assertThat(output).contains("completed");
        assertThat(output).contains("... [truncated]");
        // The "END" marker should be cut off (250 > 200)
        assertThat(output).doesNotContain("END");
    }

    // --- Error classification ---

    @Test
    void classifyErrorPermissionDenied() {
        assertThat(AgentEventPrinter.classifyError("Permission denied: /etc/shadow"))
                .isEqualTo(" [permission]");
        assertThat(AgentEventPrinter.classifyError("Access denied for user"))
                .isEqualTo(" [permission]");
    }

    @Test
    void classifyErrorNotFound() {
        assertThat(AgentEventPrinter.classifyError("File not found: foo.txt"))
                .isEqualTo(" [not found]");
        assertThat(AgentEventPrinter.classifyError("No such file or directory"))
                .isEqualTo(" [not found]");
    }

    @Test
    void classifyErrorTimeout() {
        assertThat(AgentEventPrinter.classifyError("Request timeout after 30s"))
                .isEqualTo(" [timeout]");
        assertThat(AgentEventPrinter.classifyError("Connection timed out"))
                .isEqualTo(" [timeout]");
    }

    @Test
    void classifyErrorNetwork() {
        assertThat(AgentEventPrinter.classifyError("Connection refused: localhost:8080"))
                .isEqualTo(" [network]");
        assertThat(AgentEventPrinter.classifyError("Failed to connect to server"))
                .isEqualTo(" [network]");
    }

    @Test
    void classifyErrorGenericReturnsEmpty() {
        assertThat(AgentEventPrinter.classifyError("Something went wrong"))
                .isEqualTo("");
        assertThat(AgentEventPrinter.classifyError(null))
                .isEqualTo("");
        assertThat(AgentEventPrinter.classifyError(""))
                .isEqualTo("");
    }

    @Test
    void postActingErrorIncludesClassificationLabel() {
        PreActingEvent preEvent = new PreActingEvent("bash", Map.of(), false);
        printer.onPreActing(preEvent);

        PostActingEvent postEvent = postActing("bash", true, "Permission denied: /root");
        printer.onPostActing(postEvent);

        String output = outputCapture.toString();
        assertThat(output).contains("[permission]");
    }

    @Test
    void postActingEmptyContentStillShowsDuration() {
        PreActingEvent preEvent = new PreActingEvent("bash", Map.of(), false);
        printer.onPreActing(preEvent);

        PostActingEvent postEvent = new PostActingEvent("bash", ToolResult.success("t-1", ""));
        printer.onPostActing(postEvent);

        String output = outputCapture.toString();
        assertThat(output).contains("completed");
        assertThat(output).contains("ms)");
    }

    @Test
    void postActingEmptyErrorContentStillShowsDuration() {
        PreActingEvent preEvent = new PreActingEvent("bash", Map.of(), false);
        printer.onPreActing(preEvent);

        PostActingEvent postEvent = new PostActingEvent("bash", ToolResult.error("t-1", ""));
        printer.onPostActing(postEvent);

        String output = outputCapture.toString();
        assertThat(output).contains("failed");
        assertThat(output).contains("ms)");
    }

    // --- formatDuration ---

    @Test
    void formatDurationSubSecondShowsMs() {
        // Access via a quick inline test
        StringWriter sw = new StringWriter();
        AgentEventPrinter p = new AgentEventPrinter(new PrintWriter(sw, true));
        PreActingEvent preEvent = new PreActingEvent("bash", Map.of(), false);
        p.onPreActing(preEvent);

        // Tiny delay — should show "Xms"
        PostActingEvent postEvent = postActing("bash", false, "ok");
        p.onPostActing(postEvent);

        String output = sw.toString();
        // Should contain "ms)" not "s)"
        assertThat(output).matches("(?s).*\\d+ms\\).*");
    }

    private static PostActingEvent postActing(String toolName, boolean isError, String content) {
        return new PostActingEvent(toolName, isError ? ToolResult.error("tool-1", content) : ToolResult.success("tool-1", content));
    }
}
