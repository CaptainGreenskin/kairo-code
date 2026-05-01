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
package io.kairo.code.core.team;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory task list shared across agents in a team.
 */
public final class SharedTaskList {

    private final String teamId;
    private final ConcurrentHashMap<String, SharedTask> tasks = new ConcurrentHashMap<>();

    public SharedTaskList(String teamId) {
        this.teamId = teamId;
    }

    public SharedTask create(String title, String description) {
        String id = "task-" + UUID.randomUUID().toString().substring(0, 8);
        long now = System.currentTimeMillis();
        SharedTask task = new SharedTask(id, teamId, title, description,
            SharedTask.TaskStatus.PENDING, null, List.of(), now, now);
        tasks.put(id, task);
        return task;
    }

    public Optional<SharedTask> claim(String taskId, String memberId) {
        return Optional.ofNullable(tasks.computeIfPresent(taskId, (id, t) -> {
            if (t.status() == SharedTask.TaskStatus.PENDING && t.ownerId() == null
                    && isUnblocked(t)) {
                return new SharedTask(t.taskId(), t.teamId(), t.title(), t.description(),
                    SharedTask.TaskStatus.IN_PROGRESS, memberId, t.blockedBy(),
                    t.createdAt(), System.currentTimeMillis());
            }
            return t;
        })).filter(t -> memberId.equals(t.ownerId()));
    }

    public boolean complete(String taskId, String memberId) {
        SharedTask current = tasks.get(taskId);
        if (current == null || !memberId.equals(current.ownerId())) return false;
        tasks.put(taskId, new SharedTask(taskId, current.teamId(), current.title(),
            current.description(), SharedTask.TaskStatus.COMPLETED, memberId,
            current.blockedBy(), current.createdAt(), System.currentTimeMillis()));
        return true;
    }

    public boolean fail(String taskId, String memberId) {
        SharedTask current = tasks.get(taskId);
        if (current == null || !memberId.equals(current.ownerId())) return false;
        tasks.put(taskId, new SharedTask(taskId, current.teamId(), current.title(),
            current.description(), SharedTask.TaskStatus.FAILED, memberId,
            current.blockedBy(), current.createdAt(), System.currentTimeMillis()));
        return true;
    }

    public List<SharedTask> availableTasks() {
        return tasks.values().stream()
            .filter(t -> t.status() == SharedTask.TaskStatus.PENDING
                && t.ownerId() == null
                && isUnblocked(t))
            .sorted(Comparator.comparingLong(SharedTask::createdAt))
            .collect(java.util.stream.Collectors.toList());
    }

    public List<SharedTask> all() {
        return new ArrayList<>(tasks.values());
    }

    private boolean isUnblocked(SharedTask task) {
        return task.blockedBy().stream()
            .allMatch(depId -> {
                SharedTask dep = tasks.get(depId);
                return dep != null && dep.status() == SharedTask.TaskStatus.COMPLETED;
            });
    }
}
