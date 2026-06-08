package io.kairo.code.cli.commands;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentSnapshot;
import io.kairo.api.message.Msg;
import io.kairo.api.model.ModelProvider;
import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentFactory;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.List;

/**
 * Compresses the current conversation history into a summary via LLM, then resets the session
 * with the summary as seed context.
 *
 * <p>Usage: {@code :compact}
 */
public class CompactCommand implements SlashCommand {

    private static final int MIN_TURNS = 6;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** Package-private provider override for testing. When set, used instead of OpenAIProvider. */
    ModelProvider testProvider;

    @Override
    public String name() {
        return "compact";
    }

    @Override
    public String description() {
        return "Compress conversation history into a summary";
    }

    @Override
    public void execute(String args, ReplContext context) {
        PrintWriter writer = context.writer();

        Agent agent = context.agent();
        if (agent == null) {
            writer.println("No active conversation to compact.");
            writer.flush();
            return;
        }

        List<Msg> history;
        try {
            AgentSnapshot snapshot = agent.snapshot();
            history = snapshot.conversationHistory();
        } catch (UnsupportedOperationException e) {
            writer.println("No conversation history available to compact.");
            writer.flush();
            return;
        }

        if (history == null || history.size() < MIN_TURNS) {
            writer.println("Conversation too short to compact.");
            writer.flush();
            return;
        }

        int turnCount = countTurns(history);
        CodeAgentConfig config = context.config();

        try {
            ModelProvider provider = testProvider != null
                    ? testProvider
                    : CodeAgentFactory.buildModelProvider(config.apiKey(), config.baseUrl(), config.modelName());
            var modelCfg = io.kairo.api.model.ModelConfig.builder()
                    .model(config.modelName()).maxTokens(2048).build();
            String prompt = "Summarize the following conversation into a concise continuation prompt. "
                    + "Preserve key facts, decisions, and file paths mentioned. Be brief.";
            List<Msg> compactInput = List.of(
                    Msg.of(io.kairo.api.message.MsgRole.SYSTEM, prompt),
                    Msg.of(io.kairo.api.message.MsgRole.USER,
                            history.stream().map(Msg::text).filter(t -> t != null)
                                    .collect(java.util.stream.Collectors.joining("\n---\n"))));
            io.kairo.api.model.ModelResponse response = provider.call(compactInput, modelCfg).block(TIMEOUT);
            String summary = null;
            if (response != null && response.contents() != null) {
                summary = response.contents().stream()
                        .filter(io.kairo.api.message.Content.TextContent.class::isInstance)
                        .map(c -> ((io.kairo.api.message.Content.TextContent) c).text())
                        .collect(java.util.stream.Collectors.joining("\n"));
            }

            if (summary == null || summary.isBlank()) {
                writer.println("Failed to generate summary: empty response from model.");
                writer.flush();
                return;
            }

            context.resetWithSummary(summary);
            writer.println("Conversation compacted. " + turnCount + " turns -> 1 summary.");
        } catch (Exception e) {
            writer.println("Failed to compact conversation: " + e.getMessage());
        }
        writer.flush();
    }

    /** Count conversation turns (USER messages). */
    private static int countTurns(List<Msg> history) {
        return (int) history.stream()
                .filter(m -> m.role() != null && m.role().name().equals("USER"))
                .count();
    }
}
