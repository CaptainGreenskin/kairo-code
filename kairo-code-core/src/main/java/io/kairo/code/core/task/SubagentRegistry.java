/*
 * Copyright 2025-2026 the Kairo authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.kairo.code.core.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/**
 * Registry mapping subagent names to their runtime state, enabling parent→child
 * message routing via {@code SendMessageTool}. Thread-safe for concurrent access
 * from the parent agent thread and multiple child virtual threads.
 */
public final class SubagentRegistry {

    public enum Status { RUNNING, SHUTTING_DOWN, COMPLETED, FAILED }

    public record Entry(
            String taskId,
            String name,
            BlockingQueue<String> inbox,
            AtomicReference<Status> status
    ) {}

    private final ConcurrentHashMap<String, Entry> byName = new ConcurrentHashMap<>();

    public void register(String name, String taskId) {
        byName.put(name, new Entry(taskId, name, new LinkedBlockingQueue<>(),
                new AtomicReference<>(Status.RUNNING)));
    }

    public void markCompleted(String name) {
        Entry e = byName.get(name);
        if (e != null) e.status().set(Status.COMPLETED);
    }

    public void markFailed(String name) {
        Entry e = byName.get(name);
        if (e != null) e.status().set(Status.FAILED);
    }

    public void markShuttingDown(String name) {
        Entry e = byName.get(name);
        if (e != null) e.status().set(Status.SHUTTING_DOWN);
    }

    public void unregister(String name) {
        byName.remove(name);
    }

    public Optional<Entry> lookup(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public boolean enqueue(String name, String message) {
        Entry e = byName.get(name);
        if (e == null || e.status().get() != Status.RUNNING) return false;
        return e.inbox().offer(message);
    }

    public List<String> drain(String name) {
        Entry e = byName.get(name);
        if (e == null) return List.of();
        List<String> msgs = new ArrayList<>();
        e.inbox().drainTo(msgs);
        return msgs;
    }

    public List<String> activeNames() {
        return byName.values().stream()
                .filter(e -> e.status().get() == Status.RUNNING)
                .map(Entry::name)
                .toList();
    }
}
