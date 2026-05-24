package io.kairo.code.cli.commands;

import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.code.core.LlmClassifierConfig;
import io.kairo.core.guardrail.policy.BashCommandClassifier.Category;
import io.kairo.core.guardrail.policy.LlmBashClassifier;
import java.io.PrintWriter;
import java.util.Map;

/**
 * Inspect the LLM bash-classifier surface: configuration knobs + runtime counters.
 *
 * <p>{@code :stats} merges classifier counters into the tool-usage report so users see them in the
 * standard "what just happened" view. This command exists for the inverse case &mdash; "is the LLM
 * fallback actually wired, and with what knobs?" &mdash; which {@code :stats} can't answer when no
 * tool calls have fired yet. When the fallback is disabled it also prints the enable recipe, so
 * users don't have to dig through {@code --help}.
 *
 * <p>Usage: {@code :classifier}
 */
public class ClassifierCommand implements SlashCommand {

    private static final String SEPARATOR = "─".repeat(42);

    @Override
    public String name() {
        return "classifier";
    }

    @Override
    public String description() {
        return "Show LLM bash-classifier config and runtime counters";
    }

    @Override
    public void execute(String args, ReplContext context) {
        PrintWriter writer = context.writer();
        CodeAgentSession session = context.session();
        CodeAgentConfig config = context.config();

        writer.println();
        writer.println("LLM Bash Classifier");
        writer.println(SEPARATOR);

        renderConfig(writer, config);

        LlmBashClassifier classifier = session != null ? session.llmBashClassifier() : null;
        if (classifier == null) {
            // The agent's heuristic-only path is still active &mdash; UNKNOWN commands fall through
            // to ALLOW. Tell the user how to flip this on so they don't have to read --help.
            writer.println();
            writer.println("Status            : disabled (heuristic-only)");
            writer.println();
            writer.println("To enable the LLM fallback, pick one:");
            writer.println("  - pass --llm-classifier on the next launch");
            writer.println("  - set KAIRO_CODE_LLM_CLASSIFIER=true in your shell env");
            writer.println("  - add llm-classifier=true to ~/.kairo-code/config.properties");
            writer.println(SEPARATOR);
            writer.flush();
            return;
        }

        writer.println("Status            : enabled");
        writer.println();
        renderStats(writer, classifier);
        writer.println(SEPARATOR);
        writer.flush();
    }

    private static void renderConfig(PrintWriter writer, CodeAgentConfig config) {
        LlmClassifierConfig cfg = config != null ? config.llmClassifier() : null;
        if (cfg == null) {
            // CodeAgentConfig compact ctor normalizes null to disabled(), but defensively guard
            // against an entry path that bypassed the constructor (tests, mocks, etc).
            writer.println("Config            : (unavailable)");
            return;
        }
        String agentModel = config != null ? config.modelName() : "unknown";
        String effectiveModel =
                (cfg.model() != null && !cfg.model().isBlank())
                        ? cfg.model()
                        : agentModel + " (inherited from agent)";
        writer.printf("Configured enabled: %s%n", cfg.enabled());
        writer.printf("Model             : %s%n", effectiveModel);
        writer.printf("Cache size        : %d%n", cfg.cacheSize());
        writer.printf("Timeout           : %d ms%n", cfg.timeoutMillis());
    }

    private static void renderStats(PrintWriter writer, LlmBashClassifier classifier) {
        LlmBashClassifier.Stats s = classifier.snapshot();
        long totalLookups = s.cacheHits() + s.cacheMisses();
        double hitRate = totalLookups == 0 ? 0.0 : (s.cacheHits() * 100.0) / totalLookups;
        long avgLatencyMs = s.llmCalls() == 0 ? 0 : s.totalLatencyMillis() / s.llmCalls();

        writer.printf("LLM calls         : %d%n", s.llmCalls());
        writer.printf("LLM failures      : %d%n", s.llmFailures());
        writer.printf(
                "Cache hits/misses : %d / %d  (%.1f%% hit-rate)%n",
                s.cacheHits(), s.cacheMisses(), hitRate);
        writer.printf("Avg LLM latency   : %d ms%n", avgLatencyMs);
        writer.printf("Tokens (in/out)   : %d / %d%n", s.totalInputTokens(), s.totalOutputTokens());

        Map<Category, Long> verdicts = s.verdictCounts();
        long verdictTotal = 0L;
        for (long v : verdicts.values()) verdictTotal += v;
        if (verdictTotal > 0) {
            writer.println("Verdict breakdown :");
            for (Category c : Category.values()) {
                long count = verdicts.getOrDefault(c, 0L);
                if (count > 0) {
                    writer.printf("  %-12s %d%n", c.name(), count);
                }
            }
        } else {
            writer.println("Verdict breakdown : (no LLM-resolved verdicts yet)");
        }
    }
}
