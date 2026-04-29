package io.kairo.code.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.hook.PostReasoningEvent;
import io.kairo.api.hook.PreReasoningEvent;
import io.kairo.api.message.Content;
import io.kairo.api.model.ModelConfig;
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

    @Test
    void postReasoningDisplaysThinkingContentCollapsed() {
        Content.ThinkingContent thinking = new Content.ThinkingContent("Let me think about this...", 1000, null);
        Content.TextContent text = new Content.TextContent("Here is the answer.");
        ModelResponse response = new ModelResponse("id-1", List.of(thinking, text), null, null, "gpt-4o");
        PostReasoningEvent event = new PostReasoningEvent(response, false);

        printer.onPostReasoning(event);

        String output = outputCapture.toString();
        assertThat(output).contains("thinking (26 chars)");
    }

    @Test
    void postReasoningShowsFullThinkingWhenVerboseEnabled() {
        StringWriter sw = new StringWriter();
        AgentEventPrinter verbosePrinter = new AgentEventPrinter(
                new PrintWriter(sw, true), "", false, null, true);

        Content.ThinkingContent thinking = new Content.ThinkingContent("Step 1\nStep 2", 1000, null);
        ModelResponse response = new ModelResponse("id-1", List.of(thinking), null, null, "gpt-4o");
        PostReasoningEvent event = new PostReasoningEvent(response, false);

        verbosePrinter.onPostReasoning(event);

        String output = sw.toString();
        assertThat(output).contains("thinking (13 chars)");
        assertThat(output).contains("Step 1");
        assertThat(output).contains("Step 2");
    }

    @Test
    void preReasoningStartsSpinnerAndPostReasoningStopsIt() {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw, true);
        ModelCallSpinner spinner = new ModelCallSpinner(pw, false);
        AgentEventPrinter printerWithSpinner = new AgentEventPrinter(
                pw, "", false, spinner, false);

        ModelConfig config = ModelConfig.builder()
                .model("gpt-4o")
                .maxTokens(4096)
                .temperature(1.0)
                .build();
        PreReasoningEvent preEvent = new PreReasoningEvent(List.of(), config, false);

        printerWithSpinner.onPreReasoning(preEvent);
        assertThat(spinner.isActive()).isTrue();

        PostReasoningEvent postEvent = eventWithUsage(100, 50, 0);
        printerWithSpinner.onPostReasoning(postEvent);
        assertThat(spinner.isActive()).isFalse();

        spinner.shutdown();
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
