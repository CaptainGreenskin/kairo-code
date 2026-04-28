package com.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TaskServiceTest {

    private TaskService service;
    private TaskRepository repository;

    @BeforeEach
    void setUp() {
        repository = new TaskRepository();
        TaskValidator validator = new TaskValidator();
        service = new TaskService(repository, validator);
    }

    @Test
    void createTaskSavesAndReturnsTask() {
        Task task = service.createTask("New task", TaskPriority.HIGH, LocalDate.now().plusDays(7));

        assertNotNull(task.getId());
        assertEquals("New task", task.getTitle());
        assertEquals(TaskPriority.HIGH, task.getPriority());
        assertEquals(Task.Status.TODO, task.getStatus());
    }

    @Test
    void createTaskWithNullDueDate() {
        Task task = service.createTask("No due date", TaskPriority.LOW, null);
        assertNotNull(task.getId());
        assertNull(task.getDueDate());
    }

    @Test
    void getTaskReturnsExistingTask() {
        Task created = service.createTask("Find me", TaskPriority.MEDIUM, LocalDate.now().plusDays(5));
        Optional<Task> found = service.getTask(created.getId());

        assertTrue(found.isPresent());
        assertEquals("Find me", found.get().getTitle());
    }

    @Test
    void getTaskReturnsEmptyForNonExistentId() {
        Optional<Task> found = service.getTask("non-existent");
        assertTrue(found.isEmpty());
    }

    @Test
    void completeTaskChangesStatusToCompleted() {
        Task task = service.createTask("To complete", TaskPriority.HIGH, LocalDate.now().plusDays(3));
        service.completeTask(task.getId());

        Task completed = service.getTask(task.getId()).orElseThrow();
        assertEquals(Task.Status.COMPLETED, completed.getStatus());
    }

    @Test
    void completeTaskThrowsForNonExistentId() {
        assertThrows(IllegalArgumentException.class, () -> service.completeTask("non-existent"));
    }

    @Test
    void completeTaskAlreadyCompletedTaskShouldThrow() {
        Task task = service.createTask("Already done", TaskPriority.LOW, LocalDate.now().plusDays(1));
        service.completeTask(task.getId());

        // Bug 4: completing an already-completed task should throw
        assertThrows(IllegalStateException.class, () -> service.completeTask(task.getId()));
    }

    @Test
    void getTasksByStatusReturnsCorrectTasks() {
        service.createTask("Todo 1", TaskPriority.LOW, LocalDate.now().plusDays(1));
        service.createTask("Todo 2", TaskPriority.HIGH, LocalDate.now().plusDays(2));
        Task completed = service.createTask("Done", TaskPriority.MEDIUM, LocalDate.now().plusDays(3));
        service.completeTask(completed.getId());

        List<Task> todos = service.getTasksByStatus(Task.Status.TODO);
        assertEquals(2, todos.size());

        List<Task> completedTasks = service.getTasksByStatus(Task.Status.COMPLETED);
        assertEquals(1, completedTasks.size());
    }

    @Test
    void prioritizeReturnsTasksSortedByPriority() {
        service.createTask("Low", TaskPriority.LOW, LocalDate.now().plusDays(1));
        service.createTask("Critical", TaskPriority.CRITICAL, LocalDate.now().plusDays(2));
        service.createTask("High", TaskPriority.HIGH, LocalDate.now().plusDays(3));
        service.createTask("Medium", TaskPriority.MEDIUM, LocalDate.now().plusDays(4));

        List<Task> sorted = service.prioritize(TaskPriority.HIGH);
        // Bug 2: should be sorted with CRITICAL first (highest level), but bug sorts ascending
        assertEquals(4, sorted.size());
        assertEquals(TaskPriority.CRITICAL, sorted.get(0).getPriority());
        assertEquals(TaskPriority.HIGH, sorted.get(1).getPriority());
        assertEquals(TaskPriority.MEDIUM, sorted.get(2).getPriority());
        assertEquals(TaskPriority.LOW, sorted.get(3).getPriority());
    }

    @Test
    void getOverdueTasksReturnsOnlyOverdue() {
        LocalDate today = LocalDate.now().plusDays(30);

        // Use repository directly to create tasks with past due dates (validator would reject them)
        Task overdue1 = new Task("Overdue 1", TaskPriority.HIGH, LocalDate.now().plusDays(5));
        Task overdue2 = new Task("Overdue 2", TaskPriority.MEDIUM, LocalDate.now().plusDays(10));
        Task future = new Task("Future", TaskPriority.LOW, LocalDate.now().plusDays(60));
        Task completedOverdue = new Task("Done Overdue", TaskPriority.CRITICAL, LocalDate.now().plusDays(1));
        completedOverdue.setStatus(Task.Status.COMPLETED);

        repository.save(overdue1);
        repository.save(overdue2);
        repository.save(future);
        repository.save(completedOverdue);

        // Bug 5: this should return only the 2 overdue tasks
        List<Task> overdue = service.getOverdueTasks(today);
        assertEquals(2, overdue.size());
    }

    @Test
    void getOverdueTasksReturnsEmptyWhenNoneOverdue() {
        LocalDate today = LocalDate.now();

        Task future1 = new Task("Future 1", TaskPriority.HIGH, LocalDate.now().plusDays(20));
        Task future2 = new Task("Future 2", TaskPriority.LOW, LocalDate.now().plusDays(30));
        repository.save(future1);
        repository.save(future2);

        List<Task> overdue = service.getOverdueTasks(today);
        assertTrue(overdue.isEmpty());
    }

    @Test
    void updateTaskPriorityChangesPriority() {
        Task task = service.createTask("Update me", TaskPriority.LOW, LocalDate.now().plusDays(5));
        service.updateTaskPriority(task.getId(), TaskPriority.CRITICAL);

        Task updated = service.getTask(task.getId()).orElseThrow();
        assertEquals(TaskPriority.CRITICAL, updated.getPriority());
    }

    @Test
    void updateTaskPriorityThrowsForNonExistentId() {
        assertThrows(IllegalArgumentException.class,
                () -> service.updateTaskPriority("non-existent", TaskPriority.HIGH));
    }

    @Test
    void getAllTasksReturnsAllTasks() {
        service.createTask("Task 1", TaskPriority.LOW, LocalDate.now().plusDays(1));
        service.createTask("Task 2", TaskPriority.HIGH, LocalDate.now().plusDays(2));
        service.createTask("Task 3", TaskPriority.CRITICAL, LocalDate.now().plusDays(3));

        List<Task> all = service.getAllTasks();
        assertEquals(3, all.size());
    }
}
