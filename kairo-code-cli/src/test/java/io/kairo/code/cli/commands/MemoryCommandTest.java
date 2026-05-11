package io.kairo.code.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.memory.MemoryEntry;
import io.kairo.api.memory.MemoryScope;
import io.kairo.api.memory.MemoryStore;
import io.kairo.code.cli.CommandRegistry;
import io.kairo.code.cli.ReplContext;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.core.memory.InMemoryMemoryStore;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MemoryCommandTest {

    private MemoryCommand command;
    private MemoryStore store;
    private CommandRegistry registry;
    private StringWriter outputCapture;
    private PrintWriter writer;
    private CodeAgentConfig config;

    @BeforeEach
    void setUp() {
        command = new MemoryCommand();
        store = new InMemoryMemoryStore();
        registry = new CommandRegistry();
        outputCapture = new StringWriter();
        writer = new PrintWriter(outputCapture, true);
        config = new CodeAgentConfig(
                "test-key", "https://api.test.com", "gpt-4o", 50, "/tmp", null, 0, 0, null);
    }

    @Test
    void listEmptyMemories() {
        ReplContext context = createContext();
        command.execute("list", context);
        assertThat(outputCapture.toString()).contains("No memories found.");
    }

    @Test
    void listWithMemoriesPresent() {
        MemoryEntry entry = new MemoryEntry(
                "test-1", "kairo-code", "Remember to use tabs",
                null, MemoryScope.AGENT, 0.7, null,
                Set.of("style"), Instant.now(), null);
        store.save(entry).block();

        ReplContext context = createContext();
        command.execute("list", context);

        String output = outputCapture.toString();
        assertThat(output).contains("test-1");
        assertThat(output).contains("Remember to use tabs");
        assertThat(output).contains("Total: 1 memories");
    }

    @Test
    void listFilteredByScope() {
        store.save(new MemoryEntry(
                "agent-1", "kairo-code", "agent memory",
                null, MemoryScope.AGENT, 0.5, null,
                Set.of(), Instant.now(), null)).block();
        store.save(new MemoryEntry(
                "session-1", null, "session memory",
                null, MemoryScope.SESSION, 0.5, null,
                Set.of(), Instant.now(), null)).block();

        ReplContext context = createContext();
        command.execute("list session", context);

        String output = outputCapture.toString();
        assertThat(output).contains("session-1");
        assertThat(output).doesNotContain("agent-1");
    }

    @Test
    void searchWithResults() {
        store.save(new MemoryEntry(
                "s-1", "kairo-code", "always use strict mode",
                null, MemoryScope.AGENT, 0.6, null,
                Set.of(), Instant.now(), null)).block();

        ReplContext context = createContext();
        command.execute("search strict", context);

        String output = outputCapture.toString();
        assertThat(output).contains("s-1");
        assertThat(output).contains("strict");
    }

    @Test
    void searchWithNoResults() {
        ReplContext context = createContext();
        command.execute("search nonexistent", context);
        assertThat(outputCapture.toString()).contains("No memories matching");
    }

    @Test
    void addMemory() {
        ReplContext context = createContext();
        command.execute("add Always prefer immutable collections", context);

        String output = outputCapture.toString();
        assertThat(output).contains("Memory saved:");

        // Verify it was actually stored
        var entries = store.list(MemoryScope.AGENT).collectList().block();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).content()).isEqualTo("Always prefer immutable collections");
        assertThat(entries.get(0).importance()).isEqualTo(0.8);
        assertThat(entries.get(0).tags()).contains("manual");
    }

    @Test
    void deleteMemory() {
        store.save(new MemoryEntry(
                "del-1", "kairo-code", "to be deleted",
                null, MemoryScope.AGENT, 0.5, null,
                Set.of(), Instant.now(), null)).block();

        ReplContext context = createContext();
        command.execute("delete del-1", context);

        String output = outputCapture.toString();
        assertThat(output).contains("Deleted memory: del-1");

        MemoryEntry deleted = store.get("del-1").block();
        assertThat(deleted).isNull();
    }

    @Test
    void deleteNonExistentMemory() {
        ReplContext context = createContext();
        command.execute("delete no-such-id", context);
        assertThat(outputCapture.toString()).contains("Memory not found: no-such-id");
    }

    @Test
    void infoShowsAllFields() {
        MemoryEntry entry = new MemoryEntry(
                "info-1", "kairo-code", "detailed content", "raw original",
                MemoryScope.AGENT, 0.9, null,
                Set.of("important", "manual"), Instant.now(),
                java.util.Map.of("source", "test"));
        store.save(entry).block();

        ReplContext context = createContext();
        command.execute("info info-1", context);

        String output = outputCapture.toString();
        assertThat(output).contains("Memory: info-1");
        assertThat(output).contains("kairo-code");
        assertThat(output).contains("AGENT");
        assertThat(output).contains("0.9");
        assertThat(output).contains("detailed content");
        assertThat(output).contains("raw original");
        assertThat(output).contains("source");
    }

    @Test
    void helpSubcommand() {
        ReplContext context = createContext();
        command.execute("", context);
        String output = outputCapture.toString();
        assertThat(output).contains("Usage:");
        assertThat(output).contains("list");
        assertThat(output).contains("search");
        assertThat(output).contains("add");
        assertThat(output).contains("delete");
    }

    @Test
    void addThenListShowsNewEntry() {
        ReplContext context = createContext();
        command.execute("add Test memory content here", context);

        outputCapture.getBuffer().setLength(0); // reset output
        command.execute("list agent", context);

        String output = outputCapture.toString();
        assertThat(output).contains("Test memory content");
        assertThat(output).contains("Total: 1 memories");
    }

    @Test
    void noStoreShowsUnavailable() {
        // Context with null memory store
        ReplContext context = new ReplContext(
                null, config, null, registry, writer, null, null, null, null);
        command.execute("list", context);
        assertThat(outputCapture.toString()).contains("Memory unavailable");
    }

    @Test
    void listInvalidScope() {
        ReplContext context = createContext();
        command.execute("list invalid", context);
        assertThat(outputCapture.toString()).contains("Unknown scope: invalid");
    }

    @Test
    void infoNotFound() {
        ReplContext context = createContext();
        command.execute("info missing-id", context);
        assertThat(outputCapture.toString()).contains("Memory not found: missing-id");
    }

    @Test
    void searchMissingQuery() {
        ReplContext context = createContext();
        command.execute("search", context);
        assertThat(outputCapture.toString()).contains("Usage: :memory search");
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private ReplContext createContext() {
        return new ReplContext(
                null, config, null, registry, writer, null, null, null, null,
                null, java.util.Map.of(), store);
    }
}
