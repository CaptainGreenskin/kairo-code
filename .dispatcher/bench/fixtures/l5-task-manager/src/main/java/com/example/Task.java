package com.example;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class Task {

    public enum Status {
        TODO, IN_PROGRESS, COMPLETED
    }

    private final String id;
    private String title;
    private TaskPriority priority;
    private Status status;
    private LocalDate dueDate;
    private List<String> tags;

    public Task(String title, TaskPriority priority, LocalDate dueDate) {
        this.id = UUID.randomUUID().toString();
        this.title = Objects.requireNonNull(title, "title cannot be null");
        this.priority = Objects.requireNonNull(priority, "priority cannot be null");
        this.status = Status.TODO;
        this.dueDate = dueDate;
        this.tags = new ArrayList<>();
    }

    // For testing / reconstruction
    public Task(String id, String title, TaskPriority priority, Status status, LocalDate dueDate, List<String> tags) {
        this.id = id;
        this.title = title;
        this.priority = priority;
        this.status = status;
        this.dueDate = dueDate;
        this.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>();
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public TaskPriority getPriority() {
        return priority;
    }

    public void setPriority(TaskPriority priority) {
        this.priority = priority;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public List<String> getTags() {
        return Collections.unmodifiableList(tags);
    }

    public void addTag(String tag) {
        if (tag != null && !tag.isBlank()) {
            tags.add(tag);
        }
    }

    @Override
    public String toString() {
        return "Task{id='" + id + "', title='" + title + "', priority=" + priority
                + ", status=" + status + ", dueDate=" + dueDate + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Task task = (Task) o;
        return Objects.equals(id, task.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
