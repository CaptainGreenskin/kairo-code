package io.kairo.code.core.memory;

import io.kairo.api.hook.HookHandler;
import io.kairo.api.hook.HookPhase;
import io.kairo.api.hook.HookResult;
import io.kairo.api.hook.PostReasoningEvent;
import io.kairo.api.memory.MemoryEntry;
import io.kairo.api.memory.MemoryScope;
import io.kairo.api.memory.MemoryStore;
import io.kairo.api.message.Content;
import io.kairo.api.model.ModelResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hook that automatically extracts and saves memories from agent interactions.
 *
 * <p>Fires after each model response ({@link HookPhase#POST_REASONING}), scans the response text
 * for facts, preferences, and decisions worth remembering, and saves them to the
 * {@link MemoryStore} as {@link MemoryEntry} objects with {@link MemoryScope#AGENT} scope.
 *
 * <p>Extraction uses heuristic pattern matching (not LLM-based). Deduplication is performed
 * against recently saved memories using substring overlap to avoid saving the same fact twice.
 */
public final class AutoMemoryHook {

    private static final Logger log = LoggerFactory.getLogger(AutoMemoryHook.class);

    /** Minimum response text length to consider for memory extraction. */
    static final int MIN_RESPONSE_LENGTH = 30;

    /** Maximum number of memories to extract from a single response. */
    static final int MAX_MEMORIES_PER_RESPONSE = 3;

    /** Minimum overlap ratio for deduplication (0.0-1.0). */
    static final double DEDUP_THRESHOLD = 0.6;

    /** Timeout for blocking memory queries during deduplication. */
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(3);

    // --- Patterns for explicit memory markers ---

    /** Patterns that indicate an explicit "remember this" request. */
    static final List<Pattern> REMEMBER_PATTERNS = List.of(
            Pattern.compile("(?i)(?:i'll |i will )?remember(?:ing)? that\\b(.{10,120})"),
            Pattern.compile("(?i)\\bplease remember\\b(.{10,120})"),
            Pattern.compile("(?i)\\bnote(?:d)?:\\s*(.{10,120})"),
            Pattern.compile("(?i)\\bimportant:\\s*(.{10,120})")
    );

    /** Patterns for technical facts (file paths, config, architecture). */
    static final List<Pattern> TECHNICAL_PATTERNS = List.of(
            Pattern.compile("(?i)(?:the |this )?(?:project|repo|codebase) (?:uses?|is based on|relies on) (.{10,100})"),
            Pattern.compile("(?i)(?:the )?(?:config|configuration|setting)\\s+(?:is|for)\\s+(.{10,100})"),
            Pattern.compile("(?i)(?:the )?(?:architecture|design pattern|convention) (?:is|uses?) (.{10,100})"),
            Pattern.compile("(?i)(?:the )?build (?:command|tool|system) (?:is|uses?) (.{10,80})"),
            Pattern.compile("(?i)(?:tests? (?:are|should be) run (?:with|using|via)) (.{10,80})")
    );

    /** Patterns for user preferences. */
    static final List<Pattern> PREFERENCE_PATTERNS = List.of(
            Pattern.compile("(?i)(?:i |the user )?prefer(?:s|red)?\\s+(.{10,100})"),
            Pattern.compile("(?i)(?:i |the user )?(?:always |usually )?(?:want|like|need)s?\\s+(.{10,100})"),
            Pattern.compile("(?i)(?:the )?(?:coding style|naming convention|formatting) (?:is|should be) (.{10,100})")
    );

    /** Tags to auto-generate based on content keywords. */
    private static final List<String> TAG_KEYWORDS = List.of(
            "test", "build", "config", "architecture", "preference",
            "convention", "api", "database", "deploy", "git",
            "maven", "gradle", "java", "python", "docker"
    );

    private final MemoryStore memoryStore;
    private final String agentId;

    /**
     * Create an AutoMemoryHook with the default agent ID "kairo-code".
     *
     * @param memoryStore the store to save memories to
     */
    public AutoMemoryHook(MemoryStore memoryStore) {
        this(memoryStore, "kairo-code");
    }

    /**
     * Create an AutoMemoryHook with a specific agent ID.
     *
     * @param memoryStore the store to save memories to
     * @param agentId the agent identifier for memory ownership
     */
    public AutoMemoryHook(MemoryStore memoryStore, String agentId) {
        this.memoryStore = memoryStore;
        this.agentId = agentId;
    }

    @HookHandler(HookPhase.POST_REASONING)
    public HookResult<PostReasoningEvent> onPostReasoning(PostReasoningEvent event) {
        if (memoryStore == null) {
            return HookResult.proceed(event);
        }

        ModelResponse response = event.response();
        if (response == null || response.contents() == null) {
            return HookResult.proceed(event);
        }

        // Extract text from all TextContent blocks in the response
        String responseText = response.contents().stream()
                .filter(Content.TextContent.class::isInstance)
                .map(c -> ((Content.TextContent) c).text())
                .collect(Collectors.joining("\n"));

        if (responseText == null || responseText.length() < MIN_RESPONSE_LENGTH) {
            return HookResult.proceed(event);
        }

        // Extract memory candidates and save them asynchronously
        try {
            List<MemoryCandidate> candidates = extractCandidates(responseText);
            if (!candidates.isEmpty()) {
                saveMemories(candidates);
            }
        } catch (Exception e) {
            log.debug("AutoMemoryHook extraction failed: {}", e.getMessage());
        }

        // Always proceed — memory extraction must never block the agent flow
        return HookResult.proceed(event);
    }

    /**
     * Extract memory candidates from the response text using heuristic patterns.
     *
     * @param text the model response text
     * @return list of candidate memories (may be empty, never null)
     */
    List<MemoryCandidate> extractCandidates(String text) {
        List<MemoryCandidate> candidates = new ArrayList<>();

        // 1. Check for explicit "remember" markers (highest importance)
        for (Pattern pattern : REMEMBER_PATTERNS) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find() && candidates.size() < MAX_MEMORIES_PER_RESPONSE) {
                String content = matcher.group(1).trim();
                if (isValidContent(content)) {
                    candidates.add(new MemoryCandidate(content, 0.9, "explicit"));
                }
            }
        }

        // 2. Check for technical facts
        for (Pattern pattern : TECHNICAL_PATTERNS) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find() && candidates.size() < MAX_MEMORIES_PER_RESPONSE) {
                String content = matcher.group(1).trim();
                if (isValidContent(content) && !isDuplicate(content, candidates)) {
                    candidates.add(new MemoryCandidate(content, 0.7, "technical"));
                }
            }
        }

        // 3. Check for user preferences
        for (Pattern pattern : PREFERENCE_PATTERNS) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find() && candidates.size() < MAX_MEMORIES_PER_RESPONSE) {
                String content = matcher.group(1).trim();
                if (isValidContent(content) && !isDuplicate(content, candidates)) {
                    candidates.add(new MemoryCandidate(content, 0.8, "preference"));
                }
            }
        }

        return candidates.stream()
                .limit(MAX_MEMORIES_PER_RESPONSE)
                .toList();
    }

    /**
     * Save extracted memory candidates to the store, with deduplication against existing memories.
     */
    private void saveMemories(List<MemoryCandidate> candidates) {
        // Load recent memories for deduplication
        List<MemoryEntry> recentMemories = loadRecentMemories();

        for (MemoryCandidate candidate : candidates) {
            if (isDuplicateOfExisting(candidate.content(), recentMemories)) {
                log.debug("Skipping duplicate memory: {}", truncate(candidate.content(), 50));
                continue;
            }

            MemoryEntry entry = new MemoryEntry(
                    UUID.randomUUID().toString(),
                    agentId,
                    candidate.content(),
                    null,
                    MemoryScope.AGENT,
                    candidate.importance(),
                    null,
                    generateTags(candidate.content(), candidate.category()),
                    Instant.now(),
                    null);

            // Fire and forget — don't block the agent flow
            memoryStore.save(entry)
                    .subscribe(
                            saved -> log.debug("Saved auto-memory: {}", truncate(saved.content(), 50)),
                            error -> log.debug("Failed to save auto-memory: {}", error.getMessage()));
        }
    }

    /**
     * Load recent memories from the store for deduplication.
     */
    private List<MemoryEntry> loadRecentMemories() {
        try {
            List<MemoryEntry> memories = memoryStore.recent(agentId, 20)
                    .collectList()
                    .block(QUERY_TIMEOUT);
            return memories != null ? memories : List.of();
        } catch (Exception e) {
            log.debug("Failed to load recent memories for dedup: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Check if the candidate content is a duplicate of any existing memory (substring overlap).
     */
    static boolean isDuplicateOfExisting(String candidateContent, List<MemoryEntry> existing) {
        if (existing == null || existing.isEmpty()) {
            return false;
        }
        String normalized = candidateContent.toLowerCase(Locale.ROOT).trim();
        for (MemoryEntry entry : existing) {
            if (entry.content() == null) continue;
            String existingNormalized = entry.content().toLowerCase(Locale.ROOT).trim();
            if (computeOverlap(normalized, existingNormalized) >= DEDUP_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a candidate is a duplicate of already-extracted candidates in this batch.
     */
    private static boolean isDuplicate(String content, List<MemoryCandidate> existing) {
        String normalized = content.toLowerCase(Locale.ROOT).trim();
        for (MemoryCandidate c : existing) {
            String existingNormalized = c.content().toLowerCase(Locale.ROOT).trim();
            if (computeOverlap(normalized, existingNormalized) >= DEDUP_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    /**
     * Compute the overlap ratio between two strings using longest common substring approach.
     * Returns a value between 0.0 (no overlap) and 1.0 (one string contains the other).
     */
    static double computeOverlap(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        // Simple containment check
        if (a.contains(b) || b.contains(a)) {
            return 1.0;
        }
        // Word-level overlap
        Set<String> wordsA = Set.of(a.split("\\s+"));
        Set<String> wordsB = Set.of(b.split("\\s+"));
        if (wordsA.isEmpty() || wordsB.isEmpty()) {
            return 0.0;
        }
        long common = wordsA.stream().filter(wordsB::contains).count();
        int minSize = Math.min(wordsA.size(), wordsB.size());
        return minSize == 0 ? 0.0 : (double) common / minSize;
    }

    /**
     * Generate tags from content keywords and the extraction category.
     */
    static Set<String> generateTags(String content, String category) {
        Set<String> tags = new LinkedHashSet<>();
        if (category != null && !category.isEmpty()) {
            tags.add("auto:" + category);
        }
        String lower = content.toLowerCase(Locale.ROOT);
        for (String keyword : TAG_KEYWORDS) {
            if (lower.contains(keyword)) {
                tags.add(keyword);
            }
        }
        return tags.isEmpty() ? Set.of("auto") : Set.copyOf(tags);
    }

    /**
     * Validate that extracted content is meaningful (not too short, not just punctuation).
     */
    static boolean isValidContent(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String trimmed = content.trim();
        if (trimmed.length() < 10) {
            return false;
        }
        // Must contain at least 2 word characters
        long wordChars = trimmed.chars().filter(Character::isLetterOrDigit).count();
        return wordChars >= 5;
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    /**
     * Internal record for a candidate memory before saving.
     */
    record MemoryCandidate(String content, double importance, String category) {}
}
