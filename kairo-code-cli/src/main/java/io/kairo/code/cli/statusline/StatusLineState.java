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

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Runtime state shipped to the user's status-line shell script via stdin as JSON.
 *
 * <p>Field shape mirrors Claude Code's {@code buildStatusLineCommandInput} so existing
 * statusline scripts (`jq '.model.display_name'`, `jq '.context_window.used_percentage'`)
 * port over unchanged. Fields the kairo runtime cannot populate (e.g. rate-limit windows
 * for non-Anthropic providers) are left {@code null} and Jackson omits them.
 *
 * @param sessionId UUID stable for the REPL session
 * @param sessionName user-chosen title for the session, nullable
 * @param model nested model info (id + display name); never null but fields may be empty
 * @param workspace nested cwd info; never null
 * @param version kairo-code build version, e.g. "0.2.0-SNAPSHOT"
 * @param contextWindow current token usage + budget; never null
 * @param agent name of the active agent (e.g. parent name vs sub-agent); nullable
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StatusLineState(
        String sessionId,
        String sessionName,
        ModelInfo model,
        WorkspaceInfo workspace,
        String version,
        ContextWindowInfo contextWindow,
        AgentInfo agent) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ModelInfo(String id, String displayName) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkspaceInfo(String currentDir, String projectDir) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContextWindowInfo(
            Long totalInputTokens,
            Long contextWindowSize,
            Double usedPercentage,
            Double remainingPercentage,
            String compactionPhase) {

        /**
         * Compute the derived percentage fields from raw values. Returns zero when
         * {@code contextWindowSize} is zero or negative so scripts can safely format
         * {@code .used_percentage} without divide-by-zero handling.
         */
        public static ContextWindowInfo from(
                long inputTokens, long windowSize, String compactionPhase) {
            double used = 0.0;
            double remaining = 0.0;
            if (windowSize > 0) {
                used = 100.0 * inputTokens / windowSize;
                remaining = Math.max(0.0, 100.0 - used);
            }
            return new ContextWindowInfo(
                    inputTokens, windowSize, used, remaining, compactionPhase);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentInfo(String name) {}
}
