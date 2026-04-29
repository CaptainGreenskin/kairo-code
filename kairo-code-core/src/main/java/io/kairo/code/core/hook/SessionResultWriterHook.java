/*
 * Copyright 2025-2026 the Kairo authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.kairo.code.core.hook;

import io.kairo.api.hook.HookHandler;
import io.kairo.api.hook.HookPhase;
import io.kairo.api.hook.HookResult;
import io.kairo.api.hook.SessionEndEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes a machine-readable JSON result file when a session ends.
 *
 * <p>Used by the dispatcher to determine task outcome without relying on git commits.
 *
 * <p>Phase: {@link HookPhase#SESSION_END}
 */
public final class SessionResultWriterHook {

    private static final Logger log = LoggerFactory.getLogger(SessionResultWriterHook.class);

    private final Path workingDir;
    private final SessionMetricsCollector metrics;

    /**
     * @param workingDir directory to write the result file into; null means no-op (safe for REPL)
     */
    public SessionResultWriterHook(Path workingDir) {
        this(workingDir, null);
    }

    /**
     * @param workingDir directory to write the result file into; null means no-op (safe for REPL)
     * @param metrics optional metrics collector to enrich the result with efficiency data
     */
    public SessionResultWriterHook(Path workingDir, SessionMetricsCollector metrics) {
        this.workingDir = workingDir;
        this.metrics = metrics;
    }

    @HookHandler(HookPhase.SESSION_END)
    public HookResult<SessionEndEvent> onSessionEnd(SessionEndEvent event) {
        if (workingDir == null) {
            return HookResult.proceed(event);
        }

        try {
            String json = toJson(event, metrics);
            Files.writeString(workingDir.resolve("KAIRO_SESSION_RESULT.json"), json);
        } catch (Exception e) {
            log.debug("Failed to write session result file: {}", e.getMessage());
        }

        return HookResult.proceed(event);
    }

    private static String toJson(SessionEndEvent event) {
        return toJson(event, null);
    }

    private static String toJson(SessionEndEvent event, SessionMetricsCollector metrics) {
        String escapedError =
                event.error() != null
                        ? "\"" + event.error().replace("\"", "\\\"").replace("\n", "\\n") + "\""
                        : "null";

        String base = "{\n"
                + "  \"finalState\": \"" + event.finalState() + "\",\n"
                + "  \"iterations\": " + event.iterations() + ",\n"
                + "  \"tokensUsed\": " + event.tokensUsed() + ",\n"
                + "  \"durationSeconds\": " + event.duration().toSeconds() + ",\n"
                + "  \"error\": " + escapedError + ",\n"
                + "  \"timestamp\": \"" + Instant.now() + "\"";

        String metricsFragment = metrics != null ? metrics.toJsonFragment() : "";
        return base + metricsFragment + "\n}";
    }
}
