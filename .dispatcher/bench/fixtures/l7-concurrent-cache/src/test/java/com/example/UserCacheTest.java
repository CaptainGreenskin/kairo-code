package com.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UserCacheTest {

    private UserCache cache;

    @BeforeEach
    void setUp() {
        cache = new UserCache(3);
    }

    @Test
    void putAndGetUser() {
        User user = new User("1", "Alice", "alice@example.com", User.Role.USER);
        cache.put("1", user);
        assertSame(user, cache.get("1"));
    }

    @Test
    void getMissingUserReturnsNull() {
        assertNull(cache.get("nonexistent"));
    }

    @Test
    void removeUser() {
        User user = new User("1", "Alice", "alice@example.com", User.Role.USER);
        cache.put("1", user);
        User removed = cache.remove("1");
        assertSame(user, removed);
        assertNull(cache.get("1"));
    }

    @Test
    void removeMissingUserReturnsNull() {
        assertNull(cache.remove("nonexistent"));
    }

    @Test
    void containsKeyReturnsTrueForExistingUser() {
        User user = new User("1", "Alice", "alice@example.com", User.Role.USER);
        cache.put("1", user);
        assertTrue(cache.containsKey("1"));
    }

    @Test
    void containsKeyReturnsFalseForMissingUser() {
        assertFalse(cache.containsKey("nonexistent"));
    }

    @Test
    void sizeReflectsNumberOfEntries() {
        cache.put("1", new User("1", "Alice", "alice@example.com", User.Role.USER));
        cache.put("2", new User("2", "Bob", "bob@example.com", User.Role.USER));
        assertEquals(2, cache.size());
    }

    @Test
    void lruEvictsOldestEntry() {
        cache.put("1", new User("1", "Alice", "alice@example.com", User.Role.USER));
        cache.put("2", new User("2", "Bob", "bob@example.com", User.Role.USER));
        cache.put("3", new User("3", "Carol", "carol@example.com", User.Role.USER));
        // Access "1" to make it recently used
        cache.get("1");
        // Add a fourth entry — should evict "2" (least recently used)
        cache.put("4", new User("4", "Dave", "dave@example.com", User.Role.USER));
        assertNull(cache.get("2"));
        assertNotNull(cache.get("1"));
        assertNotNull(cache.get("3"));
        assertNotNull(cache.get("4"));
    }

    @Test
    void clearRemovesAllEntries() {
        cache.put("1", new User("1", "Alice", "alice@example.com", User.Role.USER));
        cache.put("2", new User("2", "Bob", "bob@example.com", User.Role.USER));
        cache.clear();
        assertEquals(0, cache.size());
        assertNull(cache.get("1"));
    }

    @Test
    void getUpdatesAccessOrderForLru() {
        cache.put("1", new User("1", "Alice", "alice@example.com", User.Role.USER));
        cache.put("2", new User("2", "Bob", "bob@example.com", User.Role.USER));
        cache.put("3", new User("3", "Carol", "carol@example.com", User.Role.USER));
        // Access "1" to make it recently used
        cache.get("1");
        // Add fourth — should evict "2" not "1"
        cache.put("4", new User("4", "Dave", "dave@example.com", User.Role.USER));
        assertNull(cache.get("2"));
        assertNotNull(cache.get("1"));
    }
}
