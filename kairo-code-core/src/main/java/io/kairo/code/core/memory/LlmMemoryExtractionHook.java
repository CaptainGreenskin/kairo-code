package io.kairo.code.core.memory;

import io.kairo.api.hook.HookHandler;
import io.kairo.api.hook.HookPhase;
import io.kairo.api.hook.HookResult;
import io.kairo.api.hook.PreCompleteEvent;
import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.memory.MemoryEntry;
import io.kairo.api.memory.MemoryScope;
import io.kairo.api.memory.MemoryStore;
import io.kairo.api.model.ModelConfig;
import io.kairo.api.model.ModelProvider;
import io.kairo.api.model.ModelResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM-based memory extraction hook. Runs at session end (PRE_COMPLETE phase) to intelligently
 * extract durable memories from the conversation using a model call.
 *
 * <p>Replaces the heuristic regex approach of {@link AutoMemoryHook} with a forked LLM call that
 * can capture implicit preferences, design decisions, and non-obvious learnings that regex patterns
 * miss.
 *
 * <p>Design follows Claude Code's extractMemories pattern:
 * <ol>
 *   <li>Collect conversation summary (last N messages)
 *   <li>Load existing memory manifest to avoid duplicates
 *   <li>Call LLM with extraction prompt
 *   <li>Parse structured output into MemoryFile entries
 *   <li>Write to MemoryDirectoryManager (which auto-regenerates MEMORY.md)
 * </ol>
 */
public class LlmMemoryExtractionHook {

    private static final Logger log = LoggerFactory.getLogger(LlmMemoryExtractionHook.class);
    private static final int MAX_CONVERSATION_CHARS = 12000;
    private static final int MIN_MESSAGES_FOR_EXTRACTION = 4;

    private static final Pattern MEMORY_BLOCK_PATTERN = Pattern.compile(
            "```memory\\s*\\n(.*?)\\n```", Pattern.DOTALL);
    private static final Pattern FIELD_PATTERN = Pattern.compile(
            "^(name|type|description|content):\\s*(.+)$", Pattern.MULTILINE);

    private static final String EXTRACTION_PROMPT = """
            You are a memory extraction agent. Analyze the conversation below and extract 0-3 durable memories worth persisting across sessions.

            ## Memory Types
            - **user**: User's role, preferences, expertise, how they want to collaborate
            - **feedback**: Corrections or confirmed approaches (include WHY)
            - **project**: Ongoing work context, decisions, deadlines (convert relative dates to absolute)
            - **reference**: Pointers to external systems, URLs, tool locations

            ## What NOT to save
            - Code patterns derivable from reading the current codebase
            - Git history / recent changes (git log is authoritative)
            - Anything already in CLAUDE.md / KAIRO.md
            - Ephemeral task details only useful in this session
            - Debugging solutions (the fix is in the code)

            ## Existing memories (do not duplicate)
            %s

            ## Conversation to analyze
            %s

            ## Output format
            For each memory to save, output a fenced block:
            ```memory
            name: kebab-case-slug
            type: user|feedback|project|reference
            description: one-line summary for the index
            content: the memory body (1-3 sentences, include WHY context)
            ```

            If nothing worth remembering, output: NO_MEMORIES
            """;

    private final ModelProvider modelProvider;
    private final MemoryStore memoryStore;
    private final String modelName;
    private final String agentId;

    public LlmMemoryExtractionHook(
            ModelProvider modelProvider,
            MemoryStore memoryStore,
            String modelName) {
        this(modelProvider, memoryStore, modelName, "kairo-code");
    }

    public LlmMemoryExtractionHook(
            ModelProvider modelProvider,
            MemoryStore memoryStore,
            String modelName,
            String agentId) {
        this.modelProvider = modelProvider;
        this.memoryStore = memoryStore;
        this.modelName = modelName;
        this.agentId = agentId;
    }

    @HookHandler(HookPhase.PRE_COMPLETE)
    public HookResult<PreCompleteEvent> onPreComplete(PreCompleteEvent event) {
        List<Msg> history = event.conversationHistory();
        if (history == null || history.size() < MIN_MESSAGES_FOR_EXTRACTION) {
            return HookResult.proceed(event);
        }

        try {
            extractAndSave(history);
        } catch (Exception e) {
            log.debug("LLM memory extraction failed: {}", e.getMessage());
        }

        return HookResult.proceed(event);
    }

    private void extractAndSave(List<Msg> history) {
        String conversationSummary = buildConversationSummary(history);
        String existingManifest = loadExistingMemoryManifest();

        String prompt = String.format(EXTRACTION_PROMPT,
                existingManifest.isBlank() ? "(none)" : existingManifest,
                conversationSummary);

        List<Msg> messages = List.of(Msg.of(MsgRole.USER, prompt));
        ModelConfig config = ModelConfig.builder()
                .model(modelName)
                .maxTokens(1500)
                .temperature(0.0)
                .build();

        ModelResponse response = modelProvider.call(messages, config)
                .block(java.time.Duration.ofSeconds(30));

        if (response == null || response.contents() == null) {
            return;
        }

        String responseText = response.contents().stream()
                .filter(Content.TextContent.class::isInstance)
                .map(c -> ((Content.TextContent) c).text())
                .reduce("", String::concat);

        if (responseText.contains("NO_MEMORIES")) {
            log.debug("LLM memory extraction: nothing worth remembering");
            return;
        }

        List<ExtractedMemory> extracted = parseMemoryBlocks(responseText);
        for (ExtractedMemory mem : extracted) {
            try {
                MemoryEntry entry = buildEntry(mem);
                memoryStore.save(entry).block(java.time.Duration.ofSeconds(5));
                log.info("LLM extracted memory: {} ({}) → scope={}", mem.name, mem.type,
                        entry.scope());
            } catch (Exception e) {
                log.warn("Failed to save extracted memory '{}': {}", mem.name, e.getMessage());
            }
        }
    }

    private String loadExistingMemoryManifest() {
        try {
            var agentEntries = memoryStore.list(MemoryScope.AGENT)
                    .collectList().block(java.time.Duration.ofSeconds(3));
            var globalEntries = memoryStore.list(MemoryScope.GLOBAL)
                    .collectList().block(java.time.Duration.ofSeconds(3));
            var entries = new ArrayList<MemoryEntry>();
            if (agentEntries != null) entries.addAll(agentEntries);
            if (globalEntries != null) entries.addAll(globalEntries);
            if (entries == null || entries.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (var entry : entries.subList(0, Math.min(20, entries.size()))) {
                sb.append("- ").append(entry.content(), 0, Math.min(80, entry.content().length()));
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Build a MemoryEntry with scope determined by memory type:
     * - user/feedback/reference → GLOBAL (personal preferences, cross-project)
     * - project → AGENT (workspace-specific context)
     */
    private MemoryEntry buildEntry(ExtractedMemory mem) {
        String content = mem.name + ": " + mem.content;
        Set<String> tags = Set.of("llm-extracted", mem.type);
        MemoryScope scope = switch (mem.type) {
            case "user", "feedback", "reference" -> MemoryScope.GLOBAL;
            default -> MemoryScope.AGENT;
        };
        double importance = switch (mem.type) {
            case "user" -> 0.9;
            case "feedback" -> 0.85;
            case "reference" -> 0.7;
            default -> 0.75;
        };
        return new MemoryEntry(
                "llm-" + mem.name, agentId, content, null,
                scope, importance, null, tags, Instant.now(), Map.of());
    }

    private String buildConversationSummary(List<Msg> history) {
        StringBuilder sb = new StringBuilder();
        // Take last messages up to char limit
        int charCount = 0;
        List<Msg> relevant = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && charCount < MAX_CONVERSATION_CHARS; i--) {
            Msg msg = history.get(i);
            if (msg.role() == MsgRole.USER || msg.role() == MsgRole.ASSISTANT) {
                String text = msg.contents().stream()
                        .filter(Content.TextContent.class::isInstance)
                        .map(c -> ((Content.TextContent) c).text())
                        .reduce("", String::concat);
                charCount += text.length();
                relevant.add(0, msg);
            }
        }

        for (Msg msg : relevant) {
            String role = msg.role() == MsgRole.USER ? "User" : "Assistant";
            String text = msg.contents().stream()
                    .filter(Content.TextContent.class::isInstance)
                    .map(c -> ((Content.TextContent) c).text())
                    .reduce("", String::concat);
            // Truncate individual messages
            if (text.length() > 2000) {
                text = text.substring(0, 2000) + "...";
            }
            sb.append(role).append(": ").append(text).append("\n\n");
        }
        return sb.toString();
    }

    record ExtractedMemory(String name, String type, String description, String content) {}

    List<ExtractedMemory> parseMemoryBlocks(String response) {
        List<ExtractedMemory> results = new ArrayList<>();
        Matcher blockMatcher = MEMORY_BLOCK_PATTERN.matcher(response);

        while (blockMatcher.find()) {
            String block = blockMatcher.group(1);
            String name = null, type = null, description = null, content = null;

            Matcher fieldMatcher = FIELD_PATTERN.matcher(block);
            while (fieldMatcher.find()) {
                String key = fieldMatcher.group(1);
                String value = fieldMatcher.group(2).trim();
                switch (key) {
                    case "name" -> name = value;
                    case "type" -> type = value;
                    case "description" -> description = value;
                    case "content" -> content = value;
                }
            }

            if (name != null && type != null && content != null) {
                if (description == null) {
                    description = content.length() > 80 ? content.substring(0, 80) + "..." : content;
                }
                results.add(new ExtractedMemory(name, type.toLowerCase(), description, content));
            }
        }
        return results;
    }
}
