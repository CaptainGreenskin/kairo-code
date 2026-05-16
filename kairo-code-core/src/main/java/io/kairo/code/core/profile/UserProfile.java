package io.kairo.code.core.profile;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Extracted user preferences and working patterns from session history.
 *
 * @param preferredLanguages programming languages the user works with most
 * @param preferredFrameworks frameworks/libraries frequently mentioned
 * @param communicationStyle detected style: "concise", "detailed", "mixed"
 * @param commonTopics recurring topics or domains
 * @param metadata extensible key-value attributes
 * @param updatedAt when this profile was last updated
 */
public record UserProfile(
        List<String> preferredLanguages,
        List<String> preferredFrameworks,
        String communicationStyle,
        List<String> commonTopics,
        Map<String, String> metadata,
        Instant updatedAt) {

    public static UserProfile empty() {
        return new UserProfile(List.of(), List.of(), "mixed", List.of(), Map.of(), Instant.now());
    }

    public String toSummary() {
        StringBuilder sb = new StringBuilder();
        if (!preferredLanguages.isEmpty()) {
            sb.append("Languages: ").append(String.join(", ", preferredLanguages)).append("\n");
        }
        if (!preferredFrameworks.isEmpty()) {
            sb.append("Frameworks: ").append(String.join(", ", preferredFrameworks)).append("\n");
        }
        sb.append("Communication style: ").append(communicationStyle).append("\n");
        if (!commonTopics.isEmpty()) {
            sb.append("Common topics: ").append(String.join(", ", commonTopics)).append("\n");
        }
        return sb.toString().trim();
    }
}
