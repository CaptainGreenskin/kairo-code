package io.kairo.code.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.hook.PreActingEvent;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProgressPrinterTest {

    private static PreActingEvent event(String toolName) {
        return new PreActingEvent(toolName, Map.of(), false);
    }

    @Test
    void stepCountStartsAtZero() {
        ProgressPrinter printer = new ProgressPrinter(new PrintStream(new ByteArrayOutputStream()));
        assertThat(printer.stepCount()).isZero();
    }

    @Test
    void stepCountIncrementsOnEachCall() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ProgressPrinter printer = new ProgressPrinter(new PrintStream(buf));

        printer.onPreActing(event("bash"));
        printer.onPreActing(event("read_file"));
        printer.onPreActing(event("write_file"));

        assertThat(printer.stepCount()).isEqualTo(3);
    }

    @Test
    void outputContainsStepNumberAndToolName() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ProgressPrinter printer = new ProgressPrinter(new PrintStream(buf));

        printer.onPreActing(event("bash"));

        String output = buf.toString();
        assertThat(output).contains("[STEP 1]");
        assertThat(output).contains("工具调用：bash");
    }

    @Test
    void stepNumbersAreSequential() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ProgressPrinter printer = new ProgressPrinter(new PrintStream(buf));

        printer.onPreActing(event("tool_a"));
        printer.onPreActing(event("tool_b"));

        String output = buf.toString();
        assertThat(output).contains("[STEP 1]");
        assertThat(output).contains("[STEP 2]");
        assertThat(output).contains("tool_a");
        assertThat(output).contains("tool_b");
    }

    @Test
    void noOutputWhenNotUsed() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        new ProgressPrinter(new PrintStream(buf));

        assertThat(buf.toString()).isEmpty();
    }
}
