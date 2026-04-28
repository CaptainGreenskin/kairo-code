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
import io.kairo.api.hook.ToolResultEvent;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;

/**
 * Intercepts {@code bash} tool results that contain {@code BUILD FAILURE} and injects a structured
 * summary of the test failures into the conversation context.
 *
 * <p>Without this hook the agent must parse raw Maven output itself, which is error-prone and leads
 * to missed failures or unfocused re-edits.
 *
 * <p>Phase: {@link HookPhase#TOOL_RESULT} — fires immediately after every tool execution.
 */
public final class TestFailureFeedbackHook {

    private static final int MAX_ERROR_LINES = 20;

    private int injectionCount;
    private String lastInjectedSummary;
    private final int maxInjections;

    public TestFailureFeedbackHook() {
        this(5);
    }

    public TestFailureFeedbackHook(int maxInjections) {
        this.maxInjections = maxInjections;
    }

    @HookHandler(HookPhase.TOOL_RESULT)
    public HookResult<ToolResultEvent> onToolResult(ToolResultEvent event) {
        if (!"bash".equals(event.toolName())) {
            return HookResult.proceed(event);
        }

        String content = event.result().content();
        if (content == null || !content.contains("BUILD FAILURE")) {
            return HookResult.proceed(event);
        }

        String summary = extractErrorSummary(content);
        if (summary == null || summary.isBlank()) {
            return HookResult.proceed(event);
        }

        // Idempotency: skip if the same summary was already injected.
        if (summary.equals(lastInjectedSummary)) {
            return HookResult.proceed(event);
        }

        if (injectionCount >= maxInjections) {
            return HookResult.proceed(event);
        }

        injectionCount++;
        lastInjectedSummary = summary;

        int failureCount = countFailures(content);
        String message = "Test failures detected (" + failureCount + " failures):\n"
                + summary
                + "\n\nFix these specific failures. Run `mvn test` again after each fix to confirm.";

        return HookResult.inject(
                event, Msg.of(MsgRole.USER, message), "TestFailureFeedbackHook");
    }

    /** Extract [ERROR] lines from Maven output, capped at {@link #MAX_ERROR_LINES}. */
    static String extractErrorSummary(String content) {
        StringBuilder sb = new StringBuilder();
        int lines = 0;
        for (String line : content.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[ERROR]")) {
                if (lines > 0) {
                    sb.append("\n");
                }
                sb.append(trimmed);
                lines++;
                if (lines >= MAX_ERROR_LINES) {
                    break;
                }
            }
        }
        return sb.toString();
    }

    /** Count lines that look like a test failure (contains "Tests run:" with failures or "FAILURE"). */
    static int countFailures(String content) {
        int count = 0;
        for (String line : content.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.contains("Tests run:")
                    && (trimmed.contains("Failures: ") || trimmed.contains("Errors: "))
                    && !trimmed.contains("Failures: 0")) {
                // Parse the failure count from "Failures: N" or "Errors: N"
                for (String part : trimmed.split(",")) {
                    part = part.trim();
                    if ((part.startsWith("Failures: ") || part.startsWith("Errors: "))) {
                        try {
                            String num = part.split(":")[1].trim();
                            // stop at next space (e.g. "Failures: 1, Skipped: 0")
                            int spaceIdx = num.indexOf(' ');
                            if (spaceIdx > 0) {
                                num = num.substring(0, spaceIdx);
                            }
                            int n = Integer.parseInt(num);
                            if (n > 0) {
                                count += n;
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        }
        // Fallback: if we couldn't parse any count but there are [ERROR] lines, report 1.
        if (count == 0) {
            for (String line : content.lines().toList()) {
                if (line.trim().startsWith("[ERROR]")
                        && (line.contains("Test") || line.contains("test")
                            || line.contains("assert") || line.contains("Assert"))) {
                    count++;
                }
            }
        }
        return Math.max(count, 1);
    }
}
