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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;

/**
 * Unified memory_read that reads from {@link MemoryStore}.
 */
@Tool(
        name = "memory_read",
        description =
                "Read saved memories. Without args returns all memories."
                        + " With 'query' searches for relevant memories by keyword.",
        category = ToolCategory.AGENT_AND_TASK)
public class UnifiedMemoryReadTool implements SyncTool {

    @ToolParam(description = "Search query to find relevant memories")
    private String query;

    private final MemoryStore memoryStore;

    public UnifiedMemoryReadTool(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(args));
    }

    private ToolResult doExecute(Map<String, Object> args) {
        String queryVal = (String) args.get("query");

        try {
            List<MemoryEntry> entries;
            if (queryVal != null && !queryVal.isBlank()) {
                entries = memoryStore.search(queryVal, MemoryScope.AGENT)
                        .collectList().block();
            } else {
                entries = memoryStore.list(MemoryScope.AGENT)
                        .collectList().block();
            }

            if (entries == null || entries.isEmpty()) {
                return ToolResult.success("memory_read", "No memories found.", Map.of());
            }

            String formatted = entries.stream()
                    .map(e -> "- [" + e.id() + "] " + e.content())
                    .collect(Collectors.joining("\n"));

            return ToolResult.success("memory_read",
                    entries.size() + " memory(s):\n" + formatted,
                    Map.of("count", entries.size()));
        } catch (Exception e) {
            return ToolResult.error("memory_read", "Failed: " + e.getMessage());
        }
    }
}
