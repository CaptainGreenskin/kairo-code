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
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Simple in-memory message bus for team communication.
 */
public class MessageBus {

    private final CopyOnWriteArrayList<Message> messages = new CopyOnWriteArrayList<>();

    public record Message(String teamId, String sender, String content, long timestamp) {}

    public void broadcast(String teamId, String sender, String content, TeamManager teamManager) {
        messages.add(new Message(teamId, sender, content, System.currentTimeMillis()));
    }

    public List<Message> messagesForTeam(String teamId) {
        List<Message> result = new ArrayList<>();
        for (Message m : messages) {
            if (m.teamId().equals(teamId)) {
                result.add(m);
            }
        }
        return result;
    }

    public List<Message> allMessages() {
        return new ArrayList<>(messages);
    }
}
