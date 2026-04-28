package com.example;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory LRU cache with a maximum capacity.
 *
 * Bug 1: Uses plain LinkedHashMap without synchronization — concurrent get/put
 * from multiple threads causes race conditions (ConcurrentModificationException
 * or lost updates).
 *
 * Bug 2: size() check in put() is not atomic with the actual put — TOCTOU
 * race condition where multiple threads can exceed capacity before eviction kicks in.
 */
public class UserCache {

    private final int capacity;
    private final Map<String, User> cache;

    public UserCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.capacity = capacity;
        this.cache = new LinkedHashMap<>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, User> eldest) {
                return size() > capacity;
            }
        };
    }

    public User get(String id) {
        return cache.get(id);
    }

    public void put(String id, User user) {
        // Bug 2: non-atomic check-then-act — TOCTOU race
        if (cache.size() < capacity) {
            cache.put(id, user);
        } else {
            cache.put(id, user);
        }
    }

    public User remove(String id) {
        return cache.remove(id);
    }

    public boolean containsKey(String id) {
        return cache.containsKey(id);
    }

    public int size() {
        return cache.size();
    }

    public int getCapacity() {
        return capacity;
    }

    public void clear() {
        cache.clear();
    }
}
