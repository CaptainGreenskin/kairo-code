package io.kairo.code.server.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * CRUD for workspaces, persisted at ~/.kairo-code/workspaces.json.
 * Modeled after ConfigPersistenceService: atomic write (tmp → rename), POSIX 700/600.
 */
@Component
public class WorkspacePersistenceService {

    private static final Logger log = LoggerFactory.getLogger(WorkspacePersistenceService.class);

    private final Path filePath;
    private final ObjectMapper mapper = new ObjectMapper();

    public WorkspacePersistenceService() {
        this.filePath = Path.of(System.getProperty("user.home"), ".kairo-code", "workspaces.json");
    }

    public WorkspacePersistenceService(Path filePath) {
        this.filePath = filePath;
    }

    public synchronized List<WorkspaceConfig> loadAll() {
        if (!Files.exists(filePath)) {
            return Collections.emptyList();
        }
        try {
            String json = Files.readString(filePath);
            List<WorkspaceConfig> list = mapper.readValue(json, new TypeReference<List<WorkspaceConfig>>() {});
            return list != null ? list : Collections.emptyList();
        } catch (IOException e) {
            log.warn("Failed to load workspaces from {}: {}", filePath, e.getMessage());
            return Collections.emptyList();
        }
    }

    public Optional<WorkspaceConfig> findById(String id) {
        return loadAll().stream().filter(w -> w.id().equals(id)).findFirst();
    }

    public synchronized WorkspaceConfig add(String name, String workingDir, boolean useWorktree) throws IOException {
        List<WorkspaceConfig> all = new ArrayList<>(loadAll());
        WorkspaceConfig fresh = new WorkspaceConfig(
                UUID.randomUUID().toString(),
                name,
                workingDir,
                useWorktree,
                System.currentTimeMillis()
        );
        all.add(fresh);
        saveAll(all);
        return fresh;
    }

    public synchronized Optional<WorkspaceConfig> update(String id, String name, String workingDir, Boolean useWorktree) throws IOException {
        List<WorkspaceConfig> all = new ArrayList<>(loadAll());
        for (int i = 0; i < all.size(); i++) {
            WorkspaceConfig w = all.get(i);
            if (w.id().equals(id)) {
                WorkspaceConfig updated = new WorkspaceConfig(
                        w.id(),
                        name != null ? name : w.name(),
                        workingDir != null ? workingDir : w.workingDir(),
                        useWorktree != null ? useWorktree : w.useWorktree(),
                        w.createdAt()
                );
                all.set(i, updated);
                saveAll(all);
                return Optional.of(updated);
            }
        }
        return Optional.empty();
    }

    public synchronized boolean delete(String id) throws IOException {
        List<WorkspaceConfig> all = new ArrayList<>(loadAll());
        boolean removed = all.removeIf(w -> w.id().equals(id));
        if (removed) {
            saveAll(all);
        }
        return removed;
    }

    /**
     * Bootstrap a default workspace if none exist.
     * Uses fallbackWorkingDir if provided, else user.dir.
     * Returns the existing or newly-created list.
     */
    public synchronized List<WorkspaceConfig> bootstrapIfEmpty(String fallbackWorkingDir) {
        List<WorkspaceConfig> existing = loadAll();
        if (!existing.isEmpty()) {
            return existing;
        }
        String wd = (fallbackWorkingDir != null && !fallbackWorkingDir.isBlank())
                ? fallbackWorkingDir
                : System.getProperty("user.dir");
        try {
            WorkspaceConfig def = add("default", wd, false);
            log.info("Bootstrapped default workspace id={} workingDir={}", def.id(), def.workingDir());
            return List.of(def);
        } catch (IOException e) {
            log.warn("Failed to bootstrap default workspace: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private void saveAll(List<WorkspaceConfig> all) throws IOException {
        Path dir = filePath.getParent();
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            setPermissions(dir, "rwx------");
        }
        Path tmpPath = filePath.resolveSibling(filePath.getFileName() + ".tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(tmpPath.toFile(), all);
        Files.move(tmpPath, filePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        setPermissions(filePath, "rw-------");
    }

    private void setPermissions(Path path, String perms) {
        try {
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                Set<PosixFilePermission> permissions = PosixFilePermissions.fromString(perms);
                Files.setPosixFilePermissions(path, permissions);
            }
        } catch (IOException e) {
            log.debug("Could not set permissions on {}: {}", path, e.getMessage());
        }
    }
}
