package io.kairo.code.core.memory;

import io.kairo.api.memory.MemoryEntry;
import io.kairo.api.memory.MemoryScope;
import io.kairo.api.memory.MemoryStore;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolParam;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import reactor.core.publisher.Mono;

/**
 * Unified memory_write that saves directly to {@link MemoryStore},
 * ensuring memories are injected into future conversations via
 * {@code SessionMemoryEnricher}.
 */
@Tool(
        name = "memory_write",
        description =
                "Save a memory that persists across conversations. Use this proactively when"
                        + " the user states a preference, you learn a project fact, or the user"
                        + " corrects your behavior. Types: USER (preferences), FEEDBACK (corrections),"
                        + " PROJECT (tech stack, conventions), REFERENCE (URLs, docs).",
        category = ToolCategory.AGENT_AND_TASK,
        sideEffect = ToolSideEffect.WRITE)
public class UnifiedMemoryWriteTool implements SyncTool {

    @ToolParam(description = "Kebab-case slug (e.g. 'user-prefers-tailwind')", required = true)
    private String name;

    @ToolParam(description = "One-line summary", required = true)
    private String description;

    @ToolParam(description = "Memory type: USER, FEEDBACK, PROJECT, or REFERENCE", required = true)
    private String type;

    @ToolParam(description = "Detailed content of the memory", required = true)
    private String content;

    private final MemoryStore memoryStore;

    public UnifiedMemoryWriteTool(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(args));
    }

    private ToolResult doExecute(Map<String, Object> args) {
        String nameVal = (String) args.get("name");
        String descVal = (String) args.get("description");
        String typeVal = (String) args.get("type");
        String contentVal = (String) args.get("content");

        if (nameVal == null || nameVal.isBlank())
            return ToolResult.error("memory_write", "'name' is required");
        if (contentVal == null || contentVal.isBlank())
            return ToolResult.error("memory_write", "'content' is required");

        String normalizedType = typeVal != null ? typeVal.toUpperCase() : "USER";
        Set<String> tags = Set.of("structured", normalizedType.toLowerCase());

        MemoryEntry entry = new MemoryEntry(
                nameVal,
                "kairo-code",
                (descVal != null && !descVal.isBlank() ? descVal + "\n\n" : "") + contentVal,
                null,
                MemoryScope.AGENT,
                0.8,
                null,
                tags,
                Instant.now(),
                Map.of("type", normalizedType, "source", "memory_write"));

        try {
            MemoryEntry saved = memoryStore.save(entry).block();
            String action = saved != null ? "Saved" : "Failed";
            return ToolResult.success("memory_write",
                    action + " memory '" + nameVal + "' [" + normalizedType + "]",
                    Map.of("name", nameVal, "type", normalizedType));
        } catch (Exception e) {
            return ToolResult.error("memory_write", "Failed: " + e.getMessage());
        }
    }
}
