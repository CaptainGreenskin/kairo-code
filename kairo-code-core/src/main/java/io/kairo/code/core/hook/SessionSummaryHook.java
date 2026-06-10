/*
 * Copyright 2025-2026 the Kairo authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.kairo.code.core.hook;

import io.kairo.api.hook.HookHandler;
import io.kairo.api.hook.HookPhase;
import io.kairo.api.hook.HookResult;
import io.kairo.api.hook.SessionEndEvent;
import io.kairo.api.memory.MemoryEntry;
import io.kairo.api.memory.MemoryScope;
import io.kairo.api.memory.MemoryStore;
import io.kairo.api.message.Msg;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Saves a compressed session summary as a memory entry at session end.
 *
 * <p>Unlike {@link io.kairo.code.core.memory.AutoMemoryHook} which extracts individual facts
 * during the session, this hook creates a single high-level summary of what was accomplished,
 * enabling the next session to pick up where this one left off.
 */
public class SessionSummaryHook {

    private static final Logger log = LoggerFactory.getLogger(SessionSummaryHook.class);
    private static final int MIN_ITERATIONS_FOR_SUMMARY = 3;

    private final MemoryStore memoryStore;

    public SessionSummaryHook(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    @HookHandler(HookPhase.SESSION_END)
    public HookResult<SessionEndEvent> onSessionEnd(SessionEndEvent event) {
        if (memoryStore == null || event.iterations() < MIN_ITERATIONS_FOR_SUMMARY) {
            return HookResult.proceed(event);
        }

        try {
            List<Msg> history = event.conversationHistorySupplier().get();
            if (history == null || history.isEmpty()) {
                return HookResult.proceed(event);
            }

            String summary = buildSummary(event, history);
            MemoryEntry entry = new MemoryEntry(
                    "session-summary-" + UUID.randomUUID().toString().substring(0, 8),
                    event.agentName(),
                    summary,
                    null,
                    MemoryScope.AGENT,
                    0.7,
                    null,
                    Set.of("auto:session-summary", "session:" + event.agentName()),
                    Instant.now(),
                    null);

            memoryStore.save(entry).subscribe(
                    saved -> log.debug("Saved session summary: {}", saved.id()),
                    err -> log.warn("Failed to save session summary: {}", err.getMessage()));
        } catch (Exception e) {
            log.debug("Session summary hook failed: {}", e.getMessage());
        }

        return HookResult.proceed(event);
    }

    private String buildSummary(SessionEndEvent event, List<Msg> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("Session summary (").append(event.iterations()).append(" iterations, ");
        sb.append(event.tokensUsed()).append(" tokens, ");
        sb.append(event.finalState()).append("):\n");

        int userMsgCount = 0;
        String lastUserMsg = null;
        for (Msg msg : history) {
            if (msg.role() == io.kairo.api.message.MsgRole.USER
                    && !msg.verbatimPreserved()) {
                userMsgCount++;
                String text = msg.text();
                if (text != null && !text.isBlank()) {
                    lastUserMsg = text.length() > 200 ? text.substring(0, 200) + "..." : text;
                }
            }
        }

        if (lastUserMsg != null) {
            sb.append("Task: ").append(lastUserMsg).append("\n");
        }
        sb.append("User messages: ").append(userMsgCount).append("\n");
        sb.append("Status: ").append(event.finalState());
        if (event.error() != null) {
            sb.append(" (").append(event.error()).append(")");
        }

        return sb.toString();
    }
}
