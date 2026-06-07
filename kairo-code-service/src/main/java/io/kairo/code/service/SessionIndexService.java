package io.kairo.code.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages a unified session index at {@code ~/.kairo-code/sessions/index.json}.
 *
 * <p>This is the single source of truth for session metadata. Individual snapshot
 * files ({@code {id}.json}) still hold the message payload; this index only tracks
 * lightweight metadata for fast listing and filtering.
 *
 * <p>Thread-safe: all mutations go through a {@link ReadWriteLock}. Disk writes use
 * atomic rename (write to tmp, then move) to prevent corruption on crashes.
 */
public class SessionIndexService {

    private static final Logger log = LoggerFactory.getLogger(SessionIndexService.class);
    private static final int MAX_ENTRIES = 50;
    private static final String INDEX_FILE = "index.json";
    private static final String INDEX_TMP = "index.json.tmp";

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path sessionsDir;
    private final Path activeSessionsDir;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private List<SessionIndexEntry> entries;

    public SessionIndexService() {
        this(Path.of(System.getProperty("user.home"), ".kairo-code", "sessions"),
             Path.of(System.getProperty("user.home"), ".kairo-code", "active-sessions"));
    }

    public SessionIndexService(Path sessionsDir, Path activeSessionsDir) {
        this.sessionsDir = sessionsDir;
        this.activeSessionsDir = activeSessionsDir;
        this.entries = readFromDisk();
    }

    public List<SessionIndexEntry> loadIndex() {
        lock.readLock().lock();
        try {
            return List.copyOf(entries);
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<SessionIndexEntry> get(String sessionId) {
        lock.readLock().lock();
        try {
            return entries.stream()
                    .filter(e -> e.sessionId().equals(sessionId))
                    .findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    public void upsert(SessionIndexEntry entry) {
        lock.writeLock().lock();
        try {
            entries.removeIf(e -> e.sessionId().equals(entry.sessionId()));
            entries.add(entry);
            evictIfOverCap();
            flush();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void updateStatus(String sessionId, String status) {
        lock.writeLock().lock();
        try {
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).sessionId().equals(sessionId)) {
                    entries.set(i, entries.get(i).withStatus(status));
                    flush();
                    return;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void updateSnapshot(String sessionId, boolean hasSnapshot, int messageCount) {
        lock.writeLock().lock();
        try {
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).sessionId().equals(sessionId)) {
                    entries.set(i, entries.get(i).withSnapshot(hasSnapshot, messageCount));
                    flush();
                    return;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void updateName(String sessionId, String name) {
        lock.writeLock().lock();
        try {
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).sessionId().equals(sessionId)) {
                    entries.set(i, entries.get(i).withName(name));
                    flush();
                    return;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void remove(String sessionId) {
        lock.writeLock().lock();
        try {
            if (entries.removeIf(e -> e.sessionId().equals(sessionId))) {
                flush();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Bootstrap the index from existing files if index.json does not exist.
     * Reads active-sessions/ and sessions/ dirs to create initial entries.
     */
    public void migrateIfAbsent() {
        Path indexPath = sessionsDir.resolve(INDEX_FILE);
        if (Files.exists(indexPath)) {
            return;
        }

        log.info("index.json not found, migrating from existing session files...");

        lock.writeLock().lock();
        try {
            List<SessionIndexEntry> migrated = new ArrayList<>();

            // 1. Scan active-sessions/ → status=idle (not yet confirmed alive)
            if (Files.isDirectory(activeSessionsDir)) {
                try (Stream<Path> files = Files.list(activeSessionsDir)) {
                    files.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                        try {
                            SessionMetaPersistence.SessionMeta meta =
                                    MAPPER.readValue(Files.readString(p), SessionMetaPersistence.SessionMeta.class);
                            migrated.add(new SessionIndexEntry(
                                    meta.sessionId(),
                                    null,
                                    meta.workspaceId(),
                                    meta.workingDir(),
                                    meta.modelName(),
                                    SessionIndexEntry.STATUS_IDLE,
                                    meta.createdAt(),
                                    meta.createdAt(),
                                    0,
                                    false));
                        } catch (IOException e) {
                            log.debug("Skipping corrupt active-session file {}: {}", p, e.getMessage());
                        }
                    });
                } catch (IOException e) {
                    log.warn("Failed to scan active-sessions dir: {}", e.getMessage());
                }
            }

            // 2. Scan sessions/*.json (excluding index files) → status=archived
            if (Files.isDirectory(sessionsDir)) {
                try (Stream<Path> files = Files.list(sessionsDir)) {
                    files.filter(p -> p.toString().endsWith(".json"))
                         .filter(p -> !p.getFileName().toString().equals(INDEX_FILE))
                         .filter(p -> !p.getFileName().toString().equals(INDEX_TMP))
                         .forEach(p -> {
                             try {
                                 SnapshotHeader header = MAPPER.readValue(
                                         Files.readString(p), SnapshotHeader.class);
                                 if (header.sessionId == null || header.sessionId.isBlank()) return;

                                 // Check if already added from active-sessions
                                 boolean alreadyPresent = migrated.stream()
                                         .anyMatch(e -> e.sessionId().equals(header.sessionId));
                                 if (alreadyPresent) {
                                     // Update existing entry with snapshot info
                                     for (int i = 0; i < migrated.size(); i++) {
                                         if (migrated.get(i).sessionId().equals(header.sessionId)) {
                                             migrated.set(i, migrated.get(i)
                                                     .withSnapshot(true, header.messageCount)
                                                     .withName(header.name));
                                             break;
                                         }
                                     }
                                 } else {
                                     migrated.add(new SessionIndexEntry(
                                             header.sessionId,
                                             header.name,
                                             null,
                                             null,
                                             null,
                                             SessionIndexEntry.STATUS_ARCHIVED,
                                             header.savedAt,
                                             header.savedAt,
                                             header.messageCount,
                                             true));
                                 }
                             } catch (IOException e) {
                                 log.debug("Skipping corrupt snapshot file {}: {}", p, e.getMessage());
                             }
                         });
                } catch (IOException e) {
                    log.warn("Failed to scan sessions dir: {}", e.getMessage());
                }
            }

            // 3. Sort by updatedAt desc, cap at MAX_ENTRIES
            migrated.sort(Comparator.comparingLong(SessionIndexEntry::updatedAt).reversed());
            if (migrated.size() > MAX_ENTRIES) {
                migrated.subList(MAX_ENTRIES, migrated.size()).clear();
            }

            this.entries = migrated;
            flush();
            log.info("Migrated {} sessions into index.json", migrated.size());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Reconcile the index with actual live state after server startup.
     * - Active entries not in liveSessionIds → transition to idle
     * - Live sessions not in the index → add as active
     * - Entries with hasSnapshot=true but missing file → set hasSnapshot=false
     */
    public void reconcile(Set<String> liveSessionIds) {
        lock.writeLock().lock();
        try {
            boolean changed = false;

            for (int i = 0; i < entries.size(); i++) {
                SessionIndexEntry e = entries.get(i);

                // Active in index but not actually alive → idle
                if (SessionIndexEntry.STATUS_ACTIVE.equals(e.status())
                        && !liveSessionIds.contains(e.sessionId())) {
                    entries.set(i, e.withStatus(SessionIndexEntry.STATUS_IDLE));
                    changed = true;
                }

                // hasSnapshot but file missing → clear
                if (e.hasSnapshot()) {
                    Path snapshotFile = sessionsDir.resolve(e.sessionId() + ".json");
                    if (!Files.exists(snapshotFile)) {
                        entries.set(i, entries.get(i).withSnapshot(false, 0));
                        changed = true;
                    }
                }
            }

            // Live sessions not in the index → add as active
            Set<String> indexedIds = new java.util.HashSet<>();
            entries.forEach(e -> indexedIds.add(e.sessionId()));
            for (String liveId : liveSessionIds) {
                if (!indexedIds.contains(liveId)) {
                    entries.add(new SessionIndexEntry(
                            liveId, null, null, null, null,
                            SessionIndexEntry.STATUS_ACTIVE,
                            System.currentTimeMillis(), System.currentTimeMillis(),
                            0, false));
                    changed = true;
                }
            }

            if (changed) {
                evictIfOverCap();
                flush();
            }

            log.debug("Reconcile complete: {} entries, {} live", entries.size(), liveSessionIds.size());
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void evictIfOverCap() {
        if (entries.size() <= MAX_ENTRIES) return;

        // Sort candidates for eviction: archived first, then idle, by oldest updatedAt.
        // Never evict active entries.
        entries.sort(Comparator
                .<SessionIndexEntry, Integer>comparing(e -> statusPriority(e.status()))
                .thenComparing(SessionIndexEntry::updatedAt));

        while (entries.size() > MAX_ENTRIES) {
            SessionIndexEntry candidate = entries.get(0);
            if (SessionIndexEntry.STATUS_ACTIVE.equals(candidate.status())) {
                break; // don't evict active sessions
            }
            entries.remove(0);
        }

        // Restore natural order (newest first)
        entries.sort(Comparator.comparingLong(SessionIndexEntry::updatedAt).reversed());
    }

    private static int statusPriority(String status) {
        return switch (status) {
            case SessionIndexEntry.STATUS_ARCHIVED -> 0; // evict first
            case SessionIndexEntry.STATUS_IDLE -> 1;
            case SessionIndexEntry.STATUS_ACTIVE -> 2;   // evict last
            default -> 1;
        };
    }

    private List<SessionIndexEntry> readFromDisk() {
        Path indexPath = sessionsDir.resolve(INDEX_FILE);
        if (!Files.exists(indexPath)) {
            return new ArrayList<>();
        }
        try {
            IndexFile file = MAPPER.readValue(Files.readString(indexPath), IndexFile.class);
            return file.sessions != null ? new ArrayList<>(file.sessions) : new ArrayList<>();
        } catch (IOException e) {
            log.warn("Failed to read index.json, starting empty: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private void flush() {
        try {
            Files.createDirectories(sessionsDir);
            IndexFile file = new IndexFile(1, List.copyOf(entries));
            Path tmp = sessionsDir.resolve(INDEX_TMP);
            Path target = sessionsDir.resolve(INDEX_FILE);
            Files.writeString(tmp, MAPPER.writeValueAsString(file));
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("Failed to write index.json: {}", e.getMessage());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record IndexFile(int version, List<SessionIndexEntry> sessions) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SnapshotHeader(String sessionId, String name, long savedAt, int messageCount) {
        @com.fasterxml.jackson.annotation.JsonCreator
        SnapshotHeader(
                @com.fasterxml.jackson.annotation.JsonProperty("sessionId") String sessionId,
                @com.fasterxml.jackson.annotation.JsonProperty("name") String name,
                @com.fasterxml.jackson.annotation.JsonProperty("savedAt") long savedAt,
                @com.fasterxml.jackson.annotation.JsonProperty("messages") com.fasterxml.jackson.databind.JsonNode messages) {
            this(sessionId, name, savedAt, messages != null && messages.isArray() ? messages.size() : 0);
        }
    }
}
