package io.kairo.code.server.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kairo.code.server.config.ServerConfig.ServerProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * REST API for persisting and restoring session message snapshots.
 *
 * <p>Snapshots are stored as JSON files at:
 * {@code {workingDir}/.kairo-code/sessions/{sessionId}.json}.
 *
 * <p>Snapshots are bounded to {@link #MAX_SESSIONS_ON_DISK}; older entries are
 * evicted on each save.
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionSnapshotController {

    private static final int MAX_SESSIONS_ON_DISK = 50;
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("[a-zA-Z0-9_\\-]{1,64}");

    private final Path sessionsDir;
    private final ObjectMapper objectMapper;

    public SessionSnapshotController(ServerProperties props, ObjectMapper objectMapper) {
        this.sessionsDir = Paths.get(props.workingDir(), ".kairo-code", "sessions");
        this.objectMapper = objectMapper;
    }

    public record SnapshotMeta(String sessionId, String name, long savedAt, int messageCount) {}

    /**
     * List all persisted session snapshots (newest first, capped at {@link #MAX_SESSIONS_ON_DISK}).
     */
    @GetMapping("/snapshots")
    public List<SnapshotMeta> listSnapshots() throws IOException {
        if (!Files.exists(sessionsDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(sessionsDir)) {
            return files
                    .filter(p -> p.toString().endsWith(".json"))
                    .map(this::readMeta)
                    .filter(Objects::nonNull)
                    .filter(m -> !m.sessionId().isBlank())
                    .sorted(Comparator.comparingLong(SnapshotMeta::savedAt).reversed())
                    .limit(MAX_SESSIONS_ON_DISK)
                    .toList();
        }
    }

    /**
     * Save a session snapshot. The request body is a raw JSON object, expected
     * to contain {@code sessionId}, {@code name}, and {@code messages}; this
     * controller injects/overwrites {@code savedAt} and {@code sessionId} based
     * on the path variable.
     */
    @PostMapping("/{id}/snapshot")
    public Map<String, Object> saveSnapshot(
            @PathVariable("id") String id,
            @RequestBody String body) throws IOException {

        validateSessionId(id);
        Files.createDirectories(sessionsDir);

        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(body);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid JSON body");
        }
        if (parsed == null || !parsed.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Snapshot body must be a JSON object");
        }

        long savedAt = Instant.now().toEpochMilli();
        ObjectNode node = (ObjectNode) parsed;
        node.put("savedAt", savedAt);
        node.put("sessionId", id);

        Path file = sessionsDir.resolve(id + ".json");
        Files.writeString(file, objectMapper.writeValueAsString(node), StandardCharsets.UTF_8);

        evictOldSnapshots();

        return Map.of("sessionId", id, "savedAt", savedAt);
    }

    /**
     * Load a session snapshot. Returns 404 if no snapshot exists for {@code id}.
     */
    @GetMapping("/{id}/snapshot")
    public ResponseEntity<String> loadSnapshot(@PathVariable("id") String id) throws IOException {
        validateSessionId(id);
        Path file = sessionsDir.resolve(id + ".json");
        if (!Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header("Content-Type", "application/json")
                .body(Files.readString(file, StandardCharsets.UTF_8));
    }

    /**
     * Delete a session snapshot. Idempotent — returns 204 even if file is absent.
     */
    @DeleteMapping("/{id}/snapshot")
    public ResponseEntity<Void> deleteSnapshot(@PathVariable("id") String id) throws IOException {
        validateSessionId(id);
        Path file = sessionsDir.resolve(id + ".json");
        Files.deleteIfExists(file);
        return ResponseEntity.noContent().build();
    }

    private void validateSessionId(String id) {
        if (id == null || !SESSION_ID_PATTERN.matcher(id).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid session ID");
        }
    }

    private SnapshotMeta readMeta(Path p) {
        try {
            JsonNode node = objectMapper.readTree(p.toFile());
            return new SnapshotMeta(
                    node.path("sessionId").asText(""),
                    node.path("name").asText(""),
                    node.path("savedAt").asLong(0L),
                    node.path("messages").size());
        } catch (IOException e) {
            return null;
        }
    }

    private void evictOldSnapshots() throws IOException {
        if (!Files.exists(sessionsDir)) {
            return;
        }
        try (Stream<Path> files = Files.list(sessionsDir)) {
            List<Path> sorted = files
                    .filter(p -> p.toString().endsWith(".json"))
                    .sorted(Comparator.comparingLong(this::lastModifiedMillis))
                    .toList();
            int over = sorted.size() - MAX_SESSIONS_ON_DISK;
            for (int i = 0; i < over; i++) {
                Files.deleteIfExists(sorted.get(i));
            }
        }
    }

    private long lastModifiedMillis(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    public record SearchHit(
            String sessionId,
            String sessionName,
            long savedAt,
            String messageId,
            String role,
            String snippet,
            int matchIndex
    ) {}

    /**
     * Search across all session snapshots for messages containing {@code q}.
     * Returns up to {@code limit} hits, newest sessions first.
     */
    @GetMapping("/search")
    public List<SearchHit> searchMessages(
            @RequestParam("q") String q,
            @RequestParam(value = "limit", defaultValue = "30") int limit) throws IOException {

        if (q == null || q.trim().length() < 2) return List.of();
        String query = q.trim().toLowerCase();

        if (!Files.exists(sessionsDir)) return List.of();

        List<SearchHit> results = new ArrayList<>();

        try (Stream<Path> files = Files.list(sessionsDir)) {
            files.filter(p -> p.toString().endsWith(".json"))
                    .sorted(Comparator.comparingLong(p -> {
                        try { return -Files.getLastModifiedTime(p).toMillis(); }
                        catch (IOException e) { return 0L; }
                    }))
                    .forEach(file -> {
                        if (results.size() >= limit) return;
                        try {
                            JsonNode root = objectMapper.readTree(file.toFile());
                            String sessionId = root.path("sessionId").asText("");
                            String sessionName = root.path("name").asText("");
                            long savedAt = root.path("savedAt").asLong(0);
                            JsonNode messages = root.path("messages");
                            if (!messages.isArray()) return;

                            for (int i = 0; i < messages.size() && results.size() < limit; i++) {
                                JsonNode msg = messages.get(i);
                                String role = msg.path("role").asText("");
                                String content = extractText(msg);
                                if (content.toLowerCase().contains(query)) {
                                    int idx = content.toLowerCase().indexOf(query);
                                    int start = Math.max(0, idx - 60);
                                    int end = Math.min(content.length(), idx + query.length() + 60);
                                    String snippet = (start > 0 ? "…" : "")
                                            + content.substring(start, end)
                                            + (end < content.length() ? "…" : "");
                                    String messageId = msg.path("id").asText("");
                                    results.add(new SearchHit(sessionId, sessionName, savedAt, messageId, role, snippet, i));
                                }
                            }
                        } catch (IOException e) { /* skip corrupt file */ }
                    });
        }
        return results;
    }

    private String extractText(JsonNode msg) {
        JsonNode content = msg.path("content");
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            content.forEach(block -> {
                if ("text".equals(block.path("type").asText()))
                    sb.append(block.path("text").asText()).append(' ');
            });
            return sb.toString();
        }
        return "";
    }
}
