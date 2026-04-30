package io.kairo.code.cli.commands;

import io.kairo.api.memory.MemoryEntry;
import io.kairo.api.memory.MemoryScope;
import io.kairo.api.memory.MemoryStore;
import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import java.io.PrintWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Manual memory management command for the REPL.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@code :memory list [scope]} — list memories (optional: session/agent/global)</li>
 *   <li>{@code :memory search <query>} — search memories by keyword</li>
 *   <li>{@code :memory add <content>} — manually add a memory (scope=AGENT, importance=0.8)</li>
 *   <li>{@code :memory delete <id>} — delete a memory by ID</li>
 *   <li>{@code :memory info <id>} — show full memory details</li>
 *   <li>{@code :memory clear [scope]} — clear all memories in scope (with confirmation)</li>
 * </ul>
 */
public class MemoryCommand implements SlashCommand {

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public String description() {
        return "Manage persistent memories (list / search / add / delete / info / clear)";
    }

    @Override
    public void execute(String args, ReplContext context) {
        PrintWriter writer = context.writer();
        MemoryStore store = context.memoryStore();
        if (store == null) {
            writer.println("Memory unavailable: no memory store configured.");
            writer.flush();
            return;
        }

        String trimmed = args == null ? "" : args.strip();
        if (trimmed.isEmpty()) {
            printUsage(writer);
            return;
        }

        String[] parts = trimmed.split("\\s+", 2);
        String sub = parts[0].toLowerCase();
        String rest = parts.length > 1 ? parts[1].strip() : "";

        switch (sub) {
            case "list" -> handleList(rest, store, writer);
            case "search" -> handleSearch(rest, store, writer);
            case "add" -> handleAdd(rest, store, writer);
            case "delete" -> handleDelete(rest, store, writer);
            case "info" -> handleInfo(rest, store, writer);
            case "clear" -> handleClear(rest, store, context, writer);
            default -> printUsage(writer);
        }
    }

    private void handleList(String scopeFilter, MemoryStore store, PrintWriter writer) {
        List<MemoryEntry> entries;
        if (!scopeFilter.isBlank()) {
            MemoryScope scope = parseScope(scopeFilter);
            if (scope == null) {
                writer.println("Unknown scope: " + scopeFilter
                        + ". Valid scopes: session, agent, global");
                writer.flush();
                return;
            }
            entries = store.list(scope).collectList().block();
        } else {
            // List all scopes
            entries = new java.util.ArrayList<>();
            for (MemoryScope s : MemoryScope.values()) {
                List<MemoryEntry> scoped = store.list(s).collectList().block();
                if (scoped != null) {
                    entries.addAll(scoped);
                }
            }
        }

        if (entries == null || entries.isEmpty()) {
            writer.println("No memories found.");
            writer.flush();
            return;
        }

        writer.println();
        writer.printf("  %-12s  %-6s  %-8s  %-20s  %s%n",
                "ID", "Imp.", "Scope", "Tags", "Content");
        writer.println("  " + "─".repeat(74));
        for (MemoryEntry entry : entries) {
            String id = truncate(entry.id(), 10);
            String imp = String.format("%.1f", entry.importance());
            String scope = entry.scope() != null ? entry.scope().name().toLowerCase() : "?";
            String tags = entry.tags() != null && !entry.tags().isEmpty()
                    ? String.join(",", entry.tags()) : "";
            tags = truncate(tags, 18);
            String content = truncate(singleLine(entry.content()), 30);
            String age = formatAge(entry.timestamp());
            writer.printf("  %-12s  %-6s  %-8s  %-20s  %s  [%s]%n",
                    id, imp, scope, tags, content, age);
        }
        writer.printf("%n  Total: %d memories%n", entries.size());
        writer.flush();
    }

    private void handleSearch(String query, MemoryStore store, PrintWriter writer) {
        if (query.isBlank()) {
            writer.println("Usage: :memory search <query>");
            writer.flush();
            return;
        }

        List<MemoryEntry> results = store.search(query, MemoryScope.AGENT).collectList().block();
        if (results == null || results.isEmpty()) {
            writer.println("No memories matching: " + query);
            writer.flush();
            return;
        }

        writer.println();
        writer.printf("  Found %d result(s) for \"%s\":%n", results.size(), query);
        writer.println();
        for (MemoryEntry entry : results) {
            String id = truncate(entry.id(), 10);
            String content = truncate(singleLine(entry.content()), 50);
            writer.printf("  %-12s  %.1f  %s%n", id, entry.importance(), content);
        }
        writer.println();
        writer.flush();
    }

    private void handleAdd(String content, MemoryStore store, PrintWriter writer) {
        if (content.isBlank()) {
            writer.println("Usage: :memory add <content>");
            writer.flush();
            return;
        }

        String id = UUID.randomUUID().toString().substring(0, 8);
        MemoryEntry entry = new MemoryEntry(
                id,
                "kairo-code",
                content,
                null,
                MemoryScope.AGENT,
                0.8,
                null,
                Set.of("manual"),
                Instant.now(),
                null);
        store.save(entry).block();
        writer.println("✓ Memory saved: " + id);
        writer.flush();
    }

    private void handleDelete(String id, MemoryStore store, PrintWriter writer) {
        if (id.isBlank()) {
            writer.println("Usage: :memory delete <id>");
            writer.flush();
            return;
        }

        MemoryEntry existing = store.get(id).block();
        if (existing == null) {
            writer.println("Memory not found: " + id);
            writer.flush();
            return;
        }

        store.delete(id).block();
        writer.println("✓ Deleted memory: " + id);
        writer.flush();
    }

    private void handleInfo(String id, MemoryStore store, PrintWriter writer) {
        if (id.isBlank()) {
            writer.println("Usage: :memory info <id>");
            writer.flush();
            return;
        }

        MemoryEntry entry = store.get(id).block();
        if (entry == null) {
            writer.println("Memory not found: " + id);
            writer.flush();
            return;
        }

        writer.println();
        writer.println("Memory: " + entry.id());
        writer.println("  Agent ID:    " + (entry.agentId() != null ? entry.agentId() : "-"));
        writer.println("  Scope:       " + (entry.scope() != null ? entry.scope() : "-"));
        writer.println("  Importance:  " + entry.importance());
        writer.println("  Tags:        "
                + (entry.tags() != null && !entry.tags().isEmpty()
                        ? String.join(", ", entry.tags()) : "-"));
        writer.println("  Timestamp:   " + (entry.timestamp() != null ? entry.timestamp() : "-"));
        writer.println("  Content:     " + (entry.content() != null ? entry.content() : "-"));
        if (entry.rawContent() != null) {
            writer.println("  Raw Content: " + entry.rawContent());
        }
        if (entry.metadata() != null && !entry.metadata().isEmpty()) {
            writer.println("  Metadata:    " + entry.metadata());
        }
        writer.println();
        writer.flush();
    }

    private void handleClear(String scopeFilter, MemoryStore store,
            ReplContext context, PrintWriter writer) {
        MemoryScope scope;
        if (scopeFilter.isBlank()) {
            scope = MemoryScope.AGENT;
        } else {
            scope = parseScope(scopeFilter);
            if (scope == null) {
                writer.println("Unknown scope: " + scopeFilter
                        + ". Valid scopes: session, agent, global");
                writer.flush();
                return;
            }
        }

        List<MemoryEntry> entries = store.list(scope).collectList().block();
        if (entries == null || entries.isEmpty()) {
            writer.println("No memories to clear in scope: " + scope.name().toLowerCase());
            writer.flush();
            return;
        }

        // Confirm with user
        writer.printf("Clear %d memories in scope '%s'? (y/N) ",
                entries.size(), scope.name().toLowerCase());
        writer.flush();

        String confirmation = null;
        if (context.lineReader() != null) {
            try {
                confirmation = context.lineReader().readLine();
            } catch (Exception e) {
                // If we can't read input, abort
            }
        }

        if (confirmation == null || !confirmation.strip().equalsIgnoreCase("y")) {
            writer.println("Cancelled.");
            writer.flush();
            return;
        }

        for (MemoryEntry entry : entries) {
            store.delete(entry.id()).block();
        }
        writer.println("✓ Cleared " + entries.size() + " memories.");
        writer.flush();
    }

    private void printUsage(PrintWriter writer) {
        writer.println("Usage: :memory <list [scope]|search <query>|add <content>"
                + "|delete <id>|info <id>|clear [scope]>");
        writer.flush();
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private static MemoryScope parseScope(String input) {
        try {
            return MemoryScope.valueOf(input.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() > max ? value.substring(0, max - 3) + "..." : value;
    }

    private static String singleLine(String content) {
        if (content == null) return "";
        return content.replace('\n', ' ').replace('\r', ' ');
    }

    private static String formatAge(Instant timestamp) {
        if (timestamp == null) return "?";
        Duration age = Duration.between(timestamp, Instant.now());
        long seconds = age.getSeconds();
        if (seconds < 0) return "future";
        if (seconds < 60) return seconds + "s ago";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        long days = hours / 24;
        return days + "d ago";
    }
}
