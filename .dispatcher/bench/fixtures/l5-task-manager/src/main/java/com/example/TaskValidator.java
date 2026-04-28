package com.example;

import java.time.LocalDate;

public class TaskValidator {

    public void validate(Task task) {
        if (task == null) {
            throw new IllegalArgumentException("Task cannot be null");
        }

        String title = task.getTitle();
        // Bug 3: misses null titles — only rejects non-null blank strings
        if (title != null && title.isBlank()) {
            throw new IllegalArgumentException("Title cannot be empty");
        }

        TaskPriority priority = task.getPriority();
        if (priority == null) {
            throw new IllegalArgumentException("Priority cannot be null");
        }

        LocalDate dueDate = task.getDueDate();
        if (dueDate != null && dueDate.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("Due date cannot be in the past");
        }
    }

    public boolean isValidPriority(TaskPriority priority) {
        return priority != null && priority.getLevel() >= 0 && priority.getLevel() <= 3;
    }

    public boolean isValidDueDate(LocalDate dueDate) {
        return dueDate == null || !dueDate.isBefore(LocalDate.now());
    }
}
