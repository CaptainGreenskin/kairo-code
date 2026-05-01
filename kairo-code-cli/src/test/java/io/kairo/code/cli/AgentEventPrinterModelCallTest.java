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
import org.junit.jupiter.api.Assumptions;
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

    // --- Context window usage tests ---

    @Test
    void postReasoningShowsContextFillBar() {
        // Use a small maxContextTokens so we can easily trigger percentage thresholds
        StringWriter sw = new StringWriter();
        AgentEventPrinter p = new AgentEventPrinter(
                new PrintWriter(sw, true), "", false, null, false, 10_000);

        // First call: 3000 input tokens → 30%
        p.onPostReasoning(eventWithUsage(3000, 500, 0));
        String output = sw.toString();
        assertThat(output).contains("context:");
        assertThat(output).contains("30%");
        assertThat(output).contains("[");
        assertThat(output).contains("]");
    }

    @Test
    void postReasoningCumulativeTokensAcrossCalls() {
        StringWriter sw = new StringWriter();
        AgentEventPrinter p = new AgentEventPrinter(
                new PrintWriter(sw, true), "", false, null, false, 10_000);

        p.onPostReasoning(eventWithUsage(2000, 300, 0));
        p.onPostReasoning(eventWithUsage(3000, 400, 0));

        String output = sw.toString();
        // Second call should show cumulative input = 5000 → 50%
        assertThat(output).contains("50%");
    }

    @Test
    void fillBarColorChangesAtThresholds() {
        Assumptions.assumeTrue(
                AgentEventPrinter.supportsColor(),
                "ANSI RED is disabled when TERM is unset/dumb; bar color escapes are empty.");
        // Low usage: < 70% → no RED
        StringWriter swLow = new StringWriter();
        AgentEventPrinter pLow = new AgentEventPrinter(
                new PrintWriter(swLow, true), "", false, null, false, 10_000);
        pLow.onPostReasoning(eventWithUsage(5000, 200, 0)); // 50%
        assertThat(swLow.toString()).doesNotContain("\u001B[31m"); // RED

        // High usage: >= 85% → RED
        StringWriter swHigh = new StringWriter();
        AgentEventPrinter pHigh = new AgentEventPrinter(
                new PrintWriter(swHigh, true), "", false, null, false, 10_000);
        pHigh.onPostReasoning(eventWithUsage(9000, 200, 0)); // 90%
        assertThat(swHigh.toString()).contains("\u001B[31m"); // RED
    }

    @Test
    void buildFillBarProducesCorrectShape() {
        String bar0 = AgentEventPrinter.buildFillBar(0, 20);
        assertThat(bar0).isEqualTo("[\u2591\u2591\u2591\u2591\u2591\u2591\u2591\u2591\u2591\u2591\u2591\u2591\u2591\u2591\u2591\u2591\u2591\u2591\u2591\u2591]");

        String bar50 = AgentEventPrinter.buildFillBar(50, 20);
        assertThat(bar50).isEqualTo("[\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2591\u2591\u2591\u2591\u2591\u2591\u2591\u2591\u2591\u2591]");

        String bar100 = AgentEventPrinter.buildFillBar(100, 20);
        assertThat(bar100).isEqualTo("[\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588]");
    }

    @Test
    void printSessionSummaryContainsExpectedFields() {
        StringWriter sw = new StringWriter();
        AgentEventPrinter p = new AgentEventPrinter(
                new PrintWriter(sw, true), "", false, null, false, 10_000);

        p.onPostReasoning(eventWithUsage(1500, 300, 0));
        p.onPostReasoning(eventWithUsage(2000, 400, 0));
        p.printSessionSummary(125_000); // 2m5s

        String output = sw.toString();
        assertThat(output).contains("turns=2");
        assertThat(output).contains("tokens in=3,500");
        assertThat(output).contains("out=700");
        assertThat(output).contains("elapsed=2m5s");
        assertThat(output).contains("Session complete");
    }

    @Test
    void printSessionSummaryShowsZeroWhenNoCalls() {
        StringWriter sw = new StringWriter();
        AgentEventPrinter p = new AgentEventPrinter(
                new PrintWriter(sw, true), "", false, null, false, 10_000);

        p.printSessionSummary(30_000);

        String output = sw.toString();
        assertThat(output).contains("turns=0");
        assertThat(output).contains("tokens in=0");
        assertThat(output).contains("out=0");
        assertThat(output).contains("elapsed=30s");
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
