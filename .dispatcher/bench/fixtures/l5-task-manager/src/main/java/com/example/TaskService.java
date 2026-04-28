package com.example;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public class TaskService {

    private final TaskRepository repository;
    private final TaskValidator validator;

    public TaskService(TaskRepository repository, TaskValidator validator) {
        this.repository = repository;
        this.validator = validator;
    }

    public Task createTask(String title, TaskPriority priority, LocalDate dueDate) {
        Task task = new Task(title, priority, dueDate);
        validator.validate(task);
        return repository.save(task);
    }

    public Optional<Task> getTask(String id) {
        return repository.findById(id);
    }

    // Bug 4: does not check if task is already COMPLETED before completing it again
    public void completeTask(String id) {
        Task task = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + id));
        task.setStatus(Task.Status.COMPLETED);
    }

    public List<Task> getTasksByStatus(Task.Status status) {
        return repository.findByStatus(status);
    }

    public List<Task> prioritize(TaskPriority priority) {
        return repository.findAllSortedByPriority();
    }

    // Bug 5: uses isAfter instead of isBefore, returning non-overdue tasks
    public List<Task> getOverdueTasks(LocalDate today) {
        return repository.findAll().stream()
                .filter(task -> task.getDueDate() != null)
                .filter(task -> task.getStatus() != Task.Status.COMPLETED)
                .filter(task -> task.getDueDate().isAfter(today))
                .toList();
    }

    public void updateTaskPriority(String id, TaskPriority newPriority) {
        Task task = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + id));
        validator.validate(task);
        task.setPriority(newPriority);
    }

    public List<Task> getAllTasks() {
        return repository.findAll();
    }
}
