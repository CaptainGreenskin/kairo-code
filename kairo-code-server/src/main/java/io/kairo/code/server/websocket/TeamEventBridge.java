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
package io.kairo.code.server.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kairo.api.event.KairoEvent;
import io.kairo.api.event.KairoEventBus;
import io.kairo.api.team.TeamEvent;
import io.kairo.api.team.TeamEventType;
import io.kairo.code.core.team.persistence.TeamRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bridge that subscribes to {@link KairoEventBus} team-domain events and pushes them
 * to WebSocket subscribers via {@link AgentWebSocketHandler#broadcastTeamEvent(String, String)}.
 *
 * <h3>Backpressure strategy</h3>
 * <ul>
 *   <li><b>High-frequency events</b> (STEP_THINKING, STEP_TOOL_CALL, STEP_ARTIFACT_CHUNK):
 *       fire-and-forget — if a send fails or the session is slow, the event is dropped.
 *       These are incremental and the frontend can tolerate gaps.</li>
 *   <li><b>Lifecycle events</b> (all others): must be delivered. If send fails, they are
 *       buffered per-session and retried on the next successful send.</li>
 * </ul>
 *
 * <h3>Sequence numbers</h3>
 * Each team gets an independent monotonically increasing sequence counter. Clients can
 * detect gaps and request replay via {@link #replayEvents(WebSocketSession, String, long)}.
 */
@Component
public class TeamEventBridge {

    private static final Logger log = LoggerFactory.getLogger(TeamEventBridge.class);

    /** High-frequency event types that can be safely dropped under backpressure. */
    private static final Set<TeamEventType> HIGH_FREQ_TYPES = EnumSet.of(
            TeamEventType.STEP_THINKING,
            TeamEventType.STEP_TOOL_CALL,
            TeamEventType.STEP_ARTIFACT_CHUNK
    );

    private final AgentWebSocketHandler webSocketHandler;
    private final KairoEventBus eventBus;
    private final TeamRepository teamRepository;
    private final ObjectMapper objectMapper;

    /** Per-team monotonic sequence counter. */
    private final ConcurrentHashMap<String, AtomicLong> seqCounters = new ConcurrentHashMap<>();

    /** Per-session buffered lifecycle events awaiting retry. Key = session ID. */
    private final ConcurrentHashMap<String, Queue<String>> lifecycleBuffers = new ConcurrentHashMap<>();

    @Autowired
    public TeamEventBridge(
            AgentWebSocketHandler webSocketHandler,
            @Autowired(required = false) KairoEventBus eventBus,
            @Autowired(required = false) TeamRepository teamRepository,
            ObjectMapper objectMapper) {
        this.webSocketHandler = webSocketHandler;
        this.eventBus = eventBus;
        this.teamRepository = teamRepository;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        if (eventBus == null) {
            log.info("TeamEventBridge: no KairoEventBus available, bridge inactive");
            return;
        }
        eventBus.subscribe(KairoEvent.DOMAIN_TEAM)
                .subscribe(
                        this::handleEvent,
                        err -> log.error("TeamEventBridge subscription error", err)
                );
        log.info("TeamEventBridge: subscribed to team domain events");
    }

    private void handleEvent(KairoEvent kairoEvent) {
        Object payload = kairoEvent.payload();
        if (!(payload instanceof TeamEvent teamEvent)) {
            log.debug("TeamEventBridge: ignoring non-TeamEvent payload: {}", kairoEvent.eventType());
            return;
        }

        String teamId = teamEvent.teamId();
        long seq = nextSeq(teamId);
        TeamEventType eventType = teamEvent.type();
        boolean isHighFreq = HIGH_FREQ_TYPES.contains(eventType);

        String json = serializeEvent(teamEvent, seq);
        if (json == null) {
            return; // serialization failed, already logged
        }

        Set<WebSocketSession> subscribers = webSocketHandler.getTeamSubscribers(teamId);
        if (subscribers.isEmpty()) {
            log.debug("TeamEventBridge: no subscribers for team {}, seq {}", teamId, seq);
            return;
        }

        for (WebSocketSession session : subscribers) {
            if (!session.isOpen()) {
                continue;
            }

            // First, flush any buffered lifecycle events for this session
            flushLifecycleBuffer(session);

            if (isHighFreq) {
                // Fire-and-forget: drop if send fails
                sendQuietly(session, json);
            } else {
                // Lifecycle event: must be delivered
                if (!sendQuietly(session, json)) {
                    bufferLifecycleEvent(session, json);
                }
            }
        }
    }

    /**
     * Replay persisted events from a given seq for reconnecting clients.
     * Called by {@link AgentWebSocketHandler} when subscribeTeam has lastSeq > 0.
     *
     * @param session the reconnecting WebSocket session
     * @param teamId  the team to replay events for
     * @param fromSeq replay events with seq strictly greater than this value
     */
    public void replayEvents(WebSocketSession session, String teamId, long fromSeq) {
        if (teamRepository == null) {
            log.warn("TeamEventBridge: cannot replay — no TeamRepository available");
            return;
        }

        teamRepository.loadEvents(teamId)
                .subscribe(teamEvent -> {
                    long seq = nextSeq(teamId);
                    if (seq <= fromSeq) {
                        return; // skip events the client already has
                    }
                    String json = serializeEvent(teamEvent, seq);
                    if (json != null) {
                        sendQuietly(session, json);
                    }
                }, err -> log.error("TeamEventBridge: replay failed for team {}", teamId, err));
    }

    /**
     * Return the current sequence number for a team (for testing/observability).
     */
    long currentSeq(String teamId) {
        AtomicLong counter = seqCounters.get(teamId);
        return counter == null ? 0 : counter.get();
    }

    private long nextSeq(String teamId) {
        return seqCounters.computeIfAbsent(teamId, k -> new AtomicLong(0)).incrementAndGet();
    }

    private String serializeEvent(TeamEvent teamEvent, long seq) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", "TEAM_EVENT");
            envelope.put("teamId", teamEvent.teamId());
            envelope.put("eventType", teamEvent.type().name());
            envelope.put("seq", seq);
            // stepId is carried in attributes if present
            Object stepId = teamEvent.attributes().get("stepId");
            if (stepId != null) {
                envelope.put("stepId", stepId);
            }
            envelope.put("attributes", teamEvent.attributes());
            envelope.put("timestamp", teamEvent.timestamp().toString());
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.warn("TeamEventBridge: failed to serialize event for team {}: {}",
                    teamEvent.teamId(), e.getMessage());
            return null;
        }
    }

    /**
     * Attempt to send a text message to a session. Returns true if successful.
     */
    private boolean sendQuietly(WebSocketSession session, String json) {
        if (!session.isOpen()) return false;
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
            return true;
        } catch (Exception e) {
            log.debug("TeamEventBridge: send failed on ws {}: {}", session.getId(), e.getMessage());
            return false;
        }
    }

    private void bufferLifecycleEvent(WebSocketSession session, String json) {
        lifecycleBuffers
                .computeIfAbsent(session.getId(), k -> new ConcurrentLinkedQueue<>())
                .offer(json);
    }

    private void flushLifecycleBuffer(WebSocketSession session) {
        Queue<String> buffer = lifecycleBuffers.get(session.getId());
        if (buffer == null || buffer.isEmpty()) return;

        String pending;
        while ((pending = buffer.peek()) != null) {
            if (sendQuietly(session, pending)) {
                buffer.poll(); // remove on successful send
            } else {
                break; // session still blocked, stop flushing
            }
        }
    }
}
