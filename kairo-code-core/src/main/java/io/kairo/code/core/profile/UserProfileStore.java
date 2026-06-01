package io.kairo.code.core.profile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists the extracted {@link UserProfile} to {@code <kairoDir>/profile.json}.
 *
 * <p>Mirrors {@link io.kairo.code.core.evolution.LearnedLessonStore}'s file-backed JSON
 * approach (timestamp stored as an ISO string so no jackson-jsr310 module is needed).
 * The profile is written by {@code UserProfileUpdateHook} as the conversation grows and
 * read back in {@code CodeAgentFactory.resolveSystemPrompt} to personalize the system prompt.
 */
public final class UserProfileStore {

    private static final Logger log = LoggerFactory.getLogger(UserProfileStore.class);
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Stored(
            List<String> preferredLanguages,
            List<String> preferredFrameworks,
            String communicationStyle,
            List<String> commonTopics,
            Map<String, String> metadata,
            String updatedAt) {}

    private final Path storePath;

    public UserProfileStore(Path storePath) {
        this.storePath = storePath;
    }

    /** Create a store backed by {@code <kairoDir>/profile.json}. */
    public static UserProfileStore fromKairoDir(Path kairoDir) {
        return new UserProfileStore(kairoDir.resolve("profile.json"));
    }

    /** Returns the persisted profile, or {@code null} if none exists yet. */
    public UserProfile load() {
        if (!Files.exists(storePath)) {
            return null;
        }
        try {
            Stored s = MAPPER.readValue(Files.readString(storePath), Stored.class);
            Instant updated;
            try {
                updated = s.updatedAt() != null ? Instant.parse(s.updatedAt()) : Instant.now();
            } catch (Exception e) {
                updated = Instant.now();
            }
            return new UserProfile(
                    s.preferredLanguages() != null ? s.preferredLanguages() : List.of(),
                    s.preferredFrameworks() != null ? s.preferredFrameworks() : List.of(),
                    s.communicationStyle() != null ? s.communicationStyle() : "mixed",
                    s.commonTopics() != null ? s.commonTopics() : List.of(),
                    s.metadata() != null ? s.metadata() : Map.of(),
                    updated);
        } catch (IOException e) {
            log.debug("Failed to read user profile from {}: {}", storePath, e.getMessage());
            return null;
        }
    }

    public void save(UserProfile profile) {
        if (profile == null) {
            return;
        }
        try {
            Files.createDirectories(storePath.getParent());
            Stored s = new Stored(
                    profile.preferredLanguages(),
                    profile.preferredFrameworks(),
                    profile.communicationStyle(),
                    profile.commonTopics(),
                    profile.metadata(),
                    profile.updatedAt() != null ? profile.updatedAt().toString() : Instant.now().toString());
            Files.writeString(storePath, MAPPER.writeValueAsString(s));
        } catch (IOException e) {
            log.debug("Failed to write user profile to {}: {}", storePath, e.getMessage());
        }
    }
}
