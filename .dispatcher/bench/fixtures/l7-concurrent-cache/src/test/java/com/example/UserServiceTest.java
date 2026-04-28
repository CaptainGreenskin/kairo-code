package com.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UserServiceTest {

    private UserService service;
    private AuditLogger logger;

    @BeforeEach
    void setUp() {
        logger = new AuditLogger();
        service = new UserService(logger);
    }

    @Test
    void addUserStoresUser() {
        User user = new User("1", "Alice", "alice@example.com", User.Role.USER);
        service.addUser(user);
        assertSame(user, service.getUser("1"));
    }

    @Test
    void addUserLogsAudit() {
        User user = new User("1", "Alice", "alice@example.com", User.Role.USER);
        service.addUser(user);
        assertEquals(1, logger.count());
    }

    @Test
    void getUserReturnsNullForMissing() {
        assertNull(service.getUser("nonexistent"));
    }

    @Test
    void removeUserDeletesAndLogs() {
        User user = new User("1", "Alice", "alice@example.com", User.Role.USER);
        service.addUser(user);
        User removed = service.removeUser("1");
        assertSame(user, removed);
        assertNull(service.getUser("1"));
        assertEquals(2, logger.count()); // ADD + REMOVE
    }

    @Test
    void removeMissingUserReturnsNull() {
        assertNull(service.removeUser("nonexistent"));
    }

    @Test
    void updateUserModifiesUser() {
        User original = new User("1", "Alice", "alice@example.com", User.Role.USER);
        service.addUser(original);
        User updated = new User("1", "Alice Smith", "alice.smith@example.com", User.Role.ADMIN);
        service.updateUser(updated);
        assertSame(updated, service.getUser("1"));
    }

    @Test
    void updateMissingUserThrows() {
        User user = new User("1", "Alice", "alice@example.com", User.Role.USER);
        assertThrows(IllegalArgumentException.class, () -> service.updateUser(user));
    }

    @Test
    void searchByNameReturnsMatchingUsers() {
        service.addUser(new User("1", "Alice", "alice@example.com", User.Role.USER));
        service.addUser(new User("2", "Bob", "bob@example.com", User.Role.USER));
        service.addUser(new User("3", "Alicia", "alicia@example.com", User.Role.USER));
        List<User> results = service.searchByName("Ali");
        assertEquals(2, results.size());
    }

    @Test
    void searchByNameReturnsEmptyForNoMatch() {
        service.addUser(new User("1", "Alice", "alice@example.com", User.Role.USER));
        List<User> results = service.searchByName("Zoe");
        assertTrue(results.isEmpty());
    }

    @Test
    void searchByRoleReturnsMatchingUsers() {
        service.addUser(new User("1", "Alice", "alice@example.com", User.Role.ADMIN));
        service.addUser(new User("2", "Bob", "bob@example.com", User.Role.USER));
        service.addUser(new User("3", "Carol", "carol@example.com", User.Role.ADMIN));
        List<User> results = service.searchByRole(User.Role.ADMIN);
        assertEquals(2, results.size());
    }

    @Test
    void getOrCreateReturnsExistingUser() {
        User user = new User("1", "Alice", "alice@example.com", User.Role.USER);
        service.addUser(user);
        User result = service.getOrCreate("1", () -> new User("1", "New", "new@example.com", User.Role.GUEST));
        assertSame(user, result);
    }

    @Test
    void getOrCreateCreatesNewUser() {
        User result = service.getOrCreate("1", () -> new User("1", "Alice", "alice@example.com", User.Role.USER));
        assertNotNull(result);
        assertEquals("Alice", result.getName());
        assertEquals(1, service.count());
    }

    @Test
    void countReturnsNumberOfUsers() {
        service.addUser(new User("1", "Alice", "alice@example.com", User.Role.USER));
        service.addUser(new User("2", "Bob", "bob@example.com", User.Role.USER));
        assertEquals(2, service.count());
    }

    @Test
    void getAuditLogReturnsAllEntries() {
        service.addUser(new User("1", "Alice", "alice@example.com", User.Role.USER));
        service.addUser(new User("2", "Bob", "bob@example.com", User.Role.USER));
        service.removeUser("1");
        List<String> logs = service.getAuditLog();
        assertEquals(3, logs.size());
    }

    @Test
    void getAuditLogReturnsCopyNotInternalReference() {
        service.addUser(new User("1", "Alice", "alice@example.com", User.Role.USER));
        List<String> log1 = service.getAuditLog();
        List<String> log2 = service.getAuditLog();
        assertNotSame(log1, log2);
    }
}
