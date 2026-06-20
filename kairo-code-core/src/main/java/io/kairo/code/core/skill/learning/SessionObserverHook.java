package io.kairo.code.core.skill.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kairo.api.hook.PostActing;
import io.kairo.api.hook.PostActingEvent;
import io.kairo.api.hook.PreCompleteEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * POST_TOOL hook that captures tool invocations as observations for skill learning.
 *
 * <p>Observations are appended to a JSONL file at
 * {@code ~/.kairo-code/skill-learning/observations.jsonl} and accumulated in memory
 * for end-of-session analysis by {@link InstinctExtractor}.
 */
public class SessionObserverHook {

    private static final Logger log = LoggerFactory.getLogger(SessionObserverHook.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_INPUT_SUMMARY_CHARS = 200;

    private final Path observationsFile;
    private final List<ToolObservation> sessionObservations = new ArrayList<>();

    public SessionObserverHook() {
        this(Path.of(System.getProperty("user.home"),
                ".kairo-code", "skill-learning", "observations.jsonl"));
    }

    SessionObserverHook(Path observationsFile) {
        this.observationsFile = observationsFile;
    }

    @PostActing
    public void onPostActing(PostActingEvent event) {
        String toolName = event.toolName();
        boolean success = event.result() != null && !event.result().isError();
        String inputSummary = event.result() != null ? truncateStr(event.result().output(), MAX_INPUT_SUMMARY_CHARS) : "";
        String errorMsg = (event.result() != null && event.result().isError())
                ? event.result().output() : null;

        ToolObservation obs = success
                ? ToolObservation.success(toolName, inputSummary)
                : ToolObservation.failure(toolName, inputSummary, errorMsg);

        sessionObservations.add(obs);
        persistObservation(obs);
    }

    public List<ToolObservation> getSessionObservations() {
        return List.copyOf(sessionObservations);
    }

    @io.kairo.api.hook.HookHandler(io.kairo.api.hook.HookPhase.PRE_COMPLETE)
    public io.kairo.api.hook.HookResult<PreCompleteEvent> onPreComplete(PreCompleteEvent event) {
        if (sessionObservations.size() >= 5) {
            try {
                InstinctExtractor extractor = new InstinctExtractor();
                List<Instinct> instincts = extractor.extract(sessionObservations);
                if (!instincts.isEmpty()) {
                    log.info("Session end: extracted {} instinct(s) from {} observations",
                            instincts.size(), sessionObservations.size());
                }
            } catch (Exception e) {
                log.debug("Instinct extraction failed: {}", e.getMessage());
            }
        }
        return io.kairo.api.hook.HookResult.proceed(event);
    }

    private static String truncateStr(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private void persistObservation(ToolObservation obs) {
        try {
            Files.createDirectories(observationsFile.getParent());
            String line = MAPPER.writeValueAsString(Map.of(
                    "tool", obs.toolName(),
                    "input", obs.inputSummary(),
                    "success", obs.success(),
                    "error", obs.errorMessage() != null ? obs.errorMessage() : "",
                    "ts", obs.timestamp().toString())) + "\n";
            Files.writeString(observationsFile, line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.debug("Failed to persist observation: {}", e.getMessage());
        }
    }
}
