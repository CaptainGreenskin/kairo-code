package io.kairo.code.core.profile;

import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Extracts a {@link UserProfile} from conversation history by analyzing user messages for
 * programming language mentions, framework references, and communication patterns.
 */
public final class UserProfileExtractor {

    private static final Pattern TOKEN_SPLITTER = Pattern.compile("[\\s\\p{Punct}]+");

    private static final Set<String> KNOWN_LANGUAGES = Set.of(
            "java", "python", "javascript", "typescript", "go", "rust", "kotlin",
            "scala", "ruby", "php", "swift", "c++", "c#", "dart", "lua", "sql",
            "shell", "bash", "groovy", "clojure", "haskell", "elixir", "r");

    private static final Set<String> KNOWN_FRAMEWORKS = Set.of(
            "spring", "react", "vue", "angular", "django", "flask", "express",
            "nextjs", "nuxt", "rails", "laravel", "fastapi", "gin", "actix",
            "quarkus", "micronaut", "ktor", "tokio", "axum", "nest",
            "hibernate", "mybatis", "jpa", "gradle", "maven", "docker",
            "kubernetes", "terraform", "ansible", "jenkins", "gitlab");

    private UserProfileExtractor() {}

    public static UserProfile extract(List<Msg> messages) {
        Map<String, Integer> langCounts = new LinkedHashMap<>();
        Map<String, Integer> frameworkCounts = new LinkedHashMap<>();
        Set<String> topics = new LinkedHashSet<>();
        int shortMessages = 0;
        int longMessages = 0;
        int userMessageCount = 0;

        for (Msg msg : messages) {
            if (msg.role() != MsgRole.USER) {
                continue;
            }
            String content = msg.text();
            if (content == null || content.isBlank()) {
                continue;
            }
            userMessageCount++;

            String lower = content.toLowerCase(Locale.ROOT);
            Set<String> tokens = tokenize(lower);

            for (String lang : KNOWN_LANGUAGES) {
                if (tokens.contains(lang)) {
                    langCounts.merge(lang, 1, Integer::sum);
                }
            }
            for (String fw : KNOWN_FRAMEWORKS) {
                if (tokens.contains(fw)) {
                    frameworkCounts.merge(fw, 1, Integer::sum);
                }
            }

            if (content.length() < 50) {
                shortMessages++;
            } else if (content.length() > 200) {
                longMessages++;
            }
        }

        List<String> languages = topN(langCounts, 5);
        List<String> frameworks = topN(frameworkCounts, 5);

        String style;
        if (userMessageCount == 0) {
            style = "mixed";
        } else {
            double shortRatio = (double) shortMessages / userMessageCount;
            double longRatio = (double) longMessages / userMessageCount;
            if (shortRatio > 0.6) {
                style = "concise";
            } else if (longRatio > 0.4) {
                style = "detailed";
            } else {
                style = "mixed";
            }
        }

        return new UserProfile(
                languages, frameworks, style, new ArrayList<>(topics),
                Map.of(), Instant.now());
    }

    private static Set<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String t : TOKEN_SPLITTER.split(text)) {
            if (t.length() >= 1) {
                tokens.add(t);
            }
        }
        return tokens;
    }

    private static List<String> topN(Map<String, Integer> counts, int n) {
        return counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(n)
                .map(Map.Entry::getKey)
                .toList();
    }
}
