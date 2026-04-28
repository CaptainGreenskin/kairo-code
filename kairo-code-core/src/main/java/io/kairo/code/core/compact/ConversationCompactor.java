package io.kairo.code.core.compact;

import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.model.ModelProvider;
import io.kairo.core.agent.AgentBuilder;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Compresses old conversation turns into a concise summary via a one-shot LLM call.
 *
 * <p>Usage: feed the conversation history, get back a summary string that can be injected
 * as a single SYSTEM message to replace the full history.
 */
public final class ConversationCompactor {

    private static final int MAX_HISTORY_MESSAGES = 50;

    private ConversationCompactor() {}

    /**
     * Summarize the given conversation history using a one-shot LLM call.
     *
     * @param history    the full conversation history (oldest first)
     * @param provider   the model provider to use
     * @param modelName  the model name (e.g. "gpt-4o")
     * @return Mono emitting the summary text, or Mono.error on failure
     */
    public static Mono<String> compact(List<Msg> history, ModelProvider provider, String modelName) {
        if (history == null || history.isEmpty()) {
            return Mono.error(new IllegalArgumentException("History must not be empty"));
        }

        // Take at most the latest N messages to avoid exceeding LLM context window
        int start = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
        List<Msg> recent = history.subList(start, history.size());

        String serialized = serializeHistory(recent);

        String systemPrompt = "Summarize this conversation concisely in Chinese. "
                + "Keep key decisions, file edits, and errors. "
                + "Omit verbose tool output. Be precise and actionable.";

        String userMsg = "<conversation>\n" + serialized + "\n</conversation>";

        AgentBuilder builder = AgentBuilder.create()
                .name("compactor")
                .model(provider)
                .modelName(modelName)
                .systemPrompt(systemPrompt)
                .maxIterations(1);

        return builder.build()
                .call(Msg.of(MsgRole.USER, userMsg))
                .flatMap(response -> {
                    if (response == null) {
                        return Mono.error(new RuntimeException("Compactor returned null response"));
                    }
                    String text = response.text();
                    if (text == null || text.isBlank()) {
                        return Mono.error(new RuntimeException("Compactor returned empty text"));
                    }
                    return Mono.just(text.trim());
                });
    }

    /**
     * Serialize a list of messages into a readable text format for the LLM.
     */
    private static String serializeHistory(List<Msg> messages) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            Msg msg = messages.get(i);
            String role = msg.role() != null ? msg.role().name() : "UNKNOWN";
            String content = msg.text() != null ? msg.text() : "(empty)";
            sb.append("[").append(i + 1).append("] ").append(role).append(":\n");
            sb.append(content).append("\n\n");
        }
        return sb.toString();
    }
}
