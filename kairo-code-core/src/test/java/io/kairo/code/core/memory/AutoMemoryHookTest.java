package io.kairo.code.core.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.hook.HookResult;
import io.kairo.api.hook.PostReasoningEvent;
import io.kairo.api.memory.MemoryEntry;
import io.kairo.api.memory.MemoryScope;
import io.kairo.api.memory.MemoryStore;
import io.kairo.api.message.Content;
import io.kairo.api.model.ModelResponse;
import io.kairo.code.core.memory.AutoMemoryHook.MemoryCandidate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class AutoMemoryHookTest {

    // --- Stub MemoryStore that captures saves ---

    static class StubMemoryStore implements MemoryStore {
        final CopyOnWriteArrayList<MemoryEntry> saved = new CopyOnWriteArrayList<>();

        @Override
        public Mono<MemoryEntry> save(MemoryEntry entry) {
            saved.add(entry);
            return Mono.just(entry);
        }

        @Override
        public Mono<MemoryEntry> get(String id) {
            return saved.stream().filter(e -> e.id().equals(id)).findFirst()
                    .map(Mono::just).orElse(Mono.empty());
        }

        @Override
        public Flux<MemoryEntry> search(String query, MemoryScope scope) {
            return Flux.fromIterable(saved);
        }

        @Override
        public Mono<Void> delete(String id) {
            saved.removeIf(e -> e.id().equals(id));
            return Mono.empty();
        }

        @Override
        public Flux<MemoryEntry> list(MemoryScope scope) {
            return Flux.fromIterable(saved).filter(e -> e.scope() == scope);
        }
    }

    // --- Helper methods ---

    private static PostReasoningEvent eventWithText(String text) {
        ModelResponse response = new ModelResponse(
                "resp-1",
                List.of(new Content.TextContent(text)),
                null,
                ModelResponse.StopReason.END_TURN,
                "test-model");
        return new PostReasoningEvent(response, false);
    }

    private static PostReasoningEvent eventWithContents(Content... contents) {
        ModelResponse response = new ModelResponse(
                "resp-1",
                List.of(contents),
                null,
                ModelResponse.StopReason.END_TURN,
                "test-model");
        return new PostReasoningEvent(response, false);
    }

    // --- Test 1: Extract memory from explicit "remember" request ---

    @Test
    void extractsMemoryFromExplicitRememberRequest() {
        StubMemoryStore store = new StubMemoryStore();
        AutoMemoryHook hook = new AutoMemoryHook(store);

        String text = "I'll remember that the project uses Maven with Java 21 for all builds.";
        PostReasoningEvent event = eventWithText(text);

        hook.onPostReasoning(event);

        // Give async save a moment
        waitForAsync();

        assertThat(store.saved).isNotEmpty();
        assertThat(store.saved.get(0).content()).contains("the project uses Maven with Java 21");
        assertThat(store.saved.get(0).importance()).isEqualTo(0.9);
    }

    // --- Test 2: Skips trivial/short responses ---

    @Test
    void skipsTrivialShortResponses() {
        StubMemoryStore store = new StubMemoryStore();
        AutoMemoryHook hook = new AutoMemoryHook(store);

        // Short text below MIN_RESPONSE_LENGTH
        PostReasoningEvent event = eventWithText("OK, done.");

        HookResult<PostReasoningEvent> result = hook.onPostReasoning(event);

        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(store.saved).isEmpty();
    }

    // --- Test 3: Deduplication works (doesn't save same fact twice) ---

    @Test
    void deduplicationPreventsDoubleSave() {
        StubMemoryStore store = new StubMemoryStore();
        // Pre-populate with an existing memory
        MemoryEntry existing = new MemoryEntry(
                "existing-1", "kairo-code",
                "the project uses Maven with Java 21 for all builds",
                null, MemoryScope.AGENT, 0.9, null,
                Set.of("auto:explicit"), Instant.now(), null);
        store.saved.add(existing);

        AutoMemoryHook hook = new AutoMemoryHook(store);
        String text = "I'll remember that the project uses Maven with Java 21 for all builds.";
        PostReasoningEvent event = eventWithText(text);

        hook.onPostReasoning(event);
        waitForAsync();

        // Should still have only the original memory (dedup prevents second save)
        assertThat(store.saved).hasSize(1);
    }

    // --- Test 4: Importance scoring for different content types ---

    @Test
    void importanceScoringForDifferentContentTypes() {
        AutoMemoryHook hook = new AutoMemoryHook(new StubMemoryStore());

        // Explicit remember → 0.9
        List<MemoryCandidate> rememberCandidates = hook.extractCandidates(
                "I'll remember that the CI pipeline uses GitHub Actions for deployment.");
        assertThat(rememberCandidates).isNotEmpty();
        assertThat(rememberCandidates.get(0).importance()).isEqualTo(0.9);

        // Technical fact → 0.7
        List<MemoryCandidate> techCandidates = hook.extractCandidates(
                "The project uses Spring Boot with reactive WebFlux for all HTTP endpoints.");
        assertThat(techCandidates).isNotEmpty();
        assertThat(techCandidates.get(0).importance()).isEqualTo(0.7);

        // Preference → 0.8
        List<MemoryCandidate> prefCandidates = hook.extractCandidates(
                "The user prefers using AssertJ over Hamcrest for test assertions in this project.");
        assertThat(prefCandidates).isNotEmpty();
        assertThat(prefCandidates.get(0).importance()).isEqualTo(0.8);
    }

    // --- Test 5: MemoryEntry has correct scope (AGENT) ---

    @Test
    void memoryEntryHasAgentScope() {
        StubMemoryStore store = new StubMemoryStore();
        AutoMemoryHook hook = new AutoMemoryHook(store);

        PostReasoningEvent event = eventWithText(
                "Important: the database migration scripts must be run before deploying the new version.");

        hook.onPostReasoning(event);
        waitForAsync();

        assertThat(store.saved).isNotEmpty();
        assertThat(store.saved.get(0).scope()).isEqualTo(MemoryScope.AGENT);
    }

    // --- Test 6: MemoryEntry has correct agentId ---

    @Test
    void memoryEntryHasCorrectAgentId() {
        StubMemoryStore store = new StubMemoryStore();
        AutoMemoryHook hook = new AutoMemoryHook(store, "my-custom-agent");

        PostReasoningEvent event = eventWithText(
                "Noted: the staging environment uses a separate database cluster for isolation.");

        hook.onPostReasoning(event);
        waitForAsync();

        assertThat(store.saved).isNotEmpty();
        assertThat(store.saved.get(0).agentId()).isEqualTo("my-custom-agent");
    }

    // --- Test 7: Tags are auto-generated ---

    @Test
    void tagsAreAutoGenerated() {
        Set<String> tags = AutoMemoryHook.generateTags(
                "The Maven build configuration uses Java 17", "technical");

        assertThat(tags).contains("auto:technical");
        assertThat(tags).contains("maven");
        assertThat(tags).contains("java");
        assertThat(tags).contains("build");
    }

    @Test
    void tagsDefaultToAutoWhenNoKeywordsMatch() {
        Set<String> tags = AutoMemoryHook.generateTags("something very generic here", null);

        assertThat(tags).containsExactly("auto");
    }

    // --- Test 8: Timestamp is set ---

    @Test
    void timestampIsSet() {
        StubMemoryStore store = new StubMemoryStore();
        AutoMemoryHook hook = new AutoMemoryHook(store);
        Instant before = Instant.now();

        PostReasoningEvent event = eventWithText(
                "Important: all API endpoints require authentication tokens for access.");

        hook.onPostReasoning(event);
        waitForAsync();

        assertThat(store.saved).isNotEmpty();
        Instant ts = store.saved.get(0).timestamp();
        assertThat(ts).isNotNull();
        assertThat(ts).isAfterOrEqualTo(before);
        assertThat(ts).isBeforeOrEqualTo(Instant.now());
    }

    // --- Test 9: Multiple memories from one response ---

    @Test
    void multipleMemoriesFromOneResponse() {
        AutoMemoryHook hook = new AutoMemoryHook(new StubMemoryStore());

        String text = "I'll remember that the CI pipeline runs on every push. "
                + "Noted: the deploy script requires AWS credentials to be configured. "
                + "Important: the test database is reset nightly at midnight UTC.";

        List<MemoryCandidate> candidates = hook.extractCandidates(text);

        assertThat(candidates).hasSizeGreaterThanOrEqualTo(2);
        assertThat(candidates).hasSizeLessThanOrEqualTo(AutoMemoryHook.MAX_MEMORIES_PER_RESPONSE);
    }

    // --- Test 10: Empty/null response handling ---

    @Test
    void handlesNullResponse() {
        StubMemoryStore store = new StubMemoryStore();
        AutoMemoryHook hook = new AutoMemoryHook(store);

        PostReasoningEvent event = new PostReasoningEvent(null, false);
        HookResult<PostReasoningEvent> result = hook.onPostReasoning(event);

        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(store.saved).isEmpty();
    }

    @Test
    void handlesNullContents() {
        StubMemoryStore store = new StubMemoryStore();
        AutoMemoryHook hook = new AutoMemoryHook(store);

        ModelResponse response = new ModelResponse(
                "resp-1", null, null, ModelResponse.StopReason.END_TURN, "test-model");
        PostReasoningEvent event = new PostReasoningEvent(response, false);

        HookResult<PostReasoningEvent> result = hook.onPostReasoning(event);

        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(store.saved).isEmpty();
    }

    @Test
    void handlesEmptyTextContent() {
        StubMemoryStore store = new StubMemoryStore();
        AutoMemoryHook hook = new AutoMemoryHook(store);

        PostReasoningEvent event = eventWithText("");
        HookResult<PostReasoningEvent> result = hook.onPostReasoning(event);

        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(store.saved).isEmpty();
    }

    // --- Test 11: Null memoryStore is handled gracefully ---

    @Test
    void nullMemoryStoreDoesNotThrow() {
        AutoMemoryHook hook = new AutoMemoryHook(null);

        PostReasoningEvent event = eventWithText(
                "Important: the system requires authentication for all endpoints.");

        HookResult<PostReasoningEvent> result = hook.onPostReasoning(event);
        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }

    // --- Test 12: Always returns CONTINUE (never blocks agent) ---

    @Test
    void alwaysReturnsContinue() {
        StubMemoryStore store = new StubMemoryStore();
        AutoMemoryHook hook = new AutoMemoryHook(store);

        PostReasoningEvent event = eventWithText(
                "I'll remember that the user wants all code in English with clear comments.");

        HookResult<PostReasoningEvent> result = hook.onPostReasoning(event);

        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }

    // --- Test 13: isValidContent filtering ---

    @Test
    void isValidContentRejectsShortContent() {
        assertThat(AutoMemoryHook.isValidContent(null)).isFalse();
        assertThat(AutoMemoryHook.isValidContent("")).isFalse();
        assertThat(AutoMemoryHook.isValidContent("   ")).isFalse();
        assertThat(AutoMemoryHook.isValidContent("too short")).isFalse();
        assertThat(AutoMemoryHook.isValidContent("....!!!!")).isFalse();
    }

    @Test
    void isValidContentAcceptsMeaningfulContent() {
        assertThat(AutoMemoryHook.isValidContent("the project uses Maven for builds")).isTrue();
    }

    // --- Test 14: computeOverlap ---

    @Test
    void computeOverlapDetectsContainment() {
        assertThat(AutoMemoryHook.computeOverlap("abc", "abc def")).isEqualTo(1.0);
        assertThat(AutoMemoryHook.computeOverlap("abc def", "abc")).isEqualTo(1.0);
    }

    @Test
    void computeOverlapHandlesNullAndEmpty() {
        assertThat(AutoMemoryHook.computeOverlap(null, "abc")).isEqualTo(0.0);
        assertThat(AutoMemoryHook.computeOverlap("abc", null)).isEqualTo(0.0);
        assertThat(AutoMemoryHook.computeOverlap("", "abc")).isEqualTo(0.0);
    }

    // --- Test 15: isDuplicateOfExisting ---

    @Test
    void isDuplicateOfExistingDetectsMatch() {
        List<MemoryEntry> existing = List.of(
                new MemoryEntry("1", "kairo-code", "the project uses Maven for builds",
                        null, MemoryScope.AGENT, 0.7, null, Set.of(), Instant.now(), null));

        assertThat(AutoMemoryHook.isDuplicateOfExisting(
                "the project uses Maven for builds", existing)).isTrue();
    }

    @Test
    void isDuplicateOfExistingReturnsFalseForNoMatch() {
        List<MemoryEntry> existing = List.of(
                new MemoryEntry("1", "kairo-code", "the project uses Maven for builds",
                        null, MemoryScope.AGENT, 0.7, null, Set.of(), Instant.now(), null));

        assertThat(AutoMemoryHook.isDuplicateOfExisting(
                "deploy to production requires SSH keys", existing)).isFalse();
    }

    @Test
    void isDuplicateOfExistingHandlesEmptyList() {
        assertThat(AutoMemoryHook.isDuplicateOfExisting("anything", List.of())).isFalse();
        assertThat(AutoMemoryHook.isDuplicateOfExisting("anything", null)).isFalse();
    }

    // --- Test 16: Default agentId ---

    @Test
    void defaultAgentIdIsKairoCode() {
        StubMemoryStore store = new StubMemoryStore();
        AutoMemoryHook hook = new AutoMemoryHook(store);

        PostReasoningEvent event = eventWithText(
                "Noted: the application uses port 8080 for the development server by default.");

        hook.onPostReasoning(event);
        waitForAsync();

        assertThat(store.saved).isNotEmpty();
        assertThat(store.saved.get(0).agentId()).isEqualTo("kairo-code");
    }

    // --- Utility ---

    private static void waitForAsync() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
