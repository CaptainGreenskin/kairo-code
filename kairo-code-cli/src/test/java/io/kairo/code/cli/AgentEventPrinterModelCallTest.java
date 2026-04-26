package io.kairo.code.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.hook.PostReasoningEvent;
import io.kairo.api.model.ModelResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentEventPrinterModelCallTest {

    private StringWriter outputCapture;
    private AgentEventPrinter printer;

    @BeforeEach
    void setUp() {
        outputCapture = new StringWriter();
        printer = new AgentEventPrinter(new PrintWriter(outputCapture, true));
    }

    @Test
    void postReasoningPrintsInputTokens() {
        PostReasoningEvent event = eventWithUsage(1200, 300, 0);

        printer.onPostReasoning(event);

        assertThat(outputCapture.toString()).contains("in=1200");
    }

    @Test
    void postReasoningPrintsOutputTokens() {
        PostReasoningEvent event = eventWithUsage(1200, 300, 0);

        printer.onPostReasoning(event);

        assertThat(outputCapture.toString()).contains("out=300");
    }

    @Test
    void postReasoningPrintsCacheReadTokens() {
        PostReasoningEvent event = eventWithUsage(500, 200, 800);

        printer.onPostReasoning(event);

        assertThat(outputCapture.toString()).contains("cache_read=800");
    }

    @Test
    void postReasoningSkipsUsageLineWhenUsageIsNull() {
        ModelResponse response = new ModelResponse("id-1", List.of(), null, null, "gpt-4o");
        PostReasoningEvent event = new PostReasoningEvent(response, false);

        printer.onPostReasoning(event);

        assertThat(outputCapture.toString()).doesNotContain("[model]");
    }

    private static PostReasoningEvent eventWithUsage(
            int inputTokens, int outputTokens, int cacheReadTokens) {
        ModelResponse.Usage usage =
                new ModelResponse.Usage(inputTokens, outputTokens, cacheReadTokens, 0);
        ModelResponse response =
                new ModelResponse("id-1", List.of(), usage, null, "gpt-4o");
        return new PostReasoningEvent(response, false);
    }
}
