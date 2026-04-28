package com.example;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * User service with CRUD, search, and concurrent access support.
 *
 * Bug 3: searchByName uses O(n) linear scan over all users — should maintain
 * a sorted index (e.g., TreeMap) for O(log n) prefix queries.
 *
 * Bug 4: getOrCreate uses non-atomic check-then-act pattern —
 * if (!users.containsKey(id)) { users.put(id, factory.get()); }
 * Multiple threads can create duplicates. Should use computeIfAbsent.
 */
public class UserService {

    private final Map<String, User> users = new HashMap<>();
    private final AuditLogger auditLogger;

    public UserService(AuditLogger auditLogger) {
        this.auditLogger = auditLogger;
    }

    public void addUser(User user) {
        users.put(user.getId(), user);
        auditLogger.log("ADD", user.getId());
    }

    public User getUser(String id) {
        return users.get(id);
    }

    public User removeUser(String id) {
        User removed = users.remove(id);
        if (removed != null) {
            auditLogger.log("REMOVE", id);
        }
        return removed;
    }

    public User updateUser(User user) {
        if (!users.containsKey(user.getId())) {
            throw new IllegalArgumentException("User not found: " + user.getId());
        }
        users.put(user.getId(), user);
        auditLogger.log("UPDATE", user.getId());
        return user;
    }

    /**
     * Bug 3: O(n) linear scan — no index on name.
     */
    public List<User> searchByName(String prefix) {
        return users.values().stream()
                .filter(u -> u.getName().startsWith(prefix))
                .collect(Collectors.toList());
    }

    public List<User> searchByRole(User.Role role) {
        return users.values().stream()
                .filter(u -> u.getRole() == role)
                .collect(Collectors.toList());
    }

    /**
     * Bug 4: Non-atomic check-then-act — concurrent callers may create duplicates.
     */
    public User getOrCreate(String id, Supplier<User> factory) {
        if (!users.containsKey(id)) {
            users.put(id, factory.get());
        }
        return users.get(id);
    }

    public int count() {
        return users.size();
    }

    public List<String> getAuditLog() {
        return new ArrayList<>(auditLogger.getLogs());
    }
}
