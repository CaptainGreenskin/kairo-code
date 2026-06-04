package io.kairo.code.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Defines the per-session storage layout under a workspace directory.
 *
 * <p>Each session gets its own subdirectory under {@code {workingDir}/.kairo-session/{sessionId}/}
 * containing checkpoint, iterations, phase, plan, and snapshot ref files. A lightweight
 * {@code index.json} at the root tracks session metadata for tab switching.
 *
 * <p>Legacy workspaces (pre-isolation) with files directly in {@code .kairo-session/} are
 * auto-migrated to a {@code _legacy/} subdirectory on first access.
 */
public class SessionStorageLayout {

    private static final Logger log = LoggerFactory.getLogger(SessionStorageLayout.class);

    private static final String SESSION_ROOT = ".kairo-session";
    private static final String LEGACY_DIR = "_legacy";
    private static final String INDEX_FILE = "index.json";
    private static final int DEFAULT_MAX_SESSIONS = 10;
    private static final Duration DEFAULT_MAX_AGE = Duration.ofDays(30);

    private final Path workingDir;

    public SessionStorageLayout(Path workingDir) {
        this.workingDir = workingDir;
    }

    public Path root() {
        return workingDir.resolve(SESSION_ROOT);
    }

    public Path sessionDir(String sessionId) {
        return root().resolve(sessionId);
    }

    public Path checkpoint(String sessionId) {
        return sessionDir(sessionId).resolve("checkpoint.json");
    }

    public Path iterations(String sessionId) {
        return sessionDir(sessionId).resolve("iterations");
    }

    public Path phase(String sessionId) {
        return sessionDir(sessionId).resolve("phase.txt");
    }

    public Path plan(String sessionId) {
        return sessionDir(sessionId).resolve("plan.md");
    }

    public Path snapshotRef(String sessionId) {
        return sessionDir(sessionId).resolve("snapshot.ref");
    }

    public Path indexFile() {
        return root().resolve(INDEX_FILE);
    }

    public void ensureSessionDir(String sessionId) {
        try {
            Files.createDirectories(sessionDir(sessionId).resolve("iterations"));
        } catch (IOException e) {
            log.warn("Failed to create session directory for {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Detect legacy format (files directly in .kairo-session/) and migrate to _legacy/ subdir.
     * Returns the synthetic session ID assigned to the legacy data, or empty if no legacy found.
     */
    public Optional<String> detectAndMigrateLegacy() {
        Path legacyCheckpoint = root().resolve("checkpoint.json");
        Path legacyPhase = root().resolve("phase.txt");
        Path legacyPlan = root().resolve("plan.md");
        Path legacyIterations = root().resolve("iterations");

        boolean hasLegacy = Files.exists(legacyCheckpoint)
                || Files.exists(legacyPhase)
                || Files.exists(legacyIterations);

        if (!hasLegacy) {
            return Optional.empty();
        }

        String syntheticId = "legacy-session";
        Path legacyDir = root().resolve(LEGACY_DIR).resolve(syntheticId);

        try {
            Files.createDirectories(legacyDir);

            if (Files.exists(legacyCheckpoint)) {
                Files.move(legacyCheckpoint, legacyDir.resolve("checkpoint.json"));
            }
            if (Files.exists(legacyPhase)) {
                Files.move(legacyPhase, legacyDir.resolve("phase.txt"));
            }
            if (Files.exists(legacyPlan)) {
                Files.move(legacyPlan, legacyDir.resolve("plan.md"));
            }
            if (Files.isDirectory(legacyIterations)) {
                Path targetIter = legacyDir.resolve("iterations");
                Files.move(legacyIterations, targetIter);
            }

            log.info("Migrated legacy session state to {}", legacyDir);
            return Optional.of(LEGACY_DIR + "/" + syntheticId);
        } catch (IOException e) {
            log.warn("Failed to migrate legacy session state: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Garbage-collect old session directories. Keeps the most recent {@code maxSessions} sessions
     * and any session accessed within {@code maxAge}. Deletes the rest.
     */
    public void gc(int maxSessions, Duration maxAge) {
        Path rootDir = root();
        if (!Files.isDirectory(rootDir)) return;

        try (Stream<Path> dirs = Files.list(rootDir)) {
            List<Path> sessionDirs = dirs
                    .filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().equals(LEGACY_DIR))
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparingLong(p -> {
                        try {
                            return Files.getLastModifiedTime((Path) p).toMillis();
                        } catch (IOException e) {
                            return 0L;
                        }
                    }).reversed())
                    .toList();

            Instant cutoff = Instant.now().minus(maxAge);
            int kept = 0;

            for (Path dir : sessionDirs) {
                Instant lastModified;
                try {
                    lastModified = Files.getLastModifiedTime(dir).toInstant();
                } catch (IOException e) {
                    lastModified = Instant.EPOCH;
                }

                boolean withinAge = lastModified.isAfter(cutoff);
                boolean withinCount = kept < maxSessions;

                if (withinAge || withinCount) {
                    kept++;
                } else {
                    deleteRecursively(dir);
                    log.debug("GC removed session dir: {}", dir.getFileName());
                }
            }
        } catch (IOException e) {
            log.warn("Session GC failed: {}", e.getMessage());
        }
    }

    public void gc() {
        gc(DEFAULT_MAX_SESSIONS, DEFAULT_MAX_AGE);
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }
}
