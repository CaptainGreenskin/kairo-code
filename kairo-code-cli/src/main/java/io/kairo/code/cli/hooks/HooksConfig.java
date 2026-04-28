package io.kairo.code.cli.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration for shell hooks loaded from {@code ~/.kairo-code/hooks.json}.
 *
 * <p>Supported events: {@code PreToolUse}, {@code PostToolUse}, {@code Stop}.
 * Each event maps to a list of {@link HookEntry} with a matcher and shell command.
 */
public class HooksConfig {

    private static final Logger log = LoggerFactory.getLogger(HooksConfig.class);
    private static final String KAIRO_CODE_DIR = ".kairo-code";
    private static final String HOOKS_FILE = "hooks.json";

    private final Map<String, List<HookEntry>> hooks;

    public HooksConfig(Map<String, List<HookEntry>> hooks) {
        this.hooks = hooks != null ? Map.copyOf(hooks) : Map.of();
    }

    /** Return hook entries for the given event (may be empty). */
    public List<HookEntry> getHooks(String event) {
        return hooks.getOrDefault(event, List.of());
    }

    /** Return all configured events. */
    public Map<String, List<HookEntry>> getAll() {
        return Collections.unmodifiableMap(hooks);
    }

    /** Whether any hooks are configured at all. */
    public boolean isEmpty() {
        return hooks.isEmpty();
    }

    /**
     * Load hooks from {@code ~/.kairo-code/hooks.json}.
     * Returns an empty config if the file does not exist or cannot be parsed.
     */
    public static HooksConfig loadDefault() {
        Path path = Path.of(System.getProperty("user.home"), KAIRO_CODE_DIR, HOOKS_FILE);
        return load(path);
    }

    /**
     * Load hooks from the given path.
     * Returns an empty config if the file does not exist or cannot be parsed.
     */
    public static HooksConfig load(Path path) {
        if (!Files.exists(path)) {
            return new HooksConfig(Map.of());
        }
        try {
            String json = Files.readString(path);
            return parse(json);
        } catch (IOException e) {
            log.warn("Failed to read hooks config from {}: {}", path, e.getMessage());
            return new HooksConfig(Map.of());
        }
    }

    /** Parse hooks from a JSON string. */
    public static HooksConfig parse(String json) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);
            JsonNode hooksNode = root.get("hooks");
            if (hooksNode == null || !hooksNode.isObject()) {
                return new HooksConfig(Map.of());
            }

            Map<String, List<HookEntry>> map = new HashMap<>();
            hooksNode.fields().forEachRemaining(entry -> {
                String event = entry.getKey();
                JsonNode arr = entry.getValue();
                if (arr.isArray()) {
                    List<HookEntry> entries = new ArrayList<>();
                    for (JsonNode node : arr) {
                        String matcher = node.has("matcher") ? node.get("matcher").asText() : "*";
                        String command = node.has("command") ? node.get("command").asText() : "";
                        if (!command.isBlank()) {
                            entries.add(new HookEntry(matcher, command));
                        }
                    }
                    map.put(event, List.copyOf(entries));
                }
            });

            return new HooksConfig(map);
        } catch (IOException e) {
            log.warn("Failed to parse hooks config JSON: {}", e.getMessage());
            return new HooksConfig(Map.of());
        }
    }
}
