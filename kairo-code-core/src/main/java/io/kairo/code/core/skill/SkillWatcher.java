package io.kairo.code.core.skill;

import io.kairo.code.core.skill.FsSkillLoader.SkillWithSource;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches skill directories for file changes and triggers hot-reload with 500ms debounce.
 *
 * <p>Register listeners via {@link #addListener(Consumer)} — each reload event delivers
 * the full merged skill list from {@link FsSkillLoader}.
 *
 * <p>Implements {@link Closeable} so callers can use try-with-resources or wire
 * {@code @Bean(destroyMethod = "close")} for Spring lifecycle.
 */
public class SkillWatcher implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(SkillWatcher.class);
    private static final long DEBOUNCE_MS = 500;

    private final FsSkillLoader loader;
    private final WatchService watchService;
    private final List<Consumer<List<SkillWithSource>>> listeners = new CopyOnWriteArrayList<>();
    // Separate thread for the blocking watch loop
    private final Thread watchThread;
    // Separate scheduler for debounce + reload (must not be blocked by watchLoop)
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "skill-reload-scheduler"));
    private volatile ScheduledFuture<?> pending;
    private volatile boolean running = true;

    /**
     * Create a watcher that monitors all directories returned by {@link FsSkillLoader#getWatchedDirs()}.
     *
     * @param loader the skill loader to invoke on change events
     * @throws IOException if the WatchService cannot be created
     */
    public SkillWatcher(FsSkillLoader loader) throws IOException {
        this.loader = loader;
        this.watchService = FileSystems.getDefault().newWatchService();
        registerAll();
        this.watchThread = new Thread(this::watchLoop, "skill-watcher");
        this.watchThread.setDaemon(true);
        this.watchThread.start();
    }

    /**
     * Register a callback that receives the full skill list on each reload.
     */
    public void addListener(Consumer<List<SkillWithSource>> listener) {
        listeners.add(listener);
    }

    /**
     * Trigger an immediate reload and notify all listeners.
     * Useful for consumers that want the initial state after registering.
     */
    public void reloadNow() {
        reload();
    }

    private void registerAll() throws IOException {
        List<Path> dirs = loader.getWatchedDirs();
        for (Path dir : dirs) {
            if (Files.isDirectory(dir)) {
                dir.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                log.debug("Registered skill directory for watching: {}", dir);
            }
        }
    }

    private void watchLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                WatchKey key = watchService.take();
                key.pollEvents();
                key.reset();
                scheduleReload();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Skill watcher error: {}", e.getMessage());
            }
        }
    }

    private void scheduleReload() {
        if (pending != null) {
            pending.cancel(false);
        }
        pending = scheduler.schedule(this::reload, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private void reload() {
        List<SkillWithSource> skills = loader.loadAll();
        for (Consumer<List<SkillWithSource>> listener : listeners) {
            try {
                listener.accept(skills);
            } catch (Exception e) {
                log.warn("Skill reload listener threw: {}", e.getMessage());
            }
        }
        log.debug("Skill hot-reload complete, {} skills loaded", skills.size());
    }

    @Override
    public void close() throws IOException {
        running = false;
        watchThread.interrupt();
        scheduler.shutdownNow();
        watchService.close();
    }
}
