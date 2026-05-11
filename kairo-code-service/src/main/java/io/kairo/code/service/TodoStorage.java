package io.kairo.code.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads the agent's todo list snapshot from {@code <workingDir>/.kairo/todos.json}.
 *
 * <p>Mirrors the storage path used by {@code io.kairo.tools.agent.TodoWriteTool}. We re-read the
 * file from the bridge hook on every {@code todo_write} / {@code todo_read} so the UI snapshot is
 * always grounded in the on-disk state, regardless of what string the tool returned to the model.
 */
final class TodoStorage {

    private static final Logger log = LoggerFactory.getLogger(TodoStorage.class);
    private static final String RELATIVE_PATH = ".kairo/todos.json";

    private TodoStorage() {}

    /**
     * Returns the raw JSON array string from the todos file, or {@code "[]"} when the file does
     * not exist or cannot be read. Never returns {@code null}.
     */
    static String readJson(String workingDir) {
        if (workingDir == null || workingDir.isBlank()) {
            return "[]";
        }
        Path file = Path.of(workingDir).resolve(RELATIVE_PATH);
        if (!Files.exists(file)) {
            return "[]";
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8).trim();
            return content.isEmpty() ? "[]" : content;
        } catch (IOException e) {
            log.warn("Failed to read todos at {}: {}", file, e.getMessage());
            return "[]";
        }
    }
}
