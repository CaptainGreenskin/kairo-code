package io.kairo.code.core.hook;

import io.kairo.api.hook.HookHandler;
import io.kairo.api.hook.HookPhase;
import io.kairo.api.hook.HookResult;
import io.kairo.api.hook.PostReasoningEvent;
import io.kairo.api.message.Content;
import io.kairo.api.model.ModelResponse;
import io.kairo.code.core.session.SessionWriter;
import java.time.Instant;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hook that appends each assistant response to a JSONL session file via {@link SessionWriter}.
 *
 * <p>Fires after each model reasoning call ({@link HookPhase#POST_REASONING}). Extracts the
 * assistant's text content and token count from the {@link PostReasoningEvent} and appends a
 * single JSONL line. Never blocks or alters the agent flow.
 */
public class SessionAppendHook {

    private static final Logger log = LoggerFactory.getLogger(SessionAppendHook.class);

    private final SessionWriter writer;

    public SessionAppendHook(SessionWriter writer) {
        this.writer = writer;
    }

    @HookHandler(HookPhase.POST_REASONING)
    public HookResult<PostReasoningEvent> onPostReasoning(PostReasoningEvent event) {
        if (writer == null) {
            return HookResult.proceed(event);
        }

        ModelResponse response = event.response();
        if (response == null || response.contents() == null) {
            return HookResult.proceed(event);
        }

        try {
            // Extract text content from all TextContent blocks
            String text = response.contents().stream()
                    .filter(Content.TextContent.class::isInstance)
                    .map(c -> ((Content.TextContent) c).text())
                    .collect(Collectors.joining());

            if (text != null && !text.isBlank()) {
                int tokens = 0;
                if (response.usage() != null) {
                    tokens = response.usage().outputTokens();
                }
                writer.appendTurn("assistant", text, tokens, Instant.now());
            }
        } catch (Exception e) {
            log.debug("SessionAppendHook failed to write turn: {}", e.getMessage());
        }

        // Never block the agent flow
        return HookResult.proceed(event);
    }
}
