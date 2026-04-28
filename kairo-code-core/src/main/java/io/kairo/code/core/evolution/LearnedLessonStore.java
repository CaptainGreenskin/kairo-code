package io.kairo.code.core.evolution;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists and retrieves learned lessons from a JSON file.
 *
 * <p>Loads from the global store at {@code ~/.kairo-code/learned.json}. Project-specific lessons
 * live at {@code <workingDir>/.kairo-code/learned.json} and are loaded in addition to global ones,
 * with project lessons taking display priority.
 */
public final class LearnedLessonStore {

    private static final Logger log = LoggerFactory.getLogger(LearnedLessonStore.class);
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public enum Status {
        PENDING,
        APPROVED,
        REJECTED
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Lesson(String id, String toolName, String lessonText, Status status, String timestamp) {

        public static Lesson create(String toolName, String lessonText, Status status) {
            return new Lesson(
                    UUID.randomUUID().toString().substring(0, 8),
                    toolName,
                    lessonText,
                    status,
                    Instant.now().toString());
        }

        public Lesson withStatus(Status newStatus) {
            return new Lesson(id, toolName, lessonText, newStatus, timestamp);
        }
    }

    private final Path storePath;

    public LearnedLessonStore(Path storePath) {
        this.storePath = storePath;
    }

    /**
     * Create a store backed by {@code <kairoDir>/learned.json}.
     */
    public static LearnedLessonStore fromKairoDir(Path kairoDir) {
        return new LearnedLessonStore(kairoDir.resolve("learned.json"));
    }

    public List<Lesson> list() {
        return load();
    }

    public List<Lesson> listApproved() {
        return load().stream().filter(l -> l.status() == Status.APPROVED).toList();
    }

    public void save(Lesson lesson) {
        List<Lesson> lessons = new ArrayList<>(load());
        lessons.removeIf(l -> l.id().equals(lesson.id()));
        lessons.add(lesson);
        persist(lessons);
    }

    public boolean approve(String id) {
        return updateStatus(id, Status.APPROVED);
    }

    public boolean reject(String id) {
        return updateStatus(id, Status.REJECTED);
    }

    public boolean delete(String id) {
        List<Lesson> lessons = new ArrayList<>(load());
        boolean removed = lessons.removeIf(l -> l.id().equals(id));
        if (removed) {
            persist(lessons);
        }
        return removed;
    }

    /** Remove all REJECTED lessons. */
    public int clearRejected() {
        List<Lesson> lessons = new ArrayList<>(load());
        int before = lessons.size();
        lessons.removeIf(l -> l.status() == Status.REJECTED);
        persist(lessons);
        return before - lessons.size();
    }

    private boolean updateStatus(String id, Status status) {
        List<Lesson> lessons = new ArrayList<>(load());
        boolean found = false;
        for (int i = 0; i < lessons.size(); i++) {
            if (lessons.get(i).id().equals(id)) {
                lessons.set(i, lessons.get(i).withStatus(status));
                found = true;
                break;
            }
        }
        if (found) {
            persist(lessons);
        }
        return found;
    }

    private List<Lesson> load() {
        if (!Files.exists(storePath)) {
            return new ArrayList<>();
        }
        try {
            Lesson[] arr = MAPPER.readValue(storePath.toFile(), Lesson[].class);
            return new ArrayList<>(List.of(arr));
        } catch (IOException e) {
            log.warn("Failed to load learned lessons from {}: {}", storePath, e.getMessage());
            return new ArrayList<>();
        }
    }

    private void persist(List<Lesson> lessons) {
        try {
            Files.createDirectories(storePath.getParent());
            MAPPER.writeValue(storePath.toFile(), lessons);
        } catch (IOException e) {
            log.warn("Failed to persist learned lessons to {}: {}", storePath, e.getMessage());
        }
    }
}
