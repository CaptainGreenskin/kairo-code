package com.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TaskRepositoryTest {

    private TaskRepository repository;

    @BeforeEach
    void setUp() {
        repository = new TaskRepository();
    }

    @Test
    void saveAndFindById() {
        Task task = new Task("Test task", TaskPriority.HIGH, LocalDate.now().plusDays(7));
        Task saved = repository.save(task);

        Optional<Task> found = repository.findById(saved.getId());
        assertTrue(found.isPresent());
        assertEquals("Test task", found.get().getTitle());
    }

    @Test
    void findByIdReturnsEmptyForNonExistentId() {
        Optional<Task> found = repository.findById("non-existent");
        assertTrue(found.isEmpty());
    }

    @Test
    void findAllReturnsAllTasks() {
        repository.save(new Task("Task 1", TaskPriority.LOW, LocalDate.now().plusDays(1)));
        repository.save(new Task("Task 2", TaskPriority.HIGH, LocalDate.now().plusDays(2)));
        repository.save(new Task("Task 3", TaskPriority.CRITICAL, LocalDate.now().plusDays(3)));

        List<Task> all = repository.findAll();
        assertEquals(3, all.size());
    }

    @Test
    void findByStatusReturnsMatchingTasks() {
        Task todo1 = repository.save(new Task("Todo 1", TaskPriority.LOW, LocalDate.now().plusDays(1)));
        Task todo2 = repository.save(new Task("Todo 2", TaskPriority.HIGH, LocalDate.now().plusDays(2)));
        Task completed = repository.save(new Task("Done", TaskPriority.MEDIUM, LocalDate.now().plusDays(3)));
        completed.setStatus(Task.Status.COMPLETED);

        List<Task> todos = repository.findByStatus(Task.Status.TODO);
        assertEquals(2, todos.size());
        assertTrue(todos.stream().allMatch(t -> t.getStatus() == Task.Status.TODO));
    }

    @Test
    void findByStatusReturnsEmptyWhenNoMatch() {
        repository.save(new Task("Task", TaskPriority.LOW, LocalDate.now().plusDays(1)));

        List<Task> completed = repository.findByStatus(Task.Status.COMPLETED);
        assertTrue(completed.isEmpty());
    }

    @Test
    void findAllSortedByPriorityReturnsCriticalFirst() {
        repository.save(new Task("Low", TaskPriority.LOW, LocalDate.now().plusDays(1)));
        repository.save(new Task("Critical", TaskPriority.CRITICAL, LocalDate.now().plusDays(2)));
        repository.save(new Task("High", TaskPriority.HIGH, LocalDate.now().plusDays(3)));
        repository.save(new Task("Medium", TaskPriority.MEDIUM, LocalDate.now().plusDays(4)));

        List<Task> result = repository.findAllSortedByPriority();
        assertEquals(4, result.size());
        // Bug 2: should be CRITICAL first but sorts ascending instead
        assertEquals(TaskPriority.CRITICAL, result.get(0).getPriority());
        assertEquals(TaskPriority.HIGH, result.get(1).getPriority());
        assertEquals(TaskPriority.MEDIUM, result.get(2).getPriority());
        assertEquals(TaskPriority.LOW, result.get(3).getPriority());
    }

    @Test
    void findOverdueReturnsOnlyOverdueTasks() {
        LocalDate today = LocalDate.of(2025, 6, 15);

        Task overdue = repository.save(new Task("Overdue", TaskPriority.HIGH, LocalDate.of(2025, 6, 10)));
        Task notOverdue = repository.save(new Task("Future", TaskPriority.LOW, LocalDate.of(2025, 6, 20)));
        Task completedOverdue = repository.save(new Task("Done Overdue", TaskPriority.MEDIUM, LocalDate.of(2025, 6, 5)));
        completedOverdue.setStatus(Task.Status.COMPLETED);

        List<Task> overdueTasks = repository.findOverdue(today);
        assertEquals(1, overdueTasks.size());
        assertEquals("Overdue", overdueTasks.get(0).getTitle());
    }

    @Test
    void deleteByIdRemovesTask() {
        Task task = repository.save(new Task("To delete", TaskPriority.LOW, LocalDate.now().plusDays(1)));
        boolean deleted = repository.deleteById(task.getId());

        assertTrue(deleted);
        assertTrue(repository.findById(task.getId()).isEmpty());
        assertEquals(0, repository.count());
    }

    @Test
    void deleteByIdReturnsFalseForNonExistentId() {
        boolean deleted = repository.deleteById("non-existent");
        assertFalse(deleted);
    }

    @Test
    void clearRemovesAllTasks() {
        repository.save(new Task("Task 1", TaskPriority.LOW, LocalDate.now().plusDays(1)));
        repository.save(new Task("Task 2", TaskPriority.HIGH, LocalDate.now().plusDays(2)));
        repository.clear();

        assertEquals(0, repository.count());
        assertTrue(repository.findAll().isEmpty());
    }
}
