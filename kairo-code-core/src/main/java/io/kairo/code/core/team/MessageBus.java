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
package io.kairo.code.core.team;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * In-process message bus for inter-session communication within teams.
 * Not designed to cross JVM boundaries.
 */
public class MessageBus {

    public record TeamMessage(
        String messageId,
        String fromSessionId,
        String toSessionId,
        String content,
        long timestamp
    ) {}

    private final ConcurrentHashMap<String, BlockingQueue<TeamMessage>> mailboxes =
        new ConcurrentHashMap<>();

    /**
     * Send a message to a specific session.
     *
     * @return the generated message ID
     */
    public String send(String toSessionId, String fromSessionId, String content) {
        String msgId = "msg-" + UUID.randomUUID().toString().substring(0, 8);
        TeamMessage msg = new TeamMessage(msgId, fromSessionId, toSessionId,
            content, System.currentTimeMillis());
        mailboxes.computeIfAbsent(toSessionId, k -> new LinkedBlockingQueue<>()).offer(msg);
        return msgId;
    }

    /**
     * Drain all pending messages for the given session.
     */
    public List<TeamMessage> poll(String sessionId) {
        BlockingQueue<TeamMessage> queue = mailboxes.get(sessionId);
        if (queue == null || queue.isEmpty()) return List.of();
        List<TeamMessage> msgs = new ArrayList<>();
        queue.drainTo(msgs);
        return msgs;
    }

    /**
     * Broadcast a message to all members of a team.
     *
     * @return true if the team was found and messages were sent
     */
    public boolean broadcast(String teamId, String fromSessionId, String content,
                              TeamManager teamManager) {
        return teamManager.getTeam(teamId)
            .map(team -> {
                team.members().forEach(m -> send(m.sessionId(), fromSessionId, content));
                return true;
            })
            .orElse(false);
    }
}
