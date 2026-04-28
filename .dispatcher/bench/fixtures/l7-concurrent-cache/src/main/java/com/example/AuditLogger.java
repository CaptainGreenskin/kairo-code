package com.example;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Audit logger that records operations performed on users.
 *
 * Bug 5: Uses plain ArrayList without synchronization — concurrent add() calls
 * from multiple threads can corrupt ArrayList's internal array (ArrayIndexOutOfBoundsException
 * or lost entries).
 */
public class AuditLogger {

    private final List<String> logs = new ArrayList<>();

    public void log(String operation, String userId) {
        String entry = "[%s] %s user %s".formatted(
                java.time.Instant.now().toString(), operation, userId);
        logs.add(entry);
    }

    public List<String> getLogs() {
        return Collections.unmodifiableList(logs);
    }

    public int count() {
        return logs.size();
    }

    public void clear() {
        logs.clear();
    }
}
