/*
 * Copyright 2025-2026 the Kairo authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.kairo.code.cli.statusline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads {@link StatusLineConfig} from a 3-layer JSON hierarchy, mirroring
 * {@code PermissionSettingsLoader} so the same mental model applies.
 *
 * <p>Layers, lowest to highest priority:
 *
 * <ol>
 *   <li>{@code ~/.kairo-code/statusline.json} — user-level default
 *   <li>{@code <projectRoot>/.kairo/statusline.json} — project committed
 *   <li>{@code <projectRoot>/.kairo/statusline.local.json} — local override (git-ignored)
 * </ol>
 *
 * <p>JSON schema:
 *
 * <pre>{@code
 * {
 *   "type": "command",
 *   "command": "bash ~/.kairo-code/statusline.sh",
 *   "refreshInterval": 2,
 *   "padding": 0,
 *   "timeoutMs": 5000
 * }
 * }</pre>
 *
 * <p>Any layer may omit a field — later layers override earlier non-null fields. If no layer
 * defines {@code command}, the returned config is {@link StatusLineConfig#disabled()} and the
 * REPL falls back to the built-in {@code TokenStatusLine}.
 */
public final class StatusLineConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(StatusLineConfigLoader.class);

    private final ObjectMapper objectMapper;
    private final List<Path> configPaths;

    /**
     * Default constructor — looks at the canonical 3 paths relative to {@code projectRoot}.
     *
     * @param objectMapper Jackson mapper (reused from the calling subsystem)
     * @param projectRoot the workspace root for resolving project-level configs
     */
    public StatusLineConfigLoader(ObjectMapper objectMapper, Path projectRoot) {
        this.objectMapper = objectMapper;
        Path userHome = Path.of(System.getProperty("user.home"));
        this.configPaths =
                List.of(
                        userHome.resolve(".kairo-code/statusline.json"),
                        projectRoot.resolve(".kairo/statusline.json"),
                        projectRoot.resolve(".kairo/statusline.local.json"));
    }

    /** Test seam — accepts arbitrary paths in priority order. */
    public StatusLineConfigLoader(ObjectMapper objectMapper, List<Path> configPaths) {
        this.objectMapper = objectMapper;
        this.configPaths = List.copyOf(configPaths);
    }

    /**
     * Load and merge settings across all layers. Failures on individual layers are logged at
     * WARN and treated as absent — never thrown — so a broken local override doesn't lock the
     * user out of their REPL.
     */
    public StatusLineConfig load() {
        StatusLineConfig result = StatusLineConfig.disabled();
        for (Path path : configPaths) {
            if (Files.exists(path) && Files.isReadable(path)) {
                try {
                    result = mergeLayer(result, loadFrom(path));
                    log.debug("Loaded status-line config from {}", path);
                } catch (Exception e) {
                    log.warn("Failed to load status-line config from {}: {}", path, e.getMessage());
                }
            }
        }
        return result;
    }

    StatusLineConfig loadFrom(Path path) throws IOException {
        JsonNode root = objectMapper.readTree(path.toFile());
        if (!root.isObject()) {
            throw new IOException("Expected JSON object in " + path);
        }
        String type = textOrNull(root, "type");
        String command = textOrNull(root, "command");
        Duration refresh = secondsOrNull(root, "refreshInterval");
        int padding = root.path("padding").asInt(0);
        Duration timeout = millisOrNull(root, "timeoutMs");
        return new StatusLineConfig(type, command, refresh, padding, timeout);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    private static Duration secondsOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v != null && v.isNumber()) {
            long ms = (long) (v.asDouble() * 1000);
            return ms > 0 ? Duration.ofMillis(ms) : null;
        }
        return null;
    }

    private static Duration millisOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v != null && v.isNumber()) {
            long ms = v.asLong();
            return ms > 0 ? Duration.ofMillis(ms) : null;
        }
        return null;
    }

    /** Merge later layer on top of earlier — later non-blank fields win. */
    static StatusLineConfig mergeLayer(StatusLineConfig base, StatusLineConfig over) {
        String type = nonBlankOr(over.type(), base.type());
        String command = nonBlankOr(over.command(), base.command());
        Duration refresh = over.hasTimedRefresh() ? over.refreshInterval() : base.refreshInterval();
        int padding = over.padding() != 0 ? over.padding() : base.padding();
        Duration timeout =
                !over.timeout().equals(StatusLineConfig.DEFAULT_TIMEOUT)
                        ? over.timeout()
                        : base.timeout();
        return new StatusLineConfig(type, command, refresh, padding, timeout);
    }

    private static String nonBlankOr(String over, String base) {
        return (over != null && !over.isBlank()) ? over : base;
    }
}
