package io.kairo.code.core.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Appends session turns to a JSONL file (one JSON object per line) and reads them back.
 *
 * <p>Each turn is written atomically as a single line using {@link StandardOpenOption#APPEND}.
 * The file and parent directories are created on first write if they don't exist.
 *
 * <p>Thread-safe: concurrent appends rely on OS-level append atomicity for single-line writes.
 */
public class SessionWriter {

    private static final Logger log = LoggerFactory.getLogger(SessionWriter.class);

    private final Path sessionFile;
    private final ObjectMapper mapper;

    public SessionWriter(Path sessionFile) {
        this.sessionFile = sessionFile;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Return the path to this session's JSONL file.
     */
    public Path sessionFile() {
        return sessionFile;
    }

    /**
     * Append a single turn to the JSONL session file.
     * Creates the file and parent directories if absent.
     *
     * @param role     "user" or "assistant"
     * @param content  the message content
     * @param tokens   token count (0 for user turns)
     * @param timestamp when this turn occurred
     */
    public void appendTurn(String role, String content, int tokens, Instant timestamp) {
        try {
            Path parent = sessionFile.getParent();
            if (parent != null && !Files.isDirectory(parent)) {
                Files.createDirectories(parent);
            }
            // Build a compact JSON object on a single line
            Map<String, Object> turnMap = Map.of(
                    "role", role,
                    "content", content != null ? content : "",
                    "tokens", tokens,
                    "ts", timestamp.toString());
            String jsonLine = mapper.writeValueAsString(turnMap) + "\n";
            Files.writeString(sessionFile, jsonLine,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            log.warn("Failed to append turn to session file {}: {}", sessionFile, e.getMessage());
        }
    }

    /**
     * Read all turns from the session file.
     *
     * @return list of turns in file order; empty list if file is absent or empty
     */
    public List<SessionTurn> readSession() {
        if (!Files.exists(sessionFile)) {
            return Collections.emptyList();
        }
        List<SessionTurn> turns = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(sessionFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    SessionTurn turn = parseTurn(line);
                    if (turn != null) {
                        turns.add(turn);
                    }
                } catch (JsonProcessingException e) {
                    log.debug("Skipping malformed session line: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read session file {}: {}", sessionFile, e.getMessage());
        }
        return turns;
    }

    @SuppressWarnings("unchecked")
    private SessionTurn parseTurn(String json) throws JsonProcessingException {
        Map<String, Object> map = mapper.readValue(json, Map.class);
        String role = (String) map.get("role");
        String content = (String) map.get("content");
        int tokens = map.get("tokens") instanceof Number n ? n.intValue() : 0;
        String tsStr = (String) map.get("ts");
        Instant ts = tsStr != null ? Instant.parse(tsStr) : Instant.now();
        return new SessionTurn(role, content, tokens, ts);
    }
}
