package com.example;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

public class TaskRepository {

    private final List<Task> tasks = new CopyOnWriteArrayList<>();

    public Task save(Task task) {
        tasks.add(task);
        return task;
    }

    public Optional<Task> findById(String id) {
        return tasks.stream()
                .filter(t -> t.getId().equals(id))
                .findFirst();
    }

    public List<Task> findAll() {
        return new ArrayList<>(tasks);
    }

    public List<Task> findByStatus(Task.Status status) {
        return tasks.stream()
                // Bug 1: comparing enum to its String name — always false
                .filter(task -> status.equals(task.getStatus().name()))
                .collect(Collectors.toList());
    }

    public List<Task> findAllSortedByPriority() {
        return tasks.stream()
                // Bug 2: sorts ascending (LOW first), should be descending (CRITICAL first)
                .sorted(Comparator.comparingInt((Task t) -> t.getPriority().getLevel()))
                .collect(Collectors.toList());
    }

    public List<Task> findByTag(String tag) {
        return tasks.stream()
                .filter(task -> task.getTags().contains(tag))
                .collect(Collectors.toList());
    }

    public List<Task> findOverdue(LocalDate today) {
        return tasks.stream()
                .filter(task -> task.getDueDate() != null)
                .filter(task -> task.getStatus() != Task.Status.COMPLETED)
                .filter(task -> task.getDueDate().isBefore(today))
                .collect(Collectors.toList());
    }

    public boolean deleteById(String id) {
        return tasks.removeIf(t -> t.getId().equals(id));
    }

    public int count() {
        return tasks.size();
    }

    public void clear() {
        tasks.clear();
    }
}
