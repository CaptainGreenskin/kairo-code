package io.kairo.code.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists per-session metadata to {@code ~/.kairo-code/active-sessions/{id}.json} so the
 * server can rehydrate sessions after a restart.
 *
 * <p>Unlike the per-workingDir {@code phase.txt} (which is shared/overwritten when multiple
 * sessions point to the same workspace), this stores one file per session ID and includes
 * enough info to rebuild a {@code SessionEntry} on startup.
 */
public class SessionMetaPersistence {

    private static final Logger log = LoggerFactory.getLogger(SessionMetaPersistence.class);
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path baseDir;

    public SessionMetaPersistence() {
        this(Path.of(System.getProperty("user.home"), ".kairo-code", "active-sessions"));
    }

    SessionMetaPersistence(Path baseDir) {
        this.baseDir = baseDir;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SessionMeta(
            String sessionId,
            String workspaceId,
            String workingDir,
            String modelName,
            String baseUrl,
            String provider,
            String phase,
            String mode,
            long createdAt) {}

    public void save(SessionMeta meta) {
        try {
            Files.createDirectories(baseDir);
            Files.writeString(baseDir.resolve(meta.sessionId() + ".json"),
                    MAPPER.writeValueAsString(meta));
        } catch (IOException e) {
            log.debug("Failed to persist session meta for {}: {}", meta.sessionId(), e.getMessage());
        }
    }

    public void updatePhase(String sessionId, SessionPhase phase) {
        SessionMeta existing = load(sessionId);
        if (existing == null) return;
        save(new SessionMeta(
                existing.sessionId(), existing.workspaceId(), existing.workingDir(),
                existing.modelName(), existing.baseUrl(), existing.provider(),
                phase.name(), existing.mode(), existing.createdAt()));
    }

    public void remove(String sessionId) {
        try {
            Files.deleteIfExists(baseDir.resolve(sessionId + ".json"));
        } catch (IOException e) {
            log.debug("Failed to remove session meta for {}: {}", sessionId, e.getMessage());
        }
    }

    public SessionMeta load(String sessionId) {
        Path file = baseDir.resolve(sessionId + ".json");
        if (!Files.exists(file)) return null;
        try {
            return MAPPER.readValue(Files.readString(file), SessionMeta.class);
        } catch (IOException e) {
            log.debug("Failed to read session meta for {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    public List<SessionMeta> loadAll() {
        if (!Files.exists(baseDir)) return List.of();
        List<SessionMeta> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(baseDir)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                try {
                    result.add(MAPPER.readValue(Files.readString(p), SessionMeta.class));
                } catch (IOException e) {
                    log.debug("Skipping corrupt session meta file {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.debug("Failed to list session meta dir: {}", e.getMessage());
        }
        return result;
    }

    public List<SessionMeta> loadNonTerminal() {
        return loadAll().stream()
                .filter(m -> {
                    try {
                        SessionPhase p = SessionPhase.valueOf(m.phase());
                        return p != SessionPhase.COMPLETED;
                    } catch (Exception e) {
                        return false;
                    }
                })
                .toList();
    }
}
